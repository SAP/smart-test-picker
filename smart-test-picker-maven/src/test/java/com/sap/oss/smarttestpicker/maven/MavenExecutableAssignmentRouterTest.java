// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import com.sap.oss.smarttestpicker.coverage.model.*;

class MavenExecutableAssignmentRouterTest
{
	private static final ExecutionTarget ROOT = new ExecutionTarget(BuildTool.MAVEN, ".");
	private static final ExecutionTarget MODULE = new ExecutionTarget(BuildTool.MAVEN, "module-a");
	private static final TestIdentity A = TestIdentity.parse("example.A#one");
	private static final TestIdentity B = TestIdentity.parse("example.B#two");

	@Test void partitionsStrictSubsetAndPreservesModuleOwnership()
	{
		var assignment = assignment(Set.of(new ExecutableTestIdentity(MODULE, A)));
		var result = new MavenExecutableAssignmentRouter().partition(assignment, "r", "s", Set.of(ROOT, MODULE));
		assertEquals(Set.of(), result.get(ROOT)); assertEquals(Set.of(A), result.get(MODULE));
		assertFalse(result.get(MODULE).contains(B));
	}

	@Test void emptyAssignmentPartitionsToNoTests()
	{
		var result = new MavenExecutableAssignmentRouter().partition(assignment(Set.of()), "r", "s", Set.of(ROOT, MODULE));
		assertTrue(result.values().stream().allMatch(Set::isEmpty));
	}

	@Test void completeInventoryAllowsAnotherValidatedExecutionScope()
	{
		ExecutionTarget integration = new ExecutionTarget(BuildTool.MAVEN, "module-a@surefire@default-test@it-plugin");
		ExecutableTestIdentity other = new ExecutableTestIdentity(integration, B);
		var result = new MavenExecutableAssignmentRouter().partition(assignment(Set.of(other)), "r", "s",
				Set.of(MODULE), Set.of(other));
		assertEquals(Set.of(), result.get(MODULE));
		assertThrows(IllegalArgumentException.class, () -> new MavenExecutableAssignmentRouter().partition(
				assignment(Set.of(other)), "r", "s", Set.of(MODULE), Set.of()));
	}

	@Test void failsClosedForRevisionShardUnknownAndGradleTarget()
	{
		var valid = assignment(Set.of(new ExecutableTestIdentity(MODULE, A)));
		var router = new MavenExecutableAssignmentRouter();
		assertThrows(IllegalArgumentException.class, () -> router.partition(valid, "wrong", "s", Set.of(MODULE)));
		assertThrows(IllegalArgumentException.class, () -> router.partition(valid, "r", "wrong", Set.of(MODULE)));
		assertThrows(IllegalArgumentException.class, () -> router.partition(valid, "r", "s", Set.of(ROOT)));
		var gradle = assignment(Set.of(new ExecutableTestIdentity(new ExecutionTarget(BuildTool.GRADLE, ":test"), A)));
		assertThrows(IllegalArgumentException.class, () -> router.partition(gradle, "r", "s", Set.of(MODULE)));
	}

	private static ExecutableShardAssignment assignment(Set<ExecutableTestIdentity> tests)
	{
		return new ExecutableShardAssignment(1, new CoverageMapRevision("r"), new ShardId("s"), tests);
	}
}
