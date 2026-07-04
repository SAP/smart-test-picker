// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sap.oss.smarttestpicker.engine.EngineLogger;
import com.sap.oss.smarttestpicker.mapper.CoverageMap;
import com.sap.oss.smarttestpicker.mapper.CoverageMapReader;


/**
 * Selects which tests to run based on code changes and a coverage map.
 *
 * <p>This is the core test selection engine. Given a set of changed classes and/or methods
 * (from {@link com.sap.oss.smarttestpicker.change.GitChangeDetector}) and a coverage
 * map JSON file, it determines which tests are impacted by the changes.</p>
 *
 * <p>Selection strategy:</p>
 * <ul>
 *   <li>If method-level info is available for a changed class, only tests covering those
 *       specific methods are selected (precise matching)</li>
 *   <li>If method-level matching yields zero hits for a class that IS in the coverage map
 *       at class level, selection escalates to class-level for that class (safety fallback)</li>
 *   <li>If no method-level info is available (e.g. git couldn't determine which methods changed),
 *       all tests covering the changed class are selected (class-level fallback)</li>
 *   <li>If the coverage map is missing, invalid, or has no metadata, the full test suite is run</li>
 * </ul>
 *
 * @see SelectionResult
 * @see com.sap.oss.smarttestpicker.mapper.CoverageMap
 */
public class TestSelector
{

	private static final EngineLogger NOOP_LOGGER = new EngineLogger()
	{
		@Override
		public void info(String msg, Object... args) {}

		@Override
		public void warn(String msg, Object... args) {}
	};

