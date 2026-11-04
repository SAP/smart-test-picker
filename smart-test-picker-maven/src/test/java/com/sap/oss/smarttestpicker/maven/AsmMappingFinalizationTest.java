// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;

class AsmMappingFinalizationTest {
	private static final ExecutionTarget TARGET = ExecutionTarget.parse("maven:module");
	private static final ExecutableTestIdentity PASS = ExecutableTestIdentity.parse("maven:module::fixture.Tests#pass");
	private static final ExecutableTestIdentity FAILED = ExecutableTestIdentity.parse("maven:module::fixture.Tests#failed");
	private static final ExecutableTestIdentity SKIPPED = ExecutableTestIdentity.parse("maven:module::fixture.Tests#skipped");

	@Test void finalizesPositiveEvidenceWithoutChangingFragmentBytes(@TempDir Path temp) throws Exception {
		var f = fixture(temp); byte[] original = Files.readAllBytes(f.fragment());
		f.finalizer().execute(); assertArrayEquals(original, Files.readAllBytes(f.fragment()));
		var evidence = MavenMappingEvidence.read(f.evidence());
		assertEquals(Set.of(PASS, FAILED), evidence.executed()); assertEquals(Set.of(SKIPPED), evidence.nonExecuted());
		f.aggregator().execute();
		var result = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(f.result()));
		assertEquals(Set.of(PASS), result.tests().keySet());
		assertEquals(List.of(new ExecutableUnmappedTest(FAILED, UnmappedReason.FAILED),
				new ExecutableUnmappedTest(SKIPPED, UnmappedReason.SKIPPED)), result.unmapped());
		assertArrayEquals(original, Files.readAllBytes(f.fragment()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing-fragment", "incomplete", "missing-observation", "silent-empty", "unexpected", "wrong-target", "wrong-revision", "wrong-shard", "duplicate"})
	void finalizationRejectsUnsafeFragmentAndInvalidatesOldEvidence(String defect, @TempDir Path temp) throws Exception {
		var f = fixture(temp); Files.writeString(f.evidence(), "stale evidence");
		mutateFragment(f.fragment(), defect);
		byte[] input = Files.exists(f.fragment()) ? Files.readAllBytes(f.fragment()) : null;
		assertThrows(MojoExecutionException.class, () -> f.finalizer().execute());
		assertFalse(Files.exists(f.evidence()));
		if (input != null) assertArrayEquals(input, Files.readAllBytes(f.fragment()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing-fragment", "missing-evidence", "missing-observation", "silent-empty", "duplicate", "unexpected", "wrong-target", "incomplete",
			"wrong-revision", "wrong-shard", "overlap", "duplicate-evidence", "wrong-evidence-target", "wrong-version",
			"missing-identity", "missing-nonexecution", "skipped-as-executed"})
	void aggregationFailsClosedAndRemovesStaleFinalPair(String defect, @TempDir Path temp) throws Exception {
		var f = fixture(temp); f.finalizer().execute();
		if (Set.of("missing-fragment", "missing-observation", "silent-empty", "duplicate", "unexpected", "wrong-target", "incomplete", "wrong-revision", "wrong-shard").contains(defect))
			mutateFragment(f.fragment(), defect);
		else if (defect.equals("missing-evidence")) Files.delete(f.evidence());
		else {
			var json = JsonParser.parseString(Files.readString(f.evidence())).getAsJsonObject();
			switch (defect) {
				case "overlap" -> json.getAsJsonArray("NON_EXECUTED").add(PASS.toString());
				case "duplicate-evidence" -> json.getAsJsonArray("EXECUTED").add(PASS.toString());
				case "wrong-evidence-target" -> json.addProperty("executionTarget", "maven:other");
				case "wrong-version" -> json.addProperty("version", 1);
				case "missing-identity" -> json.getAsJsonArray("EXECUTED").remove(0);
				case "missing-nonexecution" -> json.getAsJsonArray("NON_EXECUTED").remove(0);
				case "skipped-as-executed" -> { json.getAsJsonArray("NON_EXECUTED").remove(0); json.getAsJsonArray("EXECUTED").add(SKIPPED.toString()); }
			}
			Files.writeString(f.evidence(), json.toString());
		}
		Files.writeString(f.result(), "stale fragment"); Files.writeString(f.resultEvidence(), "stale evidence");
		assertThrows(MojoExecutionException.class, () -> f.aggregator().execute());
		assertFalse(Files.exists(f.result())); assertFalse(Files.exists(f.resultEvidence()));
	}

	private static void mutateFragment(Path file, String defect) throws Exception {
		if (defect.equals("missing-fragment")) { Files.delete(file); return; }
		var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		switch (defect) {
			case "incomplete" -> json.getAsJsonObject("collection").addProperty("completed", false);
			case "missing-observation" -> json.getAsJsonObject("tests").remove(PASS.toString());
			case "silent-empty" -> { json.add("tests", new com.google.gson.JsonObject()); json.add("unmapped", new com.google.gson.JsonArray()); }
			case "duplicate" -> json.getAsJsonArray("unmapped").add(json.getAsJsonArray("unmapped").get(0).deepCopy());
			case "unexpected" -> json.getAsJsonObject("tests").add("maven:module::fixture.Other#unexpected", json.getAsJsonObject("tests").get(PASS.toString()).deepCopy());
			case "wrong-revision" -> json.addProperty("revision", "other");
			case "wrong-shard" -> json.addProperty("shardId", "other");
		}
		Files.writeString(file, defect.equals("wrong-target") ? json.toString().replace("maven:module::", "maven:other::") : json.toString());
	}

	private static Fixture fixture(Path temp) throws Exception {
		Path directory = Files.createDirectories(temp.resolve("module/target/stp"));
		Path fragment = directory.resolve("coverage-fragment-v3.json"), evidence = directory.resolve("execution-evidence-v2.json");
		var value = new ExecutableCoverageFragment(3, new CoverageMapRevision("rev"), new ShardId("shard"),
				Map.of(PASS, new TestCoverage(Set.of(), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_EMPTY)),
				List.of(new ExecutableUnmappedTest(FAILED, UnmappedReason.FAILED), new ExecutableUnmappedTest(SKIPPED, UnmappedReason.SKIPPED)), List.of(), true);
		Files.write(fragment, new ExecutableCoverageFragmentCodec().serialize(value));
		Path diagnostic = directory.resolve("asm-agent-diagnostic.json");
		var observed = new com.google.gson.JsonObject(); observed.addProperty("schemaVersion", "agent-shell-1");
		var configuration = new com.google.gson.JsonObject();
		configuration.addProperty("revision", "rev"); configuration.addProperty("shardId", "shard");
		configuration.addProperty("fragmentOutput", fragment.toAbsolutePath().toString());
		configuration.add("includes", com.google.gson.JsonParser.parseString("[\"fixture.\"]"));
		configuration.add("excludes", new com.google.gson.JsonArray()); observed.add("configuration", configuration);
		var metrics = new com.google.gson.JsonObject(); metrics.addProperty("classesTransformed", 1); observed.add("metrics", metrics);
		Files.writeString(diagnostic, observed.toString());
		Path assignment = temp.resolve("assignment.json");
		Files.write(assignment, new ExecutableShardAssignmentCodec().serialize(new ExecutableShardAssignment(1,
				value.revision(), value.shardId(), Set.of(PASS, FAILED, SKIPPED))));
		var finalizer = new GenerateCoverageFragmentMojo();
		set(finalizer, "schemaVersion", 3); set(finalizer, "revision", "rev"); set(finalizer, "shardId", "shard");
		set(finalizer, "coverageCollector", "ASM"); set(finalizer, "executionTarget", TARGET.toString());
		set(finalizer, "assignmentFile", assignment.toFile());
		set(finalizer, "moduleDiagnosticOutput", diagnostic.toFile());
		set(finalizer, "moduleFragmentOutput", fragment.toFile()); set(finalizer, "moduleEvidenceOutput", evidence.toFile());
		var root = project(temp, true); var module = project(temp.resolve("module"), false);
		var aggregator = new AggregateReactorCoverageFragmentMojo();
		set(aggregator, "project", root); set(aggregator, "reactorProjects", List.of(root, module));
		set(aggregator, "schemaVersion", 3); set(aggregator, "revision", "rev"); set(aggregator, "shardId", "shard");
		set(aggregator, "shardAssignments", assignment.toFile());
		Path result = temp.resolve("result.json"), resultEvidence = temp.resolve("evidence.json");
		set(aggregator, "fragmentOutput", result.toFile()); set(aggregator, "evidenceOutput", resultEvidence.toFile());
		return new Fixture(finalizer, aggregator, fragment, evidence, result, resultEvidence);
	}
	private static MavenProject project(Path dir, boolean root) {
		var project = new MavenProject(); project.setFile(dir.resolve("pom.xml").toFile()); project.setExecutionRoot(root);
		var build = new Build(); build.setDirectory(dir.resolve("target").toString()); project.setBuild(build); return project;
	}
	private static void set(Object target, String field, Object value) throws Exception {
		var f = target.getClass().getDeclaredField(field); f.setAccessible(true); f.set(target, value);
	}
	private record Fixture(GenerateCoverageFragmentMojo finalizer, AggregateReactorCoverageFragmentMojo aggregator,
			Path fragment, Path evidence, Path result, Path resultEvidence) { }
}
