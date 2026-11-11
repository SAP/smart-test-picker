// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.SetupScopeType;
import com.sap.oss.smarttestpicker.coverage.model.TestContainer;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutableShardingAndJoinTest
{
	private static final CoverageMapRevision REV = new CoverageMapRevision("abc123");
	private static final TestIdentity T = TestIdentity.parse("org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage");
	private static final ExecutableTestIdentity A = executable("java-checks-common", T);
	private static final ExecutableTestIdentity B = executable("java-checks", T);
	private static final ShardId S0 = new ShardId("0"); private static final ShardId S1 = new ShardId("1");
	private static final GeneratorProvenance GENERATOR = new GeneratorProvenance("1", "1", "1", "17");

	@Test void planIsAnExactImmutableDeterministicExecutablePartition()
	{
		ExecutableTestInventory inventory = inventory();
		ExecutableShardPlan first = ExecutableTestSharder.shard(inventory, 3);
		ExecutableShardPlan second = ExecutableTestSharder.shard(
				new ExecutableTestInventory(REV, new java.util.HashSet<>(Set.of(B, A))), 3);
		assertEquals(first, second); assertEquals(3, first.expectedShards().size());
		assertEquals(Set.of(A, B), first.allAssignedTests()); assertDoesNotThrow(() -> first.validateExactPartition(inventory));
		assertThrows(UnsupportedOperationException.class, () -> first.assignments().put(S0, Set.of()));
		assertThrows(IllegalArgumentException.class, () -> new ExecutableShardPlan(REV, Map.of(S0, Set.of(A), S1, Set.of(A))));
		assertThrows(IllegalArgumentException.class,
				() -> new ExecutableShardPlan(REV, Map.of(S0, Set.of(A))).validateExactPartition(inventory));
		assertThrows(IllegalArgumentException.class, () -> new ExecutableShardPlan(REV, Map.of(S0, Set.of(A), S1,
				Set.of(executable("other", TestIdentity.parse("x.Y#z"))))).validateExactPartition(inventory));
		assertTrue(ExecutableTestSharder.shard(new ExecutableTestInventory(REV, Set.of()), 2).expectedShards().isEmpty());
	}

	@Test void assignmentCodecIsVersionedStrictDeterministicAndRoundTrips()
	{
		ExecutableShardAssignment assignment = new ExecutableShardAssignment(1, REV, S0, Set.of(B, A));
		ExecutableShardAssignmentCodec codec = new ExecutableShardAssignmentCodec(); byte[] bytes = codec.serialize(assignment);
		assertEquals(assignment, codec.deserialize(bytes)); assertArrayEquals(bytes, codec.serialize(codec.deserialize(bytes)));
		String json = new String(bytes, StandardCharsets.UTF_8);
		List<ExecutableTestIdentity> sorted = java.util.stream.Stream.of(A, B).sorted().toList();
		assertTrue(json.indexOf(sorted.get(0).toString()) < json.indexOf(sorted.get(1).toString()));
		assertThrows(IllegalArgumentException.class, () -> codec.deserialize((A + "\n" + B).getBytes(StandardCharsets.UTF_8)));
		assertThrows(IllegalArgumentException.class, () -> codec.deserialize("{\"revision\":\"x\",\"shardId\":\"0\",\"tests\":[]}".getBytes(StandardCharsets.UTF_8)));
		assertThrows(IllegalArgumentException.class, () -> codec.deserialize("{\"version\":2,\"revision\":\"x\",\"shardId\":\"0\",\"tests\":[]}".getBytes(StandardCharsets.UTF_8)));
		assertThrows(IllegalArgumentException.class, () -> codec.deserialize(("{\"version\":1,\"revision\":\"x\",\"shardId\":\"0\",\"tests\":[\"" + A + "\",\"" + A + "\"]}").getBytes(StandardCharsets.UTF_8)));
		assertThrows(IllegalArgumentException.class, () -> codec.deserialize("{\"version\":1,\"revision\":\"x\",\"shardId\":\"0\",\"tests\":[\"bad\"]}".getBytes(StandardCharsets.UTF_8)));
	}

	@Test void shardingKeepsEnclosingAndNestedTestClassesOnOneShard()
	{
		var first = new ExecutableTestIdentity(A.target(), new TestIdentity("example.StatefulTests", "first"));
		var second = new ExecutableTestIdentity(A.target(), new TestIdentity("example.StatefulTests", "second"));
		var nested = new ExecutableTestIdentity(A.target(), new TestIdentity("example.StatefulTests$Nested", "third"));
		var other = new ExecutableTestIdentity(A.target(), new TestIdentity("example.OtherTests", "test"));
		var plan = ExecutableTestSharder.shard(new ExecutableTestInventory(REV, Set.of(first, second, nested, other)), 3);
		var owners = plan.assignments().entrySet().stream().filter(entry -> entry.getValue().contains(first)
				|| entry.getValue().contains(second) || entry.getValue().contains(nested)).toList();
		assertEquals(1, owners.size());
		assertTrue(owners.get(0).getValue().containsAll(Set.of(first, second, nested)));
	}

	@Test void sonarJavaDualOwnerJoinsAcrossDifferentShardsWithoutCoverageUnion()
	{
		ExecutableCoverageMap map = join(plan(Map.of(S0, Set.of(A), S1, Set.of(B))),
				List.of(fragment(S0, Map.of(A, covered("ClassA", "ClassB"))), fragment(S1, Map.of(B, covered("ClassA", "ClassC")))), Set.of());
		assertEquals(2, map.tests().size()); assertEquals(A.test(), B.test());
		assertEquals(Set.of("ClassA", "ClassB"), map.tests().get(A).coveredClasses());
		assertEquals(Set.of("ClassA", "ClassC"), map.tests().get(B).coveredClasses());
		assertTrue(map.completeness().isComplete()); assertEquals(2, map.statistics().expectedTests());
		assertEquals(2, map.statistics().mappedTests()); assertEquals(CoverageMapLifecycleState.CANDIDATE, map.lifecycleState());
	}

	@Test void sameLogicalOwnersMayCoexistInOneShard()
	{
		ExecutableCoverageMap map = join(plan(Map.of(S0, Set.of(A, B))),
				List.of(fragment(S0, Map.of(A, covered("ClassA"), B, covered("ClassB")))), Set.of());
		assertEquals(Set.of(A, B), map.tests().keySet()); assertTrue(map.completeness().isComplete());
	}

	@Test void sameSetupScopeIdentityInDifferentTargetsRoundTripsAndJoinsWithoutCollision()
	{
		SetupScope scopeA = new SetupScope("junit-container:" + T.className(), SetupScopeType.CONTAINER,
				Set.of("ProductionA"), Set.of(new TestContainer(T.className())), A.target());
		SetupScope scopeB = new SetupScope("junit-container:" + T.className(), SetupScopeType.CONTAINER,
				Set.of("ProductionB"), Set.of(new TestContainer(T.className())), B.target());
		var codec = new com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec();
		var first = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3, REV, S0,
				Map.of(A, covered("BodyA")), List.of(), List.of(scopeA), true);
		var second = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3, REV, S1,
				Map.of(B, covered("BodyB")), List.of(), List.of(scopeB), true);
		first = codec.deserialize(codec.serialize(first));
		second = codec.deserialize(codec.serialize(second));
		assertEquals(A.target(), first.setupScopes().get(0).owner());
		assertEquals(B.target(), second.setupScopes().get(0).owner());

		ExecutableCoverageMap map = join(plan(Map.of(S0, Set.of(A), S1, Set.of(B))), List.of(first, second), Set.of());
		assertEquals(2, map.setupScopes().size());
		assertEquals(Set.of(A.target(), B.target()), map.setupScopes().stream().map(SetupScope::owner)
				.collect(java.util.stream.Collectors.toSet()));
	}

	@Test void wrongTargetDuplicateExecutableDuplicateShardAndMissingOwnerFailClosed()
	{
		ExecutableShardPlan split = plan(Map.of(S0, Set.of(A), S1, Set.of(B)));
		assertFailureContains(split, List.of(fragment(S0, Map.of(A, covered("A"))), fragment(S1, Map.of(A, covered("B")))), "duplicate", "missing");
		ExecutableTestInventory onlyA = new ExecutableTestInventory(REV, Set.of(A));
		ExecutableShardPlan onlyAPlan = new ExecutableShardPlan(REV, Map.of(S0, Set.of(A)));
		IllegalArgumentException wrongTarget = assertThrows(IllegalArgumentException.class,
				() -> new ExecutableCoverageFragmentJoiner().join(onlyA, onlyAPlan,
						List.of(fragment(S0, Map.of(B, covered("B")))), Set.of(),
						Instant.parse("2026-01-01T00:00:00Z"), GENERATOR));
		assertTrue(wrongTarget.getMessage().contains("unexpected")); assertTrue(wrongTarget.getMessage().contains("missing"));
		assertFailureContains(split, List.of(fragment(S0, Map.of(A, covered("A")))), "missing shards", B.toString());
		assertFailureContains(split, List.of(fragment(S0, Map.of(A, covered("A"))), fragment(S0, Map.of(A, covered("A")))), "duplicate shard", "duplicate");
	}

	@Test void positiveNonExecutionRequiresExplicitOrchestratorFact()
	{
		ExecutableShardPlan split = plan(Map.of(S0, Set.of(A), S1, Set.of(B)));
		List<ExecutableCoverageFragment> fragments = List.of(fragment(S0, Map.of(A, covered("A"))), fragment(S1, Map.of()));
		assertFailureContains(split, fragments, B.toString());
		ExecutableCoverageMap map = join(split, fragments, Set.of(B));
		assertTrue(map.completeness().isComplete()); assertEquals(2, map.statistics().expectedTests());
		assertEquals(1, map.statistics().mappedTests()); assertEquals(0, map.statistics().unmappedTests());
		IllegalArgumentException contradiction = assertThrows(IllegalArgumentException.class,
				() -> join(split, List.of(fragment(S0, Map.of(A, covered("A"))), fragment(S1, Map.of(B, covered("B")))), Set.of(B)));
		assertTrue(contradiction.getMessage().contains("both reported and positively non-executable"));
	}

	@Test void skippedCollectorRecordIsAccountedOnlyByPositiveExecutionEvidence()
	{
		ExecutableShardPlan split = plan(Map.of(S0, Set.of(A), S1, Set.of(B)));
		var skipped = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3, REV, S1, Map.of(),
				List.of(new ExecutableUnmappedTest(B, UnmappedReason.SKIPPED)), List.of(), true);
		ExecutableCoverageMap map = join(split,
				List.of(fragment(S0, Map.of(A, covered("A"))), skipped), Set.of(B));
		assertTrue(map.completeness().isComplete());
		assertTrue(map.unmapped().isEmpty());
	}

	private static ExecutableCoverageMap join(ExecutableShardPlan plan, List<ExecutableCoverageFragment> fragments,
			Set<ExecutableTestIdentity> nonExecution)
	{
		return new ExecutableCoverageFragmentJoiner().join(inventory(), plan, fragments, nonExecution,
				Instant.parse("2026-01-01T00:00:00Z"), GENERATOR);
	}
	private static void assertFailureContains(ExecutableShardPlan plan, List<ExecutableCoverageFragment> fragments, String... parts)
	{
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> join(plan, fragments, Set.of()));
		for (String part : parts) assertTrue(error.getMessage().toLowerCase().contains(part.toLowerCase()), error.getMessage());
	}
	private static ExecutableTestInventory inventory() { return new ExecutableTestInventory(REV, Set.of(A, B)); }
	private static ExecutableShardPlan plan(Map<ShardId, Set<ExecutableTestIdentity>> assignments)
	{
		ExecutableShardPlan plan = new ExecutableShardPlan(REV, assignments); plan.validateExactPartition(inventory()); return plan;
	}
	private static ExecutableCoverageFragment fragment(ShardId shard, Map<ExecutableTestIdentity, TestCoverage> tests)
	{
		return new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3, REV, shard, tests, List.of(), List.of(), true);
	}
	private static ExecutableTestIdentity executable(String module, TestIdentity test)
	{
		return new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, module), test);
	}
	private static TestCoverage covered(String... classes)
	{
		return new TestCoverage(Set.of(classes), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_WITH_COVERAGE);
	}
}
