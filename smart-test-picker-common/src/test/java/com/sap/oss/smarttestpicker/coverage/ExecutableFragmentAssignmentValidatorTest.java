// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableFragmentAssignmentValidator;

import static org.junit.jupiter.api.Assertions.*;

class ExecutableFragmentAssignmentValidatorTest
{
	private static final CoverageMapRevision REVISION = new CoverageMapRevision("revision");
	private static final ShardId SHARD = new ShardId("shard");
	private static final ExecutableTestIdentity A = identity("A");
	private static final ExecutableTestIdentity B = identity("B");
	private static final ExecutableTestIdentity C = identity("C");
	private static final ExecutableTestIdentity OUTSIDE = identity("Outside");

	@Test void acceptsMappedUnmappedPositiveNonExecutionAndMixedAccounting()
	{
		assertDoesNotThrow(() -> validate(Set.of(A), fragment(Map.of(A, coverage()), List.of()), Set.of()));
		assertDoesNotThrow(() -> validate(Set.of(A), fragment(Map.of(), List.of(unmapped(A))), Set.of()));
		assertDoesNotThrow(() -> validate(Set.of(A), fragment(Map.of(), List.of()), Set.of(A)));
		assertDoesNotThrow(() -> validate(Set.of(A, B, C),
				fragment(Map.of(A, coverage()), List.of(unmapped(B))), Set.of(C)));
	}

	@Test void rejectsMissingOverlapAndUnexpectedPositiveNonExecution()
	{
		assertContains(assertThrows(IllegalArgumentException.class,
				() -> validate(Set.of(A, B), fragment(Map.of(A, coverage()), List.of()), Set.of())), "missing", B.toString());
		assertContains(assertThrows(IllegalArgumentException.class,
				() -> validate(Set.of(A), fragment(Map.of(A, coverage()), List.of()), Set.of(A))), "both reported", A.toString());
		assertContains(assertThrows(IllegalArgumentException.class,
				() -> validate(Set.of(A), fragment(Map.of(), List.of(unmapped(A))), Set.of(A))), "both reported", A.toString());
		assertContains(assertThrows(IllegalArgumentException.class,
				() -> validate(Set.of(A), fragment(Map.of(A, coverage()), List.of()), Set.of(OUTSIDE))), "does not belong");
	}

	private static void validate(Set<ExecutableTestIdentity> assigned, ExecutableCoverageFragment fragment,
			Set<ExecutableTestIdentity> nonExecuted)
	{
		ExecutableFragmentAssignmentValidator.validate(fragment,
				new ExecutableShardAssignment(1, REVISION, SHARD, assigned), nonExecuted);
	}

	private static ExecutableCoverageFragment fragment(Map<ExecutableTestIdentity, TestCoverage> mapped,
			List<ExecutableUnmappedTest> unmapped)
	{
		return new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3, REVISION, SHARD,
				mapped, unmapped, List.of(), true);
	}

	private static TestCoverage coverage()
	{
		return new TestCoverage(Set.of(), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_EMPTY);
	}

	private static ExecutableUnmappedTest unmapped(ExecutableTestIdentity identity)
	{
		return new ExecutableUnmappedTest(identity, UnmappedReason.COLLECTION_FAILED);
	}

	private static ExecutableTestIdentity identity(String method)
	{
		return new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, "module"),
				new TestIdentity("example.Tests", method));
	}

	private static void assertContains(Throwable failure, String... values)
	{
		for (String value : values) assertTrue(failure.getMessage().contains(value), failure.getMessage());
	}
}