	/**
	 * Select tests based on changed classes and/or methods and coverage map.
	 * <p>
	 * When method-level info is available for a class (i.e. changedMethods contains entries
	 * for that class), only method-level matching is used initially. If method-level matching
	 * produces zero hits for a class, selection escalates to class-level for safety - the
	 * changed method may be called indirectly or may not appear in the coverage map.
	 */
	public SelectionResult selectTests(File coverageMapFile, Set<String> changedClasses,
			Set<String> changedMethods, EngineLogger logger)
	{
		if (coverageMapFile == null || !coverageMapFile.exists())
		{
			logger.info("[SmartTestPicker] Coverage map file not found -> FULL_SUITE");
			return SelectionResult.fullSuite("Coverage map file not found");
		}

		logger.info("[SmartTestPicker] Loading coverage map: {}", coverageMapFile.getAbsolutePath());
		CoverageMap coverageMap = loadCoverageMap(coverageMapFile, logger);
		if (coverageMap == null)
		{
			logger.info("[SmartTestPicker] Failed to parse coverage map -> FULL_SUITE");
			return SelectionResult.fullSuite("Failed to parse coverage map");
		}

		if (coverageMap.getMetadata() == null)
		{
			logger.info("[SmartTestPicker] Coverage map has no metadata -> FULL_SUITE");
			return SelectionResult.fullSuite("Coverage map has no metadata");
		}

		if (coverageMap.getTestMappings() == null || coverageMap.getTestMappings().isEmpty())
		{
			logger.info("[SmartTestPicker] Coverage map has no test mappings -> FULL_SUITE");
			return SelectionResult.fullSuite("Coverage map has no test mappings");
		}

		logger.info("[SmartTestPicker] Coverage map loaded: {} tests, commitId={}",
				coverageMap.getTestMappings().size(),
				coverageMap.getMetadata().getCommitId() != null
						? coverageMap.getMetadata().getCommitId().substring(0, Math.min(7, coverageMap.getMetadata().getCommitId().length()))
						: "null");

		if (changedClasses.isEmpty() && changedMethods.isEmpty())
		{
			logger.info("[SmartTestPicker] No changed classes or methods -> selecting 0 tests");
			return SelectionResult.selected(Set.of());
		}

		// Determine which classes have method-level info from the diff.
		Set<String> classesWithMethodInfo = new HashSet<>();
		for (String method : changedMethods)
		{
			int hashIdx = method.indexOf('#');
			if (hashIdx > 0)
			{
				classesWithMethodInfo.add(method.substring(0, hashIdx));
			}
		}

		// Classes that need class-level fallback: changed but no method-level info available
		Set<String> classLevelOnlyClasses = new HashSet<>(changedClasses);
		classLevelOnlyClasses.removeAll(classesWithMethodInfo);

		logger.info("[SmartTestPicker] Selection strategy:");
		logger.info("[SmartTestPicker]   Method-level matching: {} classes ({})",
				classesWithMethodInfo.size(), classesWithMethodInfo);
		logger.info("[SmartTestPicker]   Class-level fallback: {} classes ({})",
				classLevelOnlyClasses.size(), classLevelOnlyClasses);

		Set<String> selectedTests = new HashSet<>();
		int methodMatchCount = 0;
		int classMatchCount = 0;

		// Track which classesWithMethodInfo actually got method-level hits
		Map<String, Integer> methodHitsPerClass = new HashMap<>();
		for (String cls : classesWithMethodInfo)
		{
			methodHitsPerClass.put(cls, 0);
		}

		// First pass: method-level and class-level matching
		for (Map.Entry<String, Map<String, List<String>>> entry : coverageMap.getTestMappings().entrySet())
		{
			String testName = entry.getKey();
			Map<String, List<String>> coverage = entry.getValue();

			// Method-level matching (precise)
			if (!changedMethods.isEmpty())
			{
				List<String> coveredMethods = coverage.get("methods");
				if (coveredMethods != null)
				{
					for (String coveredMethod : coveredMethods)
					{
						if (changedMethods.contains(coveredMethod))
						{
							selectedTests.add(testName);
							methodMatchCount++;
							// Track which class got a hit
							int hashIdx = coveredMethod.indexOf('#');
							if (hashIdx > 0)
							{
								String cls = coveredMethod.substring(0, hashIdx);
								methodHitsPerClass.computeIfPresent(cls, (k, v) -> v + 1);
							}
							logger.debug("[SmartTestPicker]   {} -> selected (method-level: covers {})",
									testName, coveredMethod);
							break;
						}
					}
				}
			}

			// Class-level fallback for classes without method info
			if (!selectedTests.contains(testName) && !classLevelOnlyClasses.isEmpty())
			{
				List<String> coveredClasses = coverage.get("classes");
				if (coveredClasses != null)
				{
					for (String coveredClass : coveredClasses)
					{
						if (classLevelOnlyClasses.contains(coveredClass))
						{
							selectedTests.add(testName);
							classMatchCount++;
							logger.debug("[SmartTestPicker]   {} -> selected (class-level: covers {})",
									testName, coveredClass);
							break;
						}
					}
				}
			}
		}

		// Safety fallback: escalate to class-level for classes where method-level
		// matching produced zero hits. A changed method not in the coverage map
		// cannot be proven safe - selecting class-level tests is conservative but correct.
		Set<String> escalatedClasses = new HashSet<>();
		for (Map.Entry<String, Integer> entry : methodHitsPerClass.entrySet())
		{
			if (entry.getValue() == 0)
			{
				escalatedClasses.add(entry.getKey());
			}
		}

		int escalatedCount = 0;
		if (!escalatedClasses.isEmpty())
		{
			logger.info("[SmartTestPicker]   Method-level produced 0 hits for: {} -> escalating to class-level",
					escalatedClasses);

			for (Map.Entry<String, Map<String, List<String>>> entry : coverageMap.getTestMappings().entrySet())
			{
				String testName = entry.getKey();
				if (selectedTests.contains(testName))
				{
					continue;
				}
				Map<String, List<String>> coverage = entry.getValue();
				List<String> coveredClasses = coverage.get("classes");
				if (coveredClasses != null)
				{
					for (String coveredClass : coveredClasses)
					{
						if (escalatedClasses.contains(coveredClass))
						{
							selectedTests.add(testName);
							escalatedCount++;
							break;
						}
					}
				}
			}

			logger.info("[SmartTestPicker]   Escalation added {} tests via class-level for: {}",
					escalatedCount, escalatedClasses);
		}

		int total = coverageMap.getTestMappings().size();
		double reduction = total > 0 ? (1.0 - (double) selectedTests.size() / total) * 100 : 0;
		logger.info("[SmartTestPicker] Selection complete: {} of {} tests selected ({} method-level, {} class-level, {} escalated, {}% reduction)",
				selectedTests.size(), total, methodMatchCount, classMatchCount, escalatedCount, String.format("%.1f", reduction));

		return SelectionResult.selected(selectedTests);
	}

	/**
	 * Select tests (without logger - backward compatible).
	 */
	public SelectionResult selectTests(File coverageMapFile, Set<String> changedClasses, Set<String> changedMethods)
	{
		return selectTests(coverageMapFile, changedClasses, changedMethods, NOOP_LOGGER);
	}

	/**
	 * Class-level only selection (backward compatible).
	 */
	public SelectionResult selectTests(File coverageMapFile, Set<String> changedClasses)
	{
		return selectTests(coverageMapFile, changedClasses, Set.of());
	}

	/**
	 * Loads and deserializes a coverage map JSON file.
	 */
	CoverageMap loadCoverageMap(File file, EngineLogger logger)
	{
		try
		{
			return CoverageMapReader.load(file);
		}
		catch (IOException e)
		{
			logger.warn("[SmartTestPicker] Failed to load coverage map {}: {}", file, e.getMessage());
			return null;
		}
	}

	CoverageMap loadCoverageMap(File file)
	{
		return loadCoverageMap(file, NOOP_LOGGER);
	}
}
