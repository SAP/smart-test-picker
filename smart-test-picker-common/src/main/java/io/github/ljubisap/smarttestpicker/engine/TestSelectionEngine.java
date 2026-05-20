// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ljubisap.smarttestpicker.change.GitChangeDetector;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapMetadata;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapReader;
import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;
import io.github.ljubisap.smarttestpicker.selector.SelectionResult;
import io.github.ljubisap.smarttestpicker.selector.TestSelector;


/**
 * Core test selection orchestration engine, independent of any build tool.
 *
 * <p>Encapsulates the full 8-step selection flow:</p>
 * <ol>
 *   <li>Load coverage map and validate metadata</li>
 *   <li>Verify commitId exists and is within commit distance</li>
 *   <li>Check fullSuiteTriggers against changed files</li>
 *   <li>Detect changed classes and methods via git diff</li>
 *   <li>Run TestSelector for dual-granularity matching</li>
 *   <li>Detect unmapped/new test classes</li>
 *   <li>Return SelectionOutput with status and selected tests</li>
 * </ol>
 *
 * <p>Extracted from the Gradle {@code SelectTestsTask} to be reusable across
 * both Gradle and Maven plugins.</p>
 */
public class TestSelectionEngine
{

	/**
	 * Runs the full test selection flow and returns the result.
	 *
	 * @param coverageMapFile    the coverage map JSON file
	 * @param testClassesDir     compiled test classes directory
	 * @param projectDir         project root directory (for git commands)
	 * @param maxCommitDistance   maximum commit distance before map is stale
	 * @param fullSuiteTriggers  glob patterns that force full suite when matched
	 * @param logger             engine logger
	 * @return the selection output (status, reason, selected tests, unmapped tests)
	 */
	public SelectionOutput select(File coverageMapFile, File testClassesDir, File projectDir,
			int maxCommitDistance, List<String> fullSuiteTriggers, EngineLogger logger)
	{
		List<File> dirs = testClassesDir != null ? List.of(testClassesDir) : List.of();
		return select(coverageMapFile, dirs, projectDir, maxCommitDistance, fullSuiteTriggers, logger);
	}

	/**
	 * Runs the full test selection flow with multiple test-classes directories (multi-module).
	 */
	public SelectionOutput select(File coverageMapFile, List<File> testClassesDirs, File projectDir,
			int maxCommitDistance, List<String> fullSuiteTriggers, EngineLogger logger)
	{
		return select(coverageMapFile, testClassesDirs, List.of(), projectDir,
				maxCommitDistance, fullSuiteTriggers, logger);
	}

