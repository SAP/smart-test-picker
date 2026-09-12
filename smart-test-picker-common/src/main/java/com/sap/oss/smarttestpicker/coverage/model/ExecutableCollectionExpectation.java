// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Set;
import java.util.TreeSet;

/** Orchestrator-owned expected executable occurrences and shards. */
public record ExecutableCollectionExpectation(ExecutableTestInventory testInventory, Set<ShardId> expectedShards,
		Set<ExecutableTestIdentity> intentionallyNonExecutableTests)
{
	public ExecutableCollectionExpectation(ExecutableTestInventory inventory, Set<ShardId> shards)
	{
		this(inventory, shards, Set.of());
	}

	public ExecutableCollectionExpectation
	{
		if (testInventory == null) throw new IllegalArgumentException("test inventory is required");
		expectedShards = Set.copyOf(new TreeSet<>(expectedShards));
		intentionallyNonExecutableTests = Set.copyOf(new TreeSet<>(intentionallyNonExecutableTests));
		if (!testInventory.expectedTests().containsAll(intentionallyNonExecutableTests))
			throw new IllegalArgumentException("intentionally non-executable tests must belong to expected inventory");
	}
}
