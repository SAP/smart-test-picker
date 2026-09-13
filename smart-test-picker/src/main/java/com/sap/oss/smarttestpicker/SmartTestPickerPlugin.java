// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.TaskProvider;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.gradle.api.GradleException;

import com.sap.oss.smarttestpicker.engine.ExecToXmlEngine;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;


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
		ext.getRevision().convention(project.getProviders().systemProperty("stp.revision")
				.orElse(project.getProviders().environmentVariable("GIT_COMMIT")).orElse("UNKNOWN"));
		ext.getShardId().convention(project.getProviders().systemProperty("stp.shardId"));
		ext.getRuntimeSchemaVersion().convention(project.getProviders().systemProperty("stp.schemaVersion")
				.map(Integer::parseInt).orElse(2));
		ext.getExecutionTarget().convention(project.getProviders().systemProperty("stp.executionTarget"));
		ext.getExecutableAssignmentFile().convention(project.getProviders().systemProperty("stp.executableAssignment"));
		ext.getExecutableInventoryDiscovery().convention(project.getProviders()
				.systemProperty("stp.executableInventoryDiscovery").map(Boolean::parseBoolean).orElse(false));
		ext.getMappingTestTasks().convention(java.util.List.of());
		String mappingScope = System.getProperty("stp.mappingTestTasks");
		if (mappingScope != null) ext.getMappingTestTasks().set(
				java.util.Arrays.stream(mappingScope.split(",", -1)).map(String::trim).toList());
		ext.getCoverageIncludes().convention(java.util.List.of());
		ext.getCoverageExcludes().convention(java.util.List.of());

		Configuration stpAgent = project.getConfigurations().create("stpAgent", configuration -> {
			configuration.setCanBeConsumed(false);
			configuration.setCanBeResolved(true);
			configuration.setVisible(false);
			configuration.setDescription("The single STP ASM javaagent artifact used by mapping executions.");
			configuration.attributes(attributes -> {
				attributes.attribute(Usage.USAGE_ATTRIBUTE,
						project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
				attributes.attribute(Category.CATEGORY_ATTRIBUTE,
						project.getObjects().named(Category.class, Category.LIBRARY));
				attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
						project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
			});
		});
		Configuration stpJacocoCollector = project.getConfigurations().create("stpJacocoCollector", configuration -> {
			configuration.setCanBeConsumed(false);
			configuration.setCanBeResolved(true);
			configuration.setVisible(false);
			configuration.setDescription("The STP legacy JaCoCo listener runtime used by mapping executions.");
			configuration.attributes(attributes -> {
				attributes.attribute(Usage.USAGE_ATTRIBUTE,
						project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
				attributes.attribute(Category.CATEGORY_ATTRIBUTE,
						project.getObjects().named(Category.class, Category.LIBRARY));
				attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
						project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
			});
		});
		stpAgent.defaultDependencies(dependencies -> {
			Project agentProject = project.getRootProject().findProject(":stp-agent");
			if (agentProject != null) {
				dependencies.add(project.getDependencies().project(java.util.Map.of(
						"path", agentProject.getPath(), "configuration", "stpAgentElements")));
			} else {
				dependencies.add(project.getDependencies().create(
						"com.sap.oss.smart-test-picker:stp-agent:" + pluginVersion()));
			}
		});
		stpJacocoCollector.defaultDependencies(dependencies -> {
			Project coreProject = project.getRootProject().findProject(":smart-test-picker-core");
			if (coreProject != null) {
				dependencies.add(project.getDependencies().project(java.util.Map.of("path", coreProject.getPath())));
			} else {
				dependencies.add(project.getDependencies().create(
						"com.sap.oss.smart-test-picker:smart-test-picker-core:" + pluginVersion()));
			}
		});

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

		TaskProvider<StpCoverageTest> coverageTest = project.getTasks().register(
				"generateSmartTestCoverage", StpCoverageTest.class, task -> {
			task.setGroup("verification");
			task.setDescription("Runs the dedicated Smart Test Picker coverage mapping test JVM");
			task.useJUnitPlatform();
		});

		// All-in-one mapping task; the selected backend adds only its required downstream stages.
		TaskProvider<Task> mappingTask = project.getTasks().register("generateSmartTestMapping", task -> {
			task.setGroup("verification");
			task.setDescription("Runs the selected STP coverage collector and its mapping pipeline");
			task.dependsOn(coverageTest);
		});

		TaskProvider<GenerateGradleExecutableHeadTestInventoryTask> executableInventory =
				project == project.getRootProject() ? project.getTasks().register(
						"generateGradleExecutableHeadTestInventory",
						GenerateGradleExecutableHeadTestInventoryTask.class, task -> {
						task.setGroup("verification");
						task.setDescription("Discovers executable JUnit identities by concrete Gradle Test task");
						task.getOutputFile().set(project.getLayout().getBuildDirectory()
								.file("executable-head-test-inventory.json"));
					}) : null;
		project.getTasks().register("generateGradleExecutableProjectInventory",
				GenerateGradleExecutableProjectInventoryTask.class, task -> {
			task.setGroup("verification");
			task.setDescription("Discovers project-local executable JUnit identities");
			task.getOutputFile().set(project.getLayout().getBuildDirectory()
					.file("stp/executable-project-inventory.json"));
		});
		TaskProvider<AggregateGradleExecutableCoverageTask> executableAggregate =
				project == project.getRootProject() ? project.getTasks().register(
						"aggregateGradleExecutableCoverage", AggregateGradleExecutableCoverageTask.class, task -> {
						task.setGroup("verification");
							task.getOutputFile().set(project.getLayout().getBuildDirectory()
									.file("stp/executable-fragment.json"));
						}) : null;
		project.getTasks().register("generateTestCoverageJson", GenerateTestCoverageJsonTask.class, task -> {
			task.getReportsDir().set(project.file("build/jacoco-xml"));
			task.getOutputFile().set(project.getLayout().getBuildDirectory().file("test-coverage-map.json"));
			task.getBaseBranch().set(ext.getBaseBranch());
		});

		TaskProvider<GenerateHeadTestInventoryTask> inventoryTask = project.getTasks().register(
				"generateHeadTestInventory", GenerateHeadTestInventoryTask.class, task -> {
			task.setGroup("verification");
			task.setDescription("Discovers the standard test target and writes its exact JUnit head inventory");
				task.getOutputFile().set(project.getLayout().getBuildDirectory().file("head-test-inventory.json"));
				task.getRevision().set(ext.getPrHeadRevision());
			task.dependsOn("testClasses");
		});

		project.getTasks().register("selectTests", SelectTestsTask.class, task -> {
			task.setGroup("verification");
			task.setDescription("Selects tests impacted by code changes based on coverage map and git diff");
			task.getCoverageMapFile().set(project.getLayout().getBuildDirectory().file("test-coverage-map.json"));
			task.getHeadTestInventoryFile().set(project.getLayout().getBuildDirectory().file("head-test-inventory.json"));
			task.getSelectedTestsFile().set(project.getLayout().getBuildDirectory().file("selected-tests.json"));
			task.getMaxCommitDistance().set(ext.getMaxCommitDistance());
			task.getFullSuiteTriggers().set(ext.getFullSuiteTriggers());
			task.getIntegrationRevision().set(ext.getIntegrationRevision());
			task.getPrBaseRevision().set(ext.getPrBaseRevision());
			task.getPrHeadRevision().set(ext.getPrHeadRevision());
			task.getExplicitResultFile().set(project.getLayout().getBuildDirectory().file("explicit-pr-selection-result.json"));
			task.dependsOn(inventoryTask);
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
			if (ext.getRuntimeSchemaVersion().get() == 3) return;
			Test standardTest = (Test) p.getTasks().getByName("test");
			GenerateHeadTestInventoryTask inventory = inventoryTask.get();
			inventory.getTestClassesDirs().setFrom(standardTest.getTestClassesDirs());
			inventory.getRuntimeClasspath().setFrom(standardTest.getClasspath());
			StpCoverageTest mappingTest = coverageTest.get();
			mappingTest.setTestClassesDirs(standardTest.getTestClassesDirs());
			mappingTest.setClasspath(standardTest.getClasspath());
			mirrorExecutionEnvironment(standardTest, mappingTest);
			mappingTest.getFilter().setIncludePatterns(
					standardTest.getFilter().getIncludePatterns().toArray(String[]::new));
			mappingTest.getFilter().setExcludePatterns(
					standardTest.getFilter().getExcludePatterns().toArray(String[]::new));
			CoverageCollectorBackend backend = switch (CoverageCollectorType.parse(ext.getCoverageCollector().get())) {
				case ASM -> new AsmCoverageCollectorBackend(stpAgent);
				case JACOCO -> new JacocoCoverageCollectorBackend(stpJacocoCollector);
			};
			backend.configure(p, ext, mappingTest, mappingTask.get(), null);

			p.getTasks().named("smartTest", Test.class, smartTest -> {
				// Mirror classpath from the standard test task
				Test testTask = (Test) p.getTasks().getByName("test");
				smartTest.setTestClassesDirs(testTask.getTestClassesDirs());
				smartTest.setClasspath(testTask.getClasspath());
				mirrorExecutionEnvironment(testTask, smartTest);

				File selectedFile = p.getLayout().getBuildDirectory()
						.file("selected-tests.json").get().getAsFile();
				File explicitResult = p.getLayout().getBuildDirectory()
						.file("explicit-pr-selection-result.json").get().getAsFile();
				applyExplicitExecutionGate(smartTest, explicitResult, p.getLogger());
				applySmartTestFilters(smartTest, selectedFile, p.getLogger());
			});
		});

		if (project == project.getRootProject()) project.getGradle().projectsEvaluated(gradle -> {
			if (ext.getRuntimeSchemaVersion().get() != 3) return;
			var targets = GradleExecutionTargets.resolve(project, ext.getMappingTestTasks().get());
			targets.stream().collect(java.util.stream.Collectors.groupingBy(Test::getProject)).forEach((owner, owned) -> {
				var existingPart = owner.getTasks().findByName("generateGradleExecutableProjectInventory");
				var part = existingPart instanceof GenerateGradleExecutableProjectInventoryTask inventoryPart
						? inventoryPart : owner.getTasks().create("generateGradleExecutableProjectInventory",
								GenerateGradleExecutableProjectInventoryTask.class);
				if (!part.getOutputFile().isPresent()) part.getOutputFile().set(owner.getLayout().getBuildDirectory()
						.file("stp/executable-project-inventory.json"));
				part.setRevision(ext.getRevision().get());
				owned.stream().sorted(java.util.Comparator.comparing(Test::getPath)).forEach(task -> {
					part.addTarget(task);
					task.getTestClassesDirs().getBuildDependencies().getDependencies(task).forEach(part::dependsOn);
				});
				executableInventory.get().dependsOn(part);
				executableInventory.get().getProjectInventories().from(part.getOutputFile());
			});
			configureExecutableGradleMapping(project, ext, mappingTask.get(), executableInventory.get(),
					executableAggregate.get(), stpAgent, stpJacocoCollector);
		});

	}

	private static void configureExecutableGradleMapping(Project root, SmartTestPickerExtension ext,
			Task mappingTask, GenerateGradleExecutableHeadTestInventoryTask inventoryTask,
			AggregateGradleExecutableCoverageTask aggregateTask,
			Configuration stpAgent, Configuration stpJacocoCollector) {
		boolean discovery = ext.getExecutableInventoryDiscovery().get();
		java.util.List<Test> targets = GradleExecutionTargets.resolve(root, ext.getMappingTestTasks().get());
		// The v2 synthetic task is deliberately absent from v3: every JVM must correspond
		// to one real Test-task execution target.
		mappingTask.setDependsOn(java.util.List.of());
		inventoryTask.setRevision(ext.getRevision().get());
		targets.forEach(task -> task.getTestClassesDirs().getBuildDependencies().getDependencies(task)
				.forEach(inventoryTask::dependsOn));

		if (discovery) {
			mappingTask.setEnabled(false);
			return;
		}
		if (!ext.getExecutableAssignmentFile().isPresent())
			throw new GradleException("Schema-v3 Gradle mapping requires -Dstp.executableAssignment=<file>");
		com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment assignment;
		try {
			assignment = new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(
					root.file(ext.getExecutableAssignmentFile().get()).toPath()));
		} catch (Exception failure) {
			throw new GradleException("Cannot decode schema-v3 Gradle executable assignment", failure);
		}
		java.util.List<ExecutionTarget> known = targets.stream().map(GradleExecutionTargets::forTask).toList();
		var partitions = new GradleExecutableAssignmentRouter().partition(assignment, ext.getRevision().get(),
				ext.getShardId().getOrElse("gradle:" + mappingTask.getPath()), known);
		CoverageCollectorBackend backend = switch (CoverageCollectorType.parse(ext.getCoverageCollector().get())) {
			case ASM -> new AsmCoverageCollectorBackend(stpAgent);
			case JACOCO -> new JacocoCoverageCollectorBackend(stpJacocoCollector);
		};
		aggregateTask.setAssignmentFile(root.file(ext.getExecutableAssignmentFile().get()));
		for (Test task : targets) {
			ExecutionTarget target = GradleExecutionTargets.forTask(task);
			Set<com.sap.oss.smarttestpicker.coverage.model.TestIdentity> assigned = partitions.get(target);
			task.getFilter().setIncludePatterns();
			if (assigned.isEmpty()) task.getFilter().includeTestsMatching("__stp_no_tests_assigned__");
			else assigned.stream().map(GradleExecutableAssignmentRouter::gradleFilter)
					.forEach(task.getFilter()::includeTestsMatching);
			task.getFilter().setFailOnNoMatchingTests(false);
			task.systemProperty("smartTestPicker.executionTarget", target.toString());
			if (!assigned.isEmpty()) backend.configure(task.getProject(), ext, task, mappingTask, target.toString());
			if (!assigned.isEmpty() && backend.type() == CoverageCollectorType.ASM) {
				String shard = ext.getShardId().getOrElse("gradle:" + task.getPath());
				aggregateTask.getFragments().add(new File(task.getProject().getBuildDir(), "stp/coverage/"
						+ AsmCoverageCollectorBackend.safe(task.getPath()) + "/"
						+ AsmCoverageCollectorBackend.safe(shard) + "/fragment.json"));
				aggregateTask.dependsOn(task);
			}
			if (!assigned.isEmpty() && backend.type() == CoverageCollectorType.JACOCO) {
				String shard = ext.getShardId().getOrElse("gradle:" + task.getPath());
				String segment = AsmCoverageCollectorBackend.safe(task.getPath());
				File base = new File(task.getProject().getBuildDir(), "stp/jacoco/" + segment + "/"
						+ AsmCoverageCollectorBackend.safe(shard));
				GenerateGradleJacocoExecutableFragmentTask conversion = root.getTasks().create(
						"generateStpJacocoExecutableFragment" + segment, GenerateGradleJacocoExecutableFragmentTask.class);
				conversion.setExecDir(new File(base, "exec")); conversion.setReportsDir(new File(base, "reports"));
				conversion.setSourceDir(new File(task.getProject().getProjectDir(), "src/main/java"));
				var java = task.getProject().getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class);
				conversion.setClassesDirs(java.getSourceSets().getByName("main").getOutput().getClassesDirs().getFiles().stream().toList());
				conversion.setRevision(ext.getRevision().get()); conversion.setShardId(shard);
				conversion.setTarget(target); conversion.setAssigned(assigned);
				conversion.getOutputFile().set(new File(base, "fragment.json")); conversion.dependsOn(task);
				aggregateTask.getFragments().add(new File(base, "fragment.json")); aggregateTask.dependsOn(conversion);
			}
			mappingTask.dependsOn(task);
		}
		mappingTask.dependsOn(aggregateTask);
	}

	static void applyExplicitExecutionGate(Test smartTest, File resultFile,
			org.gradle.api.logging.Logger logger)
	{
		if (!resultFile.isFile()) return;
		try
		{
			String status = JsonParser.parseString(Files.readString(resultFile.toPath()))
					.getAsJsonObject().get("status").getAsString();
			if ("BASE_OUT_OF_DATE".equals(status))
			{
				logger.lifecycle("[SmartTestPicker] Explicit PR base is out of date — smartTest skipped");
				smartTest.onlyIf(t -> false);
			}
			else if ("ERROR".equals(status))
			{
				throw new GradleException("Explicit PR selection failed; rerun selectTests for details");
			}
		}
		catch (IOException | RuntimeException failure)
		{
			if (failure instanceof GradleException gradle) throw gradle;
			throw new GradleException("Cannot read explicit PR selection result", failure);
		}
	}

	static void mirrorExecutionEnvironment(Test source, Test target)
	{
		target.setJvmArgs(source.getJvmArgs());
		target.setMinHeapSize(source.getMinHeapSize());
		target.setMaxHeapSize(source.getMaxHeapSize());
		target.setEnableAssertions(source.getEnableAssertions());
		target.setSystemProperties(source.getSystemProperties());
		target.setIncludes(source.getIncludes());
		target.setExcludes(source.getExcludes());
	}

	private static String pluginVersion() {
		try (InputStream stream = SmartTestPickerPlugin.class.getResourceAsStream(
				"/META-INF/smart-test-picker/plugin-version.txt")) {
			if (stream == null) {
				throw new IllegalStateException("Gradle-controlled STP plugin version resource is missing");
			}
			String version = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
			if (version.isEmpty()) {
				throw new IllegalStateException("Gradle-controlled STP plugin version is empty");
			}
			return version;
		} catch (IOException e) {
			throw new IllegalStateException("Cannot read Gradle-controlled STP plugin version", e);
		}
	}

	/**
	 * Applies the authoritative selected-tests.json decision to the smartTest task.
	 *
	 * <p>Logic:</p>
	 * <ul>
	 *   <li>FULL_SUITE or file missing → no filter, run everything</li>
	 *   <li>NONE → explicit no-test sentinel</li>
	 *   <li>SELECTED → conservative class widening of selectedTests only</li>
	 * </ul>
	 */
	static void applySmartTestFilters(Test smartTest, File selectedFile,
			org.gradle.api.logging.Logger logger)
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
		catch (IOException | RuntimeException e)
		{
			logger.warn("[SmartTestPicker] Failed to read selected-tests.json — running full suite");
			return;
		}

		if (output == null || !Set.of("SELECTED", "NONE", "FULL_SUITE").contains(output.getStatus())
				|| "FULL_SUITE".equals(output.getStatus()))
		{
			logger.lifecycle("[SmartTestPicker] Full suite required — no filter applied");
			return;
		}

		boolean isNone = "NONE".equals(output.getStatus());
		java.util.List<String> selectedTests = output.getSelectedTests() != null
				? output.getSelectedTests() : java.util.List.of();

		if (isNone)
		{
			logger.lifecycle("[SmartTestPicker] No tests to run (NONE)");
			smartTest.getFilter().includeTestsMatching("__no_tests_to_run__");
			smartTest.getFilter().setFailOnNoMatchingTests(false);
			return;
		}

		// Apply filters: selected tests + unmapped test classes
		Set<String> includePatterns = new LinkedHashSet<>();

		for (String test : selectedTests)
		{
			int hash = test.indexOf('#');
			String className = hash > 0 ? test.substring(0, hash) : test;
			includePatterns.add(className + ".*");
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

		logger.lifecycle("[SmartTestPicker] smartTest filter: {} mandatory identities (conservative class widening)",
				selectedTests.size());
	}

}
