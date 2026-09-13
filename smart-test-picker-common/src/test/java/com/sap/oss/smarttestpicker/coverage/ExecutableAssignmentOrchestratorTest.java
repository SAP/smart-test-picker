// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;

class ExecutableAssignmentOrchestratorTest
{
	private static final CoverageMapRevision REV = new CoverageMapRevision("revision");
	private static final ShardId SHARD = new ShardId("2");
	private static final TestIdentity TEST = TestIdentity.parse("example.SharedTest#works");

	@Test void preservesSelectionThroughSerializedMavenDispatch()
	{
		assertDispatch(BuildTool.MAVEN, new ExecutionTarget(BuildTool.MAVEN, "module-a"));
	}

	@Test void preservesSelectionThroughSerializedGradleDispatch()
	{
		assertDispatch(BuildTool.GRADLE, new ExecutionTarget(BuildTool.GRADLE, ":module-a:integrationTest"));
	}

	@Test void sameLogicalTestAtDifferentTasksRemainsDistinct()
	{
		var a = new ExecutableTestIdentity(new ExecutionTarget(BuildTool.GRADLE, ":test"), TEST);
		var b = new ExecutableTestIdentity(new ExecutionTarget(BuildTool.GRADLE, ":integrationTest"), TEST);
		var bytes = new AtomicReference<byte[]>();
		new ExecutableAssignmentOrchestrator().dispatch(new ExecutableSelectionResult(REV, Set.of(a, b)),
				REV, SHARD, BuildTool.GRADLE, bytes::set);
		assertEquals(Set.of(a, b), new ExecutableShardAssignmentCodec().deserialize(bytes.get()).tests());
	}

	@Test void zeroSelectionProducesValidEmptyAssignment()
	{
		var bytes = new AtomicReference<byte[]>();
		new ExecutableAssignmentOrchestrator().dispatch(new ExecutableSelectionResult(REV, Set.of()),
				REV, SHARD, BuildTool.GRADLE, bytes::set);
		assertTrue(new ExecutableShardAssignmentCodec().deserialize(bytes.get()).tests().isEmpty());
	}

	@Test void wrongRevisionAndAdapterFailBeforeTransport()
	{
		var identity = new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, "."), TEST);
		var selection = new ExecutableSelectionResult(REV, Set.of(identity));
		assertThrows(IllegalArgumentException.class, () -> new ExecutableAssignmentOrchestrator().dispatch(selection,
				new CoverageMapRevision("wrong"), SHARD, BuildTool.MAVEN, ignored -> fail()));
		assertThrows(IllegalArgumentException.class, () -> new ExecutableAssignmentOrchestrator().dispatch(selection,
				REV, SHARD, BuildTool.GRADLE, ignored -> fail()));
	}

	private static void assertDispatch(BuildTool tool, ExecutionTarget target)
	{
		var identity = new ExecutableTestIdentity(target, TEST);
		var bytes = new AtomicReference<byte[]>();
		ExecutableShardAssignment result = new ExecutableAssignmentOrchestrator().dispatch(
				new ExecutableSelectionResult(REV, Set.of(identity)), REV, SHARD, tool, bytes::set);
		assertEquals(result, new ExecutableShardAssignmentCodec().deserialize(bytes.get()));
		assertEquals(REV, result.revision()); assertEquals(SHARD, result.shardId());
		assertEquals(Set.of(identity), result.tests());
	}
}
