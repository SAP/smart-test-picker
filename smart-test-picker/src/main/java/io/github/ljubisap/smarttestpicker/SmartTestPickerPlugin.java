// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.testing.Test;

import com.google.gson.Gson;

import io.github.ljubisap.smarttestpicker.engine.ExecToXmlEngine;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;


/**
 * Main entry point for the Smart Test Picker Gradle plugin.
 *
 * <p>This plugin enables regression test selection in Java projects by tracking
 * per-test runtime coverage via JaCoCo and selecting only the tests impacted
 * by code changes.</p>
 *
 * <p>Registered tasks:</p>
 * <ul>
 *   <li>{@code generateSmartReports} — converts per-test {@code .exec} files into JaCoCo XML reports</li>
 *   <li>{@code generateTestCoverageJson} — parses XML reports into a unified JSON coverage map</li>
 *   <li>{@code selectTests} — analyzes git diff, cross-references with coverage map, outputs impacted tests</li>
 *   <li>{@code smartTest} — runs only the tests selected by selectTests (reads selected-tests.json)</li>
 *   <li>{@code generateTestReport} — generates an HTML dashboard report of test selection results</li>
 *   <li>{@code generateSmartTestMapping} — convenience task that chains test + report + JSON generation</li>
 * </ul>
 *
 * <p>Plugin ID: {@code io.github.ljubisap.smart-test-picker}</p>
 *
 * @see SmartTestPickerExtension
 */
public class SmartTestPickerPlugin implements Plugin<Project>
{

