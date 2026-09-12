// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable, revision-bound partition of executable test occurrences. */
public record ExecutableShardPlan(CoverageMapRevision revision,
		Map<ShardId, Set<ExecutableTestIdentity>> assignments)
{
	public ExecutableShardPlan
	{
		if (revision == null) throw new IllegalArgumentException("revision is required");
		Objects.requireNonNull(assignments, "assignments");
		TreeMap<ShardId, Set<ExecutableTestIdentity>> ordered = new TreeMap<>();
		TreeSet<ExecutableTestIdentity> assigned = new TreeSet<>();
		for (Map.Entry<ShardId, Set<ExecutableTestIdentity>> entry : assignments.entrySet())
		{
			if (entry.getKey() == null) throw new IllegalArgumentException("shard id is required");
			Objects.requireNonNull(entry.getValue(), "shard assignment");
			TreeSet<ExecutableTestIdentity> tests = new TreeSet<>();
			for (ExecutableTestIdentity test : entry.getValue())
			{
				if (test == null) throw new IllegalArgumentException("assigned executable identity is required");
				if (!assigned.add(test)) throw new IllegalArgumentException(
						"Executable identity assigned to more than one shard: " + test);
				tests.add(test);
			}
			ordered.put(entry.getKey(), Collections.unmodifiableSet(tests));
		}
		assignments = Collections.unmodifiableMap(ordered);
	}

	public Set<ShardId> expectedShards() { return assignments.keySet(); }
	public Set<ExecutableTestIdentity> allAssignedTests()
	{
		TreeSet<ExecutableTestIdentity> result = new TreeSet<>();
		assignments.values().forEach(result::addAll); return Collections.unmodifiableSet(result);
	}
	public Set<ExecutableTestIdentity> assignmentFor(ShardId shardId)
	{
		Set<ExecutableTestIdentity> result = assignments.get(shardId);
		if (result == null) throw new IllegalArgumentException("Unexpected shard: " + shardId);
		return result;
	}

	public void validateExactPartition(ExecutableTestInventory inventory)
	{
		Objects.requireNonNull(inventory, "inventory");
		if (!revision.equals(inventory.revision())) throw new IllegalArgumentException("Shard-plan revision does not match inventory revision");
		if (!inventory.expectedTests().isEmpty() && assignments.isEmpty())
			throw new IllegalArgumentException("At least one shard is required for a non-empty inventory");
		TreeSet<ExecutableTestIdentity> missing = new TreeSet<>(inventory.expectedTests()); missing.removeAll(allAssignedTests());
		TreeSet<ExecutableTestIdentity> unexpected = new TreeSet<>(allAssignedTests()); unexpected.removeAll(inventory.expectedTests());
		if (!missing.isEmpty() || !unexpected.isEmpty()) throw new IllegalArgumentException(
				"Shard plan is not an exact inventory partition; missing=" + missing + ", unexpected=" + unexpected);
	}
}
