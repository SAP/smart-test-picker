// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.validation;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;

/** Exact semantic boundary between a completed v3 fragment and its assignment. */
public final class ExecutableFragmentAssignmentValidator
{
	private ExecutableFragmentAssignmentValidator() {}
	public static void validate(ExecutableCoverageFragment fragment, ExecutableShardAssignment assignment)
	{
		validate(fragment, assignment, Set.of(), false);
	}

	/** Explicit non-execution is orchestrator input and is never inferred from fragment absence. */
	public static void validate(ExecutableCoverageFragment fragment, ExecutableShardAssignment assignment,
			Set<ExecutableTestIdentity> intentionallyNonExecutable)
	{
		validate(fragment, assignment, intentionallyNonExecutable, true);
	}

	private static void validate(ExecutableCoverageFragment fragment, ExecutableShardAssignment assignment,
			Set<ExecutableTestIdentity> intentionallyNonExecutable, boolean requirePositiveNonExecution)
	{
		if (fragment.schemaVersion() != CoverageMapContract.SCHEMA_V3) throw new IllegalArgumentException("Fragment schema must be 3");
		if (!fragment.revision().equals(assignment.revision())) throw new IllegalArgumentException("Fragment revision does not match assignment revision");
		if (!fragment.shardId().equals(assignment.shardId())) throw new IllegalArgumentException("Fragment shard does not match assignment shard");
		if (!fragment.collectionCompleted()) throw new IllegalArgumentException("Fragment collection is not completed");
		if (!assignment.tests().containsAll(intentionallyNonExecutable))
			throw new IllegalArgumentException("Positive non-execution does not belong to this shard assignment");
		Set<ExecutableTestIdentity> reported = new TreeSet<>(fragment.tests().keySet());
		for (var unmapped : fragment.unmapped()) {
			if (requirePositiveNonExecution
					&& unmapped.reason() == com.sap.oss.smarttestpicker.coverage.model.UnmappedReason.SKIPPED) continue;
			if (!reported.add(unmapped.test())) throw new IllegalArgumentException("Executable identity reported as both mapped and unmapped: " + unmapped.test());
		}
		TreeSet<ExecutableTestIdentity> contradictory = new TreeSet<>(reported);
		contradictory.retainAll(intentionallyNonExecutable);
		if (!contradictory.isEmpty()) throw new IllegalArgumentException(
				"Executable identity is both reported and positively non-executable: " + contradictory);
		Set<ExecutableTestIdentity> accounted = new TreeSet<>(reported); accounted.addAll(intentionallyNonExecutable);
		TreeSet<ExecutableTestIdentity> missing = new TreeSet<>(assignment.tests()); missing.removeAll(accounted);
		TreeSet<ExecutableTestIdentity> unexpected = new TreeSet<>(reported); unexpected.removeAll(assignment.tests());
		if (!missing.isEmpty() || !unexpected.isEmpty()) throw new IllegalArgumentException(
				"Fragment does not exactly account for assignment; missing=" + missing + ", unexpected=" + unexpected);
		if (new HashSet<>(fragment.unmapped().stream().map(u -> u.test()).toList()).size() != fragment.unmapped().size())
			throw new IllegalArgumentException("Duplicate executable identity in unmapped reports");
	}
}
