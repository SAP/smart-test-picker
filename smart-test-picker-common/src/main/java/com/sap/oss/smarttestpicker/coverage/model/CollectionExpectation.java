// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Set;
import java.util.TreeSet;

/** Independent expected test inventory and shard identities for one collection. */
public record CollectionExpectation(TestInventory testInventory, Set<ShardId> expectedShards)
{
	public CollectionExpectation
	{
		if (testInventory == null) throw new IllegalArgumentException("test inventory is required");
		expectedShards = Set.copyOf(new TreeSet<>(expectedShards));
	}
}
