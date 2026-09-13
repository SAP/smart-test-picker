// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Collections;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

/** Strict Maven adapter validation and exact reactor-module partitioning. */
final class MavenExecutableAssignmentRouter
{
	Map<ExecutionTarget, Set<TestIdentity>> partition(ExecutableShardAssignment assignment, String revision,
			String shardId, Collection<ExecutionTarget> knownTargets)
	{
		if (!assignment.revision().equals(new CoverageMapRevision(revision)))
			throw new IllegalArgumentException("Maven executable assignment revision mismatch");
		if (!assignment.shardId().equals(new ShardId(shardId)))
			throw new IllegalArgumentException("Maven executable assignment shard mismatch");
		Map<ExecutionTarget, Set<TestIdentity>> result = new TreeMap<>();
		knownTargets.forEach(target -> {
			if (target.buildTool() != BuildTool.MAVEN) throw new IllegalArgumentException("Non-Maven known target: " + target);
			if (result.put(target, new TreeSet<>()) != null) throw new IllegalArgumentException("Ambiguous Maven target: " + target);
		});
		assignment.tests().forEach(identity -> {
			if (identity.target().buildTool() != BuildTool.MAVEN)
				throw new IllegalArgumentException("Maven mapping rejects non-Maven execution target: " + identity.target());
			Set<TestIdentity> tests = result.get(identity.target());
			if (tests == null) throw new IllegalArgumentException("Assigned Maven target is absent from reactor: " + identity.target());
			tests.add(identity.test());
		});
		Map<ExecutionTarget, Set<TestIdentity>> frozen = new TreeMap<>();
		result.forEach((target, tests) -> frozen.put(target,
				Collections.unmodifiableSortedSet(new TreeSet<>(tests))));
		return Map.copyOf(frozen);
	}
}
