// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.SetupScopeType;
import com.sap.oss.smarttestpicker.coverage.model.TestContainer;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.engine.ExecToXmlEngine;
import com.sap.oss.smarttestpicker.mapper.CoverageMapperJaxb;

/** Converts one Test task's legacy JaCoCo facts into one target-qualified v3 fragment. */
@DisableCachingByDefault(because = "Consumes runtime session files from one Test task")
public abstract class GenerateGradleJacocoExecutableFragmentTask extends DefaultTask {
	public GenerateGradleJacocoExecutableFragmentTask() { GradleMappingExecution.fresh(this); }
	private File execDir, reportsDir, sourceDir;
	private final org.gradle.api.file.ConfigurableFileCollection classesDirs = getProject().files();
	private String revision, shardId;
	private String targetIdentity;
	private List<List<String>> assignedFields = new ArrayList<>();
	@org.gradle.api.tasks.InputDirectory public File getExecDir() { return execDir; } public void setExecDir(File value) { execDir = value; }
	// Scratch reports may already contain current-run evidence; Gradle must not clean them before validation.
	@org.gradle.api.tasks.LocalState public File getReportsDir() { return reportsDir; } public void setReportsDir(File value) { reportsDir = value; }
	@org.gradle.api.tasks.InputFiles public File getSourceDir() { return sourceDir; } public void setSourceDir(File value) { sourceDir = value; }
	@org.gradle.api.tasks.Classpath public org.gradle.api.file.ConfigurableFileCollection getClassesDirs() { return classesDirs; }
	public void setClassesDirs(List<File> value) { getClassesDirs().setFrom(value); }
	@org.gradle.api.tasks.Input public String getRevision() { return revision; } public void setRevision(String value) { revision = value; }
	@org.gradle.api.tasks.Input public String getShardId() { return shardId; } public void setShardId(String value) { shardId = value; }
	@Internal public ExecutionTarget getTarget() { return ExecutionTarget.parse(targetIdentity); }
	public void setTarget(ExecutionTarget value) { targetIdentity = value.toString(); }
	@Internal public Set<TestIdentity> getAssigned() {
		Set<TestIdentity> result = new java.util.TreeSet<>();
		assignedFields.forEach(fields -> result.add(new TestIdentity(fields.get(0), fields.get(1), fields.get(2))));
		return result;
	}
	public void setAssigned(Set<TestIdentity> value) {
		assignedFields = new ArrayList<>();
		value.stream().sorted().forEach(test -> assignedFields.add(new ArrayList<>(List.of(
				test.className(), test.methodName(), test.methodParameterTypes()))));
	}
	@org.gradle.api.tasks.Input public String getTargetIdentity() { return targetIdentity; }
	@org.gradle.api.tasks.Input public List<List<String>> getAssignedFields() { return assignedFields; }
	@OutputFile public abstract RegularFileProperty getOutputFile();