	/**
	 * Runs the full test selection flow with source dirs for abstract/interface filtering.
	 *
	 * @param coverageMapFile   the JSON coverage map file
	 * @param testClassesDirs   compiled test-classes directories (one per module)
	 * @param testSourceDirs    test source directories for filtering abstract/interface classes
	 * @param projectDir        git working directory
	 * @param maxCommitDistance  max commits before falling back to full suite
	 * @param fullSuiteTriggers glob patterns that trigger full suite if matched by changed files
	 * @param logger            engine logger
	 */
	public SelectionOutput select(File coverageMapFile, List<File> testClassesDirs,
			List<File> testSourceDirs, File projectDir,
			int maxCommitDistance, List<String> fullSuiteTriggers, EngineLogger logger)
	{
		GitChangeDetector git = new GitChangeDetector(projectDir);
		NewTestDetector newTestDetector = new NewTestDetector();

		// Load coverage map
		CoverageMap coverageMap;
		if (coverageMapFile.exists())
		{
			try
			{
				coverageMap = CoverageMapReader.load(coverageMapFile);
			}
			catch (IOException e)
			{
				logger.warn("[SmartTestPicker] Failed to read coverage map: {}", e.getMessage());
				return new SelectionOutput("FULL_SUITE", "Failed to read coverage map: " + e.getMessage(),
						List.of(), Map.of());
			}
		}
		else
		{
			logger.warn("[SmartTestPicker] Coverage map not found: {}", coverageMapFile);
			return new SelectionOutput("FULL_SUITE", "Coverage map not found", List.of(), Map.of());
		}

		if (coverageMap == null || coverageMap.getMetadata() == null)
		{
			logger.warn("[SmartTestPicker] Coverage map or metadata is missing — falling back to full suite");
			return new SelectionOutput("FULL_SUITE", "Coverage map or metadata missing",
					List.of(), newTestDetector.detect(coverageMap, git, null, testClassesDirs, testSourceDirs, logger));
		}

		CoverageMapMetadata metadata = coverageMap.getMetadata();
		String commitId = metadata.getCommitId();

		// Validate commitId
		if (commitId == null || commitId.isEmpty())
		{
			logger.warn("[SmartTestPicker] No commitId in coverage map metadata — falling back to full suite");
			return new SelectionOutput("FULL_SUITE", "No commitId in coverage map metadata",
					List.of(), newTestDetector.detect(coverageMap, git, null, testClassesDirs, testSourceDirs, logger));
		}

		if (!git.isValidCommit(commitId))
		{
			logger.warn("[SmartTestPicker] Invalid commitId {} — possible rebase or force push", commitId);
			return new SelectionOutput("FULL_SUITE",
					"commitId " + commitId + " is not a valid git commit (rebase/force push?)",
					List.of(), newTestDetector.detect(coverageMap, git, null, testClassesDirs, testSourceDirs, logger));
		}

		// Check commit distance
		int distance = git.getCommitDistance(commitId);
		if (distance > maxCommitDistance)
		{
			logger.warn("[SmartTestPicker] Commit distance {} exceeds max {} — mapping is too stale", distance, maxCommitDistance);
			return new SelectionOutput("FULL_SUITE",
					"Commit distance " + distance + " exceeds max " + maxCommitDistance + " \u2014 mapping is too stale",
					List.of(), newTestDetector.detect(coverageMap, git, commitId, testClassesDirs, testSourceDirs, logger));
		}

		// Check full-suite trigger patterns
		if (fullSuiteTriggers != null && !fullSuiteTriggers.isEmpty())
		{
			Set<String> changedFiles = git.getChangedFiles(commitId);
			String matchedFile = findTriggerMatch(changedFiles, fullSuiteTriggers);
			if (matchedFile != null)
			{
				logger.info("[SmartTestPicker] Full suite triggered by file: {}", matchedFile);
				return new SelectionOutput("FULL_SUITE",
						"Changed file matches fullSuiteTrigger pattern: " + matchedFile,
						List.of(), newTestDetector.detect(coverageMap, git, commitId, testClassesDirs, testSourceDirs, logger));
			}
		}

		// Ensure .gitattributes has Java diff driver for method-level detection
		git.ensureJavaDiffDriver();

		// Get changed classes and methods
		Set<String> changedClasses = git.getChangedClasses(commitId);
		Set<String> changedMethods = git.getChangedMethods(commitId);
		logger.info("[SmartTestPicker] Changed classes since {}: {}", commitId.substring(0, 7), changedClasses);
		if (!changedMethods.isEmpty())
		{
			logger.info("[SmartTestPicker] Changed methods: {}", changedMethods);
		}

		// Detect new test classes
		Map<String, String> unmappedTests = newTestDetector.detect(coverageMap, git, commitId, testClassesDirs, testSourceDirs, logger);

		if (changedClasses.isEmpty() && changedMethods.isEmpty())
		{
			SelectionOutput out = new SelectionOutput("NONE", "No production code changes detected",
					List.of(), unmappedTests);
			out.setChangedClasses(List.of());
			return out;
		}

		// Select tests
		TestSelector selector = new TestSelector();
		SelectionResult result = selector.selectTests(coverageMapFile, changedClasses, changedMethods);

		if (result.isFullSuiteRequired())
		{
			SelectionOutput out = new SelectionOutput("FULL_SUITE", result.getReason(), List.of(), unmappedTests);
			out.setChangedClasses(new ArrayList<>(changedClasses));
			return out;
		}

		List<String> selected = new ArrayList<>(result.getSelectedTests());
		String reason = selected.size() + " tests selected out of "
				+ coverageMap.getTestMappings().size() + " total";
		SelectionOutput out = new SelectionOutput("SELECTED", reason, selected, unmappedTests);
		out.setChangedClasses(new ArrayList<>(changedClasses));
		return out;
	}

	/**
	 * Checks whether any changed file matches a full-suite trigger glob pattern.
	 */
	private String findTriggerMatch(Set<String> changedFiles, List<String> triggers)
	{
		for (String pattern : triggers)
		{
			PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
			for (String file : changedFiles)
			{
				if (matcher.matches(Paths.get(file)))
				{
					return file + " (pattern: " + pattern + ")";
				}
			}
		}
		return null;
	}
}