	/**
	 * Applies the plugin to the given Gradle project.
	 * Registers the extension DSL, all tasks, and optional JaCoCo agent configuration.
	 *
	 * @param project the Gradle project to apply the plugin to
	 */
	@Override
	public void apply(Project project)
	{
		project.getLogger().lifecycle("[SmartTestPickerPlugin] Plugin applied.");

		SmartTestPickerExtension ext = project.getExtensions()
				.create("smartTestPicker", SmartTestPickerExtension.class);
		ext.getBaseBranch().convention("main");
		ext.getMaxCommitDistance().convention(500);
		ext.getClassLevelSelection().convention(false);
		ext.getFullSuiteTriggers().convention(java.util.List.of());

		// Task: generateSmartReports
		project.getTasks().register("generateSmartReports", task -> {
			task.setGroup("verification");
			task.setDescription("Generates JaCoCo XML reports for each session_*.exec file");

			File buildDir = project.getBuildDir();
			File projectDir = project.getProjectDir();

			task.doLast(t -> {
				File execDir = new File(buildDir, "jacoco");
				File classesDir = new File(buildDir, "classes/java/main");
				File sourceDir = new File(projectDir, "src/main/java");
				File reportDir = new File(buildDir, "jacoco-xml");

				try
				{
					new ExecToXmlEngine().generateReports(
							execDir, classesDir, sourceDir, reportDir,
							new GradleEngineLogger(t.getLogger()));
				}
				catch (IOException e)
				{
					throw new RuntimeException("[SmartTestPickerPlugin] Failed to generate reports", e);
				}
			});
		});

		// Optional all-in-one task
		project.getTasks().register("generateSmartTestMapping", task -> {
			task.setGroup("verification");
			task.setDescription("Runs tests, generates reports, and builds test-to-class mapping");
			task.dependsOn("test", "generateSmartReports", "generateTestCoverageJson");
		});

		// Optional JaCoCo agent setup (only if config exists)
		Configuration jacocoAgent = project.getConfigurations().findByName("jacocoAgent");
		if (jacocoAgent != null)
		{
			project.getTasks().withType(Test.class).configureEach(test -> {
				test.finalizedBy("generateSmartReports");
			});
		}
		else
		{
			project.getLogger().warn("[SmartTestPickerPlugin] jacocoAgent configuration not found. Skipping agent setup.");
		}

		project.getTasks().register("generateTestCoverageJson", GenerateTestCoverageJsonTask.class, task -> {
			task.getReportsDir().set(project.file("build/jacoco-xml"));
			task.getOutputFile().set(project.getLayout().getBuildDirectory().file("test-coverage-map.json"));
			task.getBaseBranch().set(ext.getBaseBranch());
		});

		project.getTasks().register("selectTests", SelectTestsTask.class, task -> {
			task.setGroup("verification");
			task.setDescription("Selects tests impacted by code changes based on coverage map and git diff");
			task.getCoverageMapFile().set(project.getLayout().getBuildDirectory().file("test-coverage-map.json"));
			task.getSelectedTestsFile().set(project.getLayout().getBuildDirectory().file("selected-tests.json"));
			task.getMaxCommitDistance().set(ext.getMaxCommitDistance());
			task.getFullSuiteTriggers().set(ext.getFullSuiteTriggers());
			// Always re-run: selection depends on git state which Gradle cannot track
			task.getOutputs().upToDateWhen(t -> false);
		});

		project.getTasks().register("generateTestReport", GenerateTestReportTask.class, task -> {
			task.setGroup("verification");
			task.setDescription("Generates an HTML dashboard report of test selection results");
			task.getCoverageMapFile().set(project.getLayout().getBuildDirectory().file("test-coverage-map.json"));
			task.getSelectedTestsFile().set(project.getLayout().getBuildDirectory().file("selected-tests.json"));
			task.getReportFile().set(project.getLayout().getBuildDirectory()
					.file("reports/smart-test-picker/index.html"));
			task.getMaxCommitDistance().set(ext.getMaxCommitDistance());
			task.getJacocoXmlDir().set(project.getLayout().getBuildDirectory().dir("jacoco-xml"));
			task.getSourceDir().set(project.getLayout().getProjectDirectory().dir("src/main/java"));
			task.getClassLevelSelection().set(ext.getClassLevelSelection());
			task.mustRunAfter("selectTests");
			// Always re-run: report depends on git state (branch, changed code)
			task.getOutputs().upToDateWhen(t -> false);
		});

		// smartTest: runs only the tests selected by selectTests.
		// Must be invoked as a separate Gradle command AFTER selectTests has written selected-tests.json.
		// Usage: ./gradlew selectTests && ./gradlew smartTest
		project.getTasks().register("smartTest", Test.class, task -> {
			task.setGroup("verification");
			task.setDescription("Runs only tests selected by SmartTestPicker based on code changes");
			task.useJUnitPlatform();
		});

		// Remote store tasks — pull/push coverage maps from a remote HTTP store
		project.getTasks().register("pullCoverageMap", PullCoverageMapTask.class, task -> {
			task.setGroup("verification");
			task.setDescription("Pulls coverage map from remote store");
			task.getUrl().set(ext.getRemoteStore().getUrl());
			task.getBaseBranch().set(ext.getBaseBranch());
			task.getUsername().set(ext.getRemoteStore().getCredentials().getUsername());
			task.getPassword().set(ext.getRemoteStore().getCredentials().getPassword());
			task.getOutputFile().set(project.getLayout().getBuildDirectory().file("test-coverage-map.json"));
			task.getOutputs().upToDateWhen(t -> false);
			task.onlyIf(t -> ext.getRemoteStore().getUrl().isPresent());
		});

		project.getTasks().register("pushCoverageMap", PushCoverageMapTask.class, task -> {
			task.setGroup("verification");
			task.setDescription("Pushes coverage map to remote store");
			task.getUrl().set(ext.getRemoteStore().getUrl());
			task.getBaseBranch().set(ext.getBaseBranch());
			task.getUsername().set(ext.getRemoteStore().getCredentials().getUsername());
			task.getPassword().set(ext.getRemoteStore().getCredentials().getPassword());
			task.getInputFile().set(project.getLayout().getBuildDirectory().file("test-coverage-map.json"));
			task.onlyIf(t -> ext.getRemoteStore().getUrl().isPresent()
					&& ext.getRemoteStore().getPush().getOrElse(false));
		});

		// Apply test filters at configuration time (afterEvaluate) — reads selected-tests.json
		// which must exist from a prior ./gradlew selectTests invocation
		project.afterEvaluate(p -> {
			p.getTasks().named("smartTest", Test.class, smartTest -> {
				// Mirror classpath from the standard test task
				Test testTask = (Test) p.getTasks().getByName("test");
				smartTest.setTestClassesDirs(testTask.getTestClassesDirs());
				smartTest.setClasspath(testTask.getClasspath());

				File selectedFile = p.getLayout().getBuildDirectory()
						.file("selected-tests.json").get().getAsFile();
				File coverageMapFile = p.getLayout().getBuildDirectory()
						.file("test-coverage-map.json").get().getAsFile();

				boolean classLevel = ext.getClassLevelSelection().getOrElse(false);
				applySmartTestFilters(smartTest, selectedFile, coverageMapFile, p.getLogger(), classLevel);
			});
		});

	}