	@TaskAction public void generate() {
		try {
			Set<TestIdentity> assigned = getAssigned();
			ExecutionTarget target = getTarget();
			File output = getOutputFile().get().getAsFile();
			Files.deleteIfExists(output.toPath());
			new ExecToXmlEngine().generateReports(execDir, getClassesDirs().getFiles().stream().toList(), sourceDir, reportsDir,
					new GradleEngineLogger(getLogger()), 1, (dir, name) -> name.startsWith("session_") && name.endsWith(".exec"));
			Map<TestIdentity, File> executed = identities(".identity");
			Map<TestIdentity, File> skipped = identities(".non-executed"); skipped.keySet().removeAll(executed.keySet());
			Set<TestIdentity> observed = new java.util.TreeSet<>(executed.keySet()); observed.addAll(skipped.keySet());
			if (!assigned.containsAll(observed)) throw new GradleException("JaCoCo observed tests outside Gradle task assignment: "
					+ difference(observed, assigned));
			Map<ExecutableTestIdentity, TestCoverage> mapped = new TreeMap<>();
			List<ExecutableUnmappedTest> unmapped = new ArrayList<>();
			CoverageMapperJaxb mapper = new CoverageMapperJaxb(reportsDir);
			boolean complete = true;
			for (TestIdentity logical : assigned) {
				ExecutableTestIdentity executable = new ExecutableTestIdentity(target, logical);
				File identityFile = executed.get(logical);
				if (identityFile == null) {
					if (skipped.containsKey(logical)) unmapped.add(new ExecutableUnmappedTest(executable, UnmappedReason.SKIPPED));
					else { complete = false; unmapped.add(new ExecutableUnmappedTest(executable, UnmappedReason.COLLECTION_FAILED)); }
					continue;
				}
				String base = identityFile.getName().substring(0, identityFile.getName().length() - ".identity".length());
				File status = new File(reportsDir, base + ".status");
				File exec = new File(execDir, base + ".exec");
				if (!status.isFile() || !exec.isFile()) {
					complete = false;
					unmapped.add(new ExecutableUnmappedTest(executable, UnmappedReason.COLLECTION_FAILED));
					continue;
				}
				String state = Files.readString(status.toPath(), StandardCharsets.UTF_8).trim();
				TestOutcome outcome = TestOutcome.valueOf(read(identityFile).get("outcome"));
				if (outcome == TestOutcome.FAIL) {
					unmapped.add(new ExecutableUnmappedTest(executable, UnmappedReason.FAILED));
					continue;
				}
				if ("EMPTY".equals(state)) mapped.put(executable, new TestCoverage(Set.of(), Set.of(), outcome, CollectionStatus.COLLECTED_EMPTY));
				else if ("COVERED".equals(state)) {
					var coverage = mapper.readSchemaV2Coverage(new File(reportsDir, base + ".xml"));
					mapped.put(executable, new TestCoverage(coverage.coveredClasses(), coverage.coveredMethods(), outcome,
							coverage.coveredClasses().isEmpty() ? CollectionStatus.COLLECTED_EMPTY : CollectionStatus.COLLECTED_WITH_COVERAGE));
				} else { complete = false; unmapped.add(new ExecutableUnmappedTest(executable, UnmappedReason.COLLECTION_FAILED)); }
			}
			ExecutableCoverageFragment fragment = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3,
					new CoverageMapRevision(revision), new ShardId(shardId), mapped, unmapped, setupScopes(mapper, assigned, target), complete);
			output.getParentFile().mkdirs();
			Files.write(output.toPath(), new ExecutableCoverageFragmentCodec().serialize(fragment));
			if (!complete) throw new GradleException("JaCoCo Gradle executable collection is incomplete");
		} catch (Exception failure) {
			throw failure instanceof GradleException gradle ? gradle : new GradleException("Cannot generate Gradle JaCoCo executable fragment", failure);
		}
	}

	private List<SetupScope> setupScopes(CoverageMapperJaxb mapper, Set<TestIdentity> assigned, ExecutionTarget target) throws Exception {
		// Include orphan exec files: missing ownership must not silently erase positive coverage.
		Set<String> bases = new java.util.TreeSet<>();
		File[] files = execDir.listFiles((dir, name) -> name.startsWith("session_setup_")
				&& (name.endsWith(".exec") || name.endsWith(".setup")));
		if (files != null) for (File file : files) bases.add(file.getName().substring(0, file.getName().lastIndexOf('.')));
		File[] reports = reportsDir.listFiles((dir, name) -> name.startsWith("session_setup_")
				&& (name.endsWith(".xml") || name.endsWith(".status")));
		if (reports != null) for (File report : reports) bases.add(report.getName().substring(0, report.getName().lastIndexOf('.')));
		List<SetupScope> scopes = new ArrayList<>();
		Set<String> containers = new java.util.HashSet<>();
		for (String base : bases) {
			try {
				File metadata = new File(execDir, base + ".setup");
				if (!metadata.isFile() || !new File(execDir, base + ".exec").isFile())
					throw new IllegalArgumentException("missing setup ownership or execution data");
				Map<String, String> values = new HashMap<>();
				for (String line : Files.readAllLines(metadata.toPath(), StandardCharsets.UTF_8)) {
					int at = line.indexOf('=');
					if (at <= 0 || values.put(line.substring(0, at), line.substring(at + 1)) != null)
						throw new IllegalArgumentException("malformed or duplicate setup field");
				}
				if (!"1".equals(values.get("format"))) throw new IllegalArgumentException("unsupported setup format");
				String container = values.get("container");
				if (container == null || container.isBlank() || assigned.stream().noneMatch(test ->
						test.className().equals(container) || test.className().startsWith(container + "$")))
					throw new IllegalArgumentException("setup container is not bound to this target assignment: " + container);
				if (!containers.add(container)) throw new IllegalArgumentException("duplicate setup container: " + container);
				String state = Files.readString(new File(reportsDir, base + ".status").toPath(), StandardCharsets.UTF_8).trim();
				Set<String> classes;
				if (state.equals("COVERED")) classes = mapper.readSchemaV2Coverage(new File(reportsDir, base + ".xml")).coveredClasses();
				else if (state.equals("EMPTY")) classes = Set.of();
				else throw new IllegalArgumentException("unsafe setup report status " + state);
				if (!classes.isEmpty()) scopes.add(new SetupScope("junit-container:" + container,
						SetupScopeType.FRAMEWORK_SETUP, classes, Set.of(new TestContainer(container)), target));
			} catch (Exception failure) {
				throw new GradleException("Unsafe JaCoCo setup coverage for " + target + " (" + base + "): " + failure.getMessage(), failure);
			}
		}
		return scopes;
	}

	private Map<TestIdentity, File> identities(String suffix) throws Exception {
		Map<TestIdentity, File> result = new TreeMap<>(); File[] files = execDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(suffix));
		if (files == null) return result;
		for (File file : files) {
			Map<String,String> values = read(file);
			TestIdentity id = new TestIdentity(values.get("className"), values.get("methodName"), values.getOrDefault("methodParameterTypes", ""));
			if (result.putIfAbsent(id, file) != null) {
				throw new GradleException("Duplicate JaCoCo identity artifact: " + id);
			}
		}
		return result;
	}
	private static Map<String,String> read(File file) throws Exception { Map<String,String> values = new HashMap<>(); for (String line : Files.readAllLines(file.toPath())) { int at = line.indexOf('='); if (at > 0) values.put(line.substring(0, at), line.substring(at + 1)); } return values; }
	private static Set<TestIdentity> difference(Set<TestIdentity> left, Set<TestIdentity> right) { Set<TestIdentity> copy = new java.util.TreeSet<>(left); copy.removeAll(right); return copy; }
}
