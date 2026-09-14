// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.google.gson.JsonParser;

class MergeExecutableTargetFragmentsMojoTest {
	@Test void mergesDifferentExecutionTargetsAndRejectsDuplicateOwners(@TempDir Path root) throws Exception {
		var unit = identity("module@surefire@unit", "example.Tests#unit");
		var integration = identity("module@failsafe@it", "example.Tests#integration");
		Path first = fragment(root, "first", unit), second = fragment(root, "second", integration);
		Path firstEvidence = evidence(root, "first", unit), secondEvidence = evidence(root, "second", integration);
		Path output = root.resolve("joined.json"), outputEvidence = root.resolve("joined-evidence.json");
		var assignment = new ExecutableShardAssignment(1, new CoverageMapRevision("r"), new ShardId("s"),
				Set.of(unit, integration));
		MergeExecutableTargetFragmentsMojo.merge(List.of(first.toFile(), second.toFile()),
				List.of(firstEvidence.toFile(), secondEvidence.toFile()), assignment, "r", "s",
				output.toFile(), outputEvidence.toFile());
		assertEquals(Set.of(unit, integration), new ExecutableCoverageFragmentCodec()
				.deserialize(Files.readAllBytes(output)).tests().keySet());
		assertEquals("test", JsonParser.parseString(Files.readString(outputEvidence)).getAsJsonObject()
				.get("testTarget").getAsString());
		assertThrows(IllegalArgumentException.class, () -> MergeExecutableTargetFragmentsMojo.merge(
				List.of(first.toFile(), first.toFile()), List.of(firstEvidence.toFile(), firstEvidence.toFile()),
				assignment, "r", "s", output.toFile(), outputEvidence.toFile()));
	}

	@Test void ignoresOnlyEmptyNonExecutableTargets(@TempDir Path root) throws Exception {
		var unit = identity("module@surefire@unit", "example.Tests#unit");
		Path complete = fragment(root, "complete", unit), completeEvidence = evidence(root, "complete", unit);
		Path empty = emptyFragment(root, "empty"), emptyEvidence = emptyEvidence(root, "empty");
		Path output = root.resolve("joined.json"), outputEvidence = root.resolve("joined-evidence.json");
		var assignment = new ExecutableShardAssignment(1, new CoverageMapRevision("r"), new ShardId("s"), Set.of(unit));
		MergeExecutableTargetFragmentsMojo.merge(List.of(empty.toFile(), complete.toFile()),
				List.of(emptyEvidence.toFile(), completeEvidence.toFile()), assignment, "r", "s",
				output.toFile(), outputEvidence.toFile());
		assertEquals(Set.of(unit), new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(output)).tests().keySet());
		Files.writeString(emptyEvidence, "{\"version\":2,\"revision\":\"r\",\"shardId\":\"s\",\"EXECUTED\":[],\"NON_EXECUTED\":[\"" + unit + "\"]}");
		assertThrows(IllegalArgumentException.class, () -> MergeExecutableTargetFragmentsMojo.merge(
				List.of(empty.toFile()), List.of(emptyEvidence.toFile()), assignment, "r", "s",
				output.toFile(), outputEvidence.toFile()));
	}

	private static Path fragment(Path root, String name, ExecutableTestIdentity identity) throws Exception {
		Path file = root.resolve(name + ".json");
		var coverage = new TestCoverage(Set.of("example.Production"), Set.of(), TestOutcome.PASS,
				CollectionStatus.COLLECTED_WITH_COVERAGE);
		var fragment = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3,
				new CoverageMapRevision("r"), new ShardId("s"), Map.of(identity, coverage), List.of(), List.of(), true);
		Files.write(file, new ExecutableCoverageFragmentCodec().serialize(fragment)); return file;
	}

	private static Path evidence(Path root, String name, ExecutableTestIdentity identity) throws Exception {
		Path file = root.resolve(name + "-evidence.json");
		Files.writeString(file, "{\"version\":2,\"revision\":\"r\",\"shardId\":\"s\",\"EXECUTED\":[\""
				+ identity + "\"],\"NON_EXECUTED\":[]}"); return file;
	}

	private static Path emptyFragment(Path root, String name) throws Exception {
		Path file = root.resolve(name + ".json");
		var fragment = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3,
				new CoverageMapRevision("r"), new ShardId("s"), Map.of(), List.of(), List.of(), false);
		Files.write(file, new ExecutableCoverageFragmentCodec().serialize(fragment)); return file;
	}

	private static Path emptyEvidence(Path root, String name) throws Exception {
		Path file = root.resolve(name + "-evidence.json");
		Files.writeString(file, "{\"version\":2,\"revision\":\"r\",\"shardId\":\"s\",\"EXECUTED\":[],\"NON_EXECUTED\":[]}"); return file;
	}

	private static ExecutableTestIdentity identity(String target, String test) {
		return new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, target), TestIdentity.parse(test));
	}
}