	/**
	 * Applies test filters to the smartTest task based on selected-tests.json and new test detection.
	 *
	 * <p>Logic:</p>
	 * <ul>
	 *   <li>FULL_SUITE or file missing → no filter, run everything</li>
	 *   <li>NONE → include only unmapped tests (if any), otherwise skip all</li>
	 *   <li>SELECTED → include selected tests + unmapped tests + any additional new tests found at config time</li>
	 * </ul>
	 */
	private void applySmartTestFilters(Test smartTest, File selectedFile, File coverageMapFile,
			org.gradle.api.logging.Logger logger, boolean classLevelSelection)
	{
		if (!selectedFile.exists())
		{
			logger.lifecycle(
					"[SmartTestPicker] selected-tests.json not found — running full suite. Run selectTests first.");
			return;
		}

		// Parse selected-tests.json
		SelectionOutput output;
		try
		{
			String json = new String(Files.readAllBytes(selectedFile.toPath()));
			output = new Gson().fromJson(json, SelectionOutput.class);
		}
		catch (IOException e)
		{
			logger.warn("[SmartTestPicker] Failed to read selected-tests.json — running full suite");
			return;
		}

		if (output == null || "FULL_SUITE".equals(output.getStatus()))
		{
			logger.lifecycle("[SmartTestPicker] Full suite required — no filter applied");
			return;
		}

		// Collect unmapped tests from JSON + detect any additional new tests at config time
		Set<String> unmappedTestClasses = new LinkedHashSet<>();
		if (output.getUnmappedTests() != null)
		{
			unmappedTestClasses.addAll(output.getUnmappedTests().keySet());
		}
		// Belt-and-suspenders: also detect new tests at config time
		Set<String> additionalNew = detectNewTestClasses(smartTest, coverageMapFile, logger);
		unmappedTestClasses.addAll(additionalNew);

		boolean isNone = "NONE".equals(output.getStatus());
		java.util.List<String> selectedTests = output.getSelectedTests() != null
				? output.getSelectedTests() : java.util.List.of();

		if (isNone && unmappedTestClasses.isEmpty())
		{
			logger.lifecycle("[SmartTestPicker] No tests to run (NONE + no unmapped tests)");
			smartTest.getFilter().includeTestsMatching("__no_tests_to_run__");
			return;
		}

		// Apply filters: selected tests + unmapped test classes
		Set<String> includePatterns = new LinkedHashSet<>();

		for (String test : selectedTests)
		{
			if (classLevelSelection)
			{
				int hash = test.indexOf('#');
				String className = hash > 0 ? test.substring(0, hash) : test;
				includePatterns.add(className + ".*");
			}
			else
			{
				includePatterns.add(test.replace('#', '.'));
			}
		}

		for (String newClass : unmappedTestClasses)
		{
			includePatterns.add(newClass + ".*");
		}

		if (includePatterns.isEmpty())
		{
			logger.lifecycle("[SmartTestPicker] No tests to run");
			smartTest.getFilter().includeTestsMatching("__no_tests_to_run__");
			return;
		}

		for (String pattern : includePatterns)
		{
			smartTest.getFilter().includeTestsMatching(pattern);
		}
		smartTest.getFilter().setFailOnNoMatchingTests(false);

		logger.lifecycle("[SmartTestPicker] smartTest filter: {} selected tests + {} unmapped test classes{}",
				selectedTests.size(), unmappedTestClasses.size(),
				classLevelSelection ? " (class-level selection)" : "");
	}

	/**
	 * Detects test classes that exist on disk but are not in the coverage map.
	 * These are "new tests" that should always be run.
	 *
	 * @return set of simple class names of new test classes
	 */
	private Set<String> detectNewTestClasses(Test smartTest, File coverageMapFile,
			org.gradle.api.logging.Logger logger)
	{
		Set<String> newTestClasses = new LinkedHashSet<>();

		if (!coverageMapFile.exists())
		{
			return newTestClasses;
		}

		// Load coverage map to get known test class names
		Set<String> knownTestClasses = new HashSet<>();
		try
		{
			CoverageMap coverageMap = io.github.ljubisap.smarttestpicker.mapper.CoverageMapReader.load(coverageMapFile);
			if (coverageMap != null && coverageMap.getTestMappings() != null)
			{
				for (String testName : coverageMap.getTestMappings().keySet())
				{
					// Extract class name from "TestClass#testMethod"
					int hash = testName.indexOf('#');
					if (hash > 0)
					{
						knownTestClasses.add(testName.substring(0, hash));
					}
				}
			}
		}
		catch (IOException e)
		{
			logger.warn("[SmartTestPicker] Failed to read coverage map for new test detection");
			return newTestClasses;
		}

		// Scan compiled test class files
		smartTest.getTestClassesDirs().getFiles().forEach(dir -> {
			if (!dir.exists())
				return;
			try
			{
				Files.walk(dir.toPath())
						.filter(p -> p.toString().endsWith(".class"))
						.forEach(classFile -> {
							String fileName = classFile.getFileName().toString();
							String className = fileName.replace(".class", "");

							// Skip inner classes and non-test classes
							if (className.contains("$"))
								return;

							// Check if this class is known in the coverage map
							if (!knownTestClasses.contains(className))
							{
								newTestClasses.add(className);
							}
						});
			}
			catch (IOException e)
			{
				logger.warn("[SmartTestPicker] Failed to scan test classes in {}", dir);
			}
		});

		if (!newTestClasses.isEmpty())
		{
			logger.lifecycle("[SmartTestPicker] New test classes detected (not in coverage map): {}",
					newTestClasses);
		}

		return newTestClasses;
	}

}
