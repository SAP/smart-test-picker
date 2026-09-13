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
	private File execDir, reportsDir, sourceDir;
	private List<File> classesDirs = List.of();
	private String revision, shardId;
	private ExecutionTarget target;
	private Set<TestIdentity> assigned = Set.of();
	@Internal public File getExecDir() { return execDir; } public void setExecDir(File value) { execDir = value; }
	@Internal public File getReportsDir() { return reportsDir; } public void setReportsDir(File value) { reportsDir = value; }
	@Internal public File getSourceDir() { return sourceDir; } public void setSourceDir(File value) { sourceDir = value; }
	@Internal public List<File> getClassesDirs() { return classesDirs; } public void setClassesDirs(List<File> value) { classesDirs = value; }
	@Internal public String getRevision() { return revision; } public void setRevision(String value) { revision = value; }
	@Internal public String getShardId() { return shardId; } public void setShardId(String value) { shardId = value; }
	@Internal public ExecutionTarget getTarget() { return target; } public void setTarget(ExecutionTarget value) { target = value; }
	@Internal public Set<TestIdentity> getAssigned() { return assigned; } public void setAssigned(Set<TestIdentity> value) { assigned = value; }
	@OutputFile public abstract RegularFileProperty getOutputFile();

	@TaskAction public void generate() {
		try {
			new ExecToXmlEngine().generateReports(execDir, classesDirs, sourceDir, reportsDir,
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
				if (!status.isFile() || !exec.isFile()) { complete = false; unmapped.add(new ExecutableUnmappedTest(executable, UnmappedReason.COLLECTION_FAILED)); continue; }
				String state = Files.readString(status.toPath(), StandardCharsets.UTF_8).trim();
				TestOutcome outcome = TestOutcome.valueOf(read(identityFile).get("outcome"));
				if ("EMPTY".equals(state)) mapped.put(executable, new TestCoverage(Set.of(), Set.of(), outcome, CollectionStatus.COLLECTED_EMPTY));
				else if ("COVERED".equals(state)) {
					var coverage = mapper.readSchemaV2Coverage(new File(reportsDir, base + ".xml"));
					mapped.put(executable, new TestCoverage(coverage.coveredClasses(), coverage.coveredMethods(), outcome,
							coverage.coveredClasses().isEmpty() ? CollectionStatus.COLLECTED_EMPTY : CollectionStatus.COLLECTED_WITH_COVERAGE));
				} else { complete = false; unmapped.add(new ExecutableUnmappedTest(executable, UnmappedReason.COLLECTION_FAILED)); }
			}
			ExecutableCoverageFragment fragment = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3,
					new CoverageMapRevision(revision), new ShardId(shardId), mapped, unmapped, List.of(), complete);
			File output = getOutputFile().get().getAsFile(); output.getParentFile().mkdirs();
			Files.write(output.toPath(), new ExecutableCoverageFragmentCodec().serialize(fragment));
			if (!complete) throw new GradleException("JaCoCo Gradle executable collection is incomplete");
		} catch (Exception failure) {
			throw failure instanceof GradleException gradle ? gradle : new GradleException("Cannot generate Gradle JaCoCo executable fragment", failure);
		}
	}

	private Map<TestIdentity, File> identities(String suffix) throws Exception {
		Map<TestIdentity, File> result = new TreeMap<>(); File[] files = execDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(suffix));
		if (files == null) return result;
		for (File file : files) { Map<String,String> values = read(file); TestIdentity id = new TestIdentity(values.get("className"), values.get("methodName"), values.getOrDefault("methodParameterTypes", ""));
			if (result.putIfAbsent(id, file) != null) throw new GradleException("Duplicate JaCoCo identity artifact: " + id); }
		return result;
	}
	private static Map<String,String> read(File file) throws Exception { Map<String,String> values = new HashMap<>(); for (String line : Files.readAllLines(file.toPath())) { int at = line.indexOf('='); if (at > 0) values.put(line.substring(0, at), line.substring(at + 1)); } return values; }
	private static Set<TestIdentity> difference(Set<TestIdentity> left, Set<TestIdentity> right) { Set<TestIdentity> copy = new java.util.TreeSet<>(left); copy.removeAll(right); return copy; }
}
