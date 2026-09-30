// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;

import com.google.gson.Gson;

import com.sap.oss.smarttestpicker.engine.ExecToXmlEngine;
import com.sap.oss.smarttestpicker.execution.ExecutionIdentityMetadata;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;


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
 * <p>Plugin ID: {@code com.sap.oss.smart-test-picker}</p>
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
			// Configure after the subject build has finished configuring its Test tasks.
			// Some builds replace both systemProperties and JaCoCo's destinationFile;
			// STP's per-test listener requires the agent to write test.exec in the
			// same directory advertised through stp.exec.dir.
			project.afterEvaluate(p -> p.getTasks().withType(Test.class).configureEach(test -> {
				File execDir = new File(p.getLayout().getBuildDirectory().getAsFile().get(), "jacoco");
				test.systemProperty("stp.exec.dir", execDir.getAbsolutePath());
				// A single test.exec file is reset and moved after each logical test.
				// Multiple Gradle test-worker JVMs would race on that file and corrupt
				// per-test execution data, so collection must be serialized.
				test.setMaxParallelForks(1);
				JacocoTaskExtension jacoco = test.getExtensions().findByType(JacocoTaskExtension.class);
				if (jacoco != null)
				{
					jacoco.setDestinationFile(new File(execDir, "test.exec"));
				}
			}));
		}
		else
		{
			project.getLogger().warn("[SmartTestPickerPlugin] jacocoAgent configuration not found. Skipping agent setup.");
		}

		project.getTasks().register("generateTestCoverageJson", GenerateTestCoverageJsonTask.class, task -> {
			task.getReportsDir().set(project.getLayout().getBuildDirectory().dir("jacoco-xml"));
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
				boolean classLevel = ext.getClassLevelSelection().getOrElse(false);
				applySmartTestFilters(smartTest, selectedFile, p.getLogger(), classLevel);
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
	 *   <li>SELECTED → include selected tests and selection-time runnable unmapped tests</li>
	 * </ul>
	 */
	private void applySmartTestFilters(Test smartTest, File selectedFile,
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

		// Selection-time discovery is authoritative. Re-scanning compiled classes here
		// would reintroduce non-runnable helpers that the runnable-inventory contract
		// deliberately excludes.
		Set<String> unmappedTestClasses = new LinkedHashSet<>();
		if (output.getUnmappedTests() != null)
		{
			unmappedTestClasses.addAll(output.getUnmappedTests().keySet());
		}
		boolean isNone = "NONE".equals(output.getStatus());
		java.util.List<String> selectedTests = output.getSelectedTests() != null
				? output.getSelectedTests() : java.util.List.of();

		if (isNone && unmappedTestClasses.isEmpty())
		{
			logger.lifecycle("[SmartTestPicker] No tests to run (NONE + no unmapped tests)");
			configureNoTests(smartTest);
			return;
		}

		// Apply filters: selected tests + unmapped test classes
		Set<String> includePatterns = buildIncludePatterns(output, classLevelSelection);

		if (includePatterns.isEmpty())
		{
			logger.lifecycle("[SmartTestPicker] No tests to run");
			configureNoTests(smartTest);
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

	static void configureNoTests(Test smartTest)
	{
		smartTest.getFilter().includeTestsMatching("__no_tests_to_run__");
		smartTest.getFilter().setFailOnNoMatchingTests(false);
	}

	/**
	 * Translates selector keys into Gradle test-filter identities. Structured
	 * execution metadata is authoritative because legacy coverage keys contain a
	 * session hash and only a simple class name. Legacy maps retain the historical
	 * best-effort fallback.
	 */
	static Set<String> buildIncludePatterns(SelectionOutput output, boolean classLevelSelection)
	{
		Set<String> patterns = new LinkedHashSet<>();
		Map<String, ExecutionIdentityMetadata> identities = output.getExecutionIdentities() != null
				? output.getExecutionIdentities() : Map.of();
		for (String selected : output.getSelectedTests() != null ? output.getSelectedTests() : java.util.List.<String>of())
		{
			ExecutionIdentityMetadata identity = identities.get(selected);
			if (identity != null && identity.getTestClassFqn() != null)
			{
				if (classLevelSelection || identity.getLogicalMethodName() == null)
					patterns.add(identity.getTestClassFqn() + ".*");
				else
					patterns.add(identity.getTestClassFqn() + "." + identity.getLogicalMethodName());
			}
			else
			{
				int hash = selected.indexOf('#');
				String className = hash > 0 ? selected.substring(0, hash) : selected;
				patterns.add(classLevelSelection ? className + ".*" : selected.replace('#', '.'));
			}
		}
		if (output.getUnmappedTests() != null)
			for (String testClass : output.getUnmappedTests().keySet()) patterns.add(testClass + ".*");
		return patterns;
	}

}
