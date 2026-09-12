// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;

/** Stable round-robin sharding over sorted executable identities. */
public final class ExecutableTestSharder
{
	private ExecutableTestSharder() {}
	public static ExecutableShardPlan shard(ExecutableTestInventory inventory, int shardCount)
	{
		if (shardCount <= 0) throw new IllegalArgumentException("shard count must be positive");
		if (inventory.expectedTests().isEmpty()) return new ExecutableShardPlan(inventory.revision(), java.util.Map.of());
		LinkedHashMap<ShardId, Set<ExecutableTestIdentity>> assignments = new LinkedHashMap<>();
		for (int i = 0; i < shardCount; i++) assignments.put(new ShardId(Integer.toString(i)), new TreeSet<>());
		int index = 0;
		for (ExecutableTestIdentity test : new TreeSet<>(inventory.expectedTests()))
			assignments.get(new ShardId(Integer.toString(index++ % shardCount))).add(test);
		ExecutableShardPlan result = new ExecutableShardPlan(inventory.revision(), assignments);
		result.validateExactPartition(inventory); return result;
	}
}
