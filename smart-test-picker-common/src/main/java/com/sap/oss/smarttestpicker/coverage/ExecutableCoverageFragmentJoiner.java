// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCollectionExpectation;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCollectionSummary;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCompleteness;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableFragmentAssignmentValidator;

/** Pure schema-v3 join over executable identities. */
public final class ExecutableCoverageFragmentJoiner
{
	public ExecutableCoverageMap join(ExecutableTestInventory inventory, ExecutableShardPlan plan,
			List<ExecutableCoverageFragment> fragments, Set<ExecutableTestIdentity> intentionallyNonExecutable,
			Instant generatedAt, GeneratorProvenance generator)
	{
		plan.validateExactPartition(inventory);
		if (!inventory.expectedTests().containsAll(intentionallyNonExecutable))
			throw new IllegalArgumentException("Positive non-execution must belong to expected inventory");
		List<String> errors = new ArrayList<>();
		Map<ShardId, List<ExecutableCoverageFragment>> byShard = new TreeMap<>();
		for (ExecutableCoverageFragment fragment : fragments)
		{
			if (!inventory.revision().equals(fragment.revision())) errors.add("fragment revision mismatch: " + fragment.shardId());
			byShard.computeIfAbsent(fragment.shardId(), ignored -> new ArrayList<>()).add(fragment);
		}
		TreeSet<ShardId> actualShards = new TreeSet<>(byShard.keySet());
		TreeSet<ShardId> missingShards = new TreeSet<>(plan.expectedShards()); missingShards.removeAll(actualShards);
		TreeSet<ShardId> unexpectedShards = new TreeSet<>(actualShards); unexpectedShards.removeAll(plan.expectedShards());
		if (!missingShards.isEmpty()) errors.add("missing shards: " + missingShards);
		if (!unexpectedShards.isEmpty()) errors.add("unexpected shards: " + unexpectedShards);
		byShard.forEach((shard, reports) -> { if (reports.size() > 1) errors.add("duplicate shard report: " + shard); });

		Map<ExecutableTestIdentity, TestCoverage> mapped = new TreeMap<>();
		Map<ExecutableTestIdentity, ExecutableUnmappedTest> unmapped = new TreeMap<>();
		Map<ExecutableTestIdentity, ShardId> owners = new HashMap<>();
		TreeSet<ExecutableTestIdentity> duplicates = new TreeSet<>();
		Map<String, SetupScope> scopes = new TreeMap<>();
		List<ShardId> completedReports = new ArrayList<>();
		for (ExecutableCoverageFragment fragment : fragments)
		{
			completedReports.add(fragment.shardId());
			if (plan.assignments().containsKey(fragment.shardId()))
			{
				Set<ExecutableTestIdentity> positiveForShard = new TreeSet<>(intentionallyNonExecutable);
				positiveForShard.retainAll(plan.assignmentFor(fragment.shardId()));
				try { ExecutableFragmentAssignmentValidator.validate(fragment,
						new ExecutableShardAssignment(1, plan.revision(), fragment.shardId(), plan.assignmentFor(fragment.shardId())),
						positiveForShard); }
				catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
			}
			for (var entry : fragment.tests().entrySet())
			{
				if (owners.putIfAbsent(entry.getKey(), fragment.shardId()) != null) duplicates.add(entry.getKey());
				else mapped.put(entry.getKey(), entry.getValue());
			}
			for (ExecutableUnmappedTest entry : fragment.unmapped())
			{
				if (owners.putIfAbsent(entry.test(), fragment.shardId()) != null) duplicates.add(entry.test());
				else unmapped.put(entry.test(), entry);
			}
			for (SetupScope scope : fragment.setupScopes())
				if (scopes.putIfAbsent(scope.id(), scope) != null) errors.add("duplicate setup-scope id: " + scope.id());
		}
		if (!duplicates.isEmpty()) errors.add("duplicate executable ownership: " + duplicates);
		TreeSet<ExecutableTestIdentity> reported = new TreeSet<>(owners.keySet());
		ExecutableCollectionSummary summary = new ExecutableCollectionSummary(reported, completedReports, duplicates);
		ExecutableCompleteness completeness = ExecutableCompleteness.from(
				new ExecutableCollectionExpectation(inventory, plan.expectedShards(), intentionallyNonExecutable), summary);
		if (!completeness.isComplete()) errors.add("incomplete executable collection: missingTests=" + completeness.missingTests()
				+ ", unexpectedTests=" + completeness.unexpectedTests() + ", missingShards=" + completeness.missingShards()
				+ ", duplicateShards=" + completeness.duplicateShards() + ", duplicateTests=" + completeness.duplicateTests());
		if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
		long classEdges = mapped.values().stream().mapToLong(value -> value.coveredClasses().size()).sum();
		long methodEdges = mapped.values().stream().mapToLong(value -> value.coveredMethods().size()).sum();
		return new ExecutableCoverageMap(CoverageMapContract.SCHEMA_V3, inventory.revision(), generatedAt, generator,
				mapped, List.copyOf(unmapped.values()), List.copyOf(scopes.values()), completeness,
				new MapStatistics(inventory.expectedTests().size(), mapped.size(), unmapped.size(), scopes.size(), classEdges, methodEdges),
				CoverageMapLifecycleState.CANDIDATE, null);
	}
}
