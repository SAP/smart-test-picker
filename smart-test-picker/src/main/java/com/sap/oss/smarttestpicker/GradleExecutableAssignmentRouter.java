// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

/** Strict build-tool adapter validation and exact Test-task partitioning. */
final class GradleExecutableAssignmentRouter {
	Map<ExecutionTarget, Set<TestIdentity>> partition(ExecutableShardAssignment assignment,
			String revision, String shardId, Collection<ExecutionTarget> knownTargets) {
		if (!assignment.revision().equals(new CoverageMapRevision(revision)))
			throw new IllegalArgumentException("Gradle executable assignment revision mismatch");
		if (!assignment.shardId().equals(new ShardId(shardId)))
			throw new IllegalArgumentException("Gradle executable assignment shard mismatch");
		Set<ExecutionTarget> known = new TreeSet<>(knownTargets);
		Map<ExecutionTarget, Set<TestIdentity>> result = new TreeMap<>();
		known.forEach(target -> result.put(target, new TreeSet<>()));
		assignment.tests().forEach(identity -> {
			if (identity.target().buildTool() != BuildTool.GRADLE)
				throw new IllegalArgumentException("Non-Gradle target in Gradle executable assignment: " + identity.target());
			Set<TestIdentity> tests = result.get(identity.target());
			if (tests == null) throw new IllegalArgumentException(
					"Unknown Gradle Test task target: " + identity.target());
			tests.add(identity.test());
		});
		Map<ExecutionTarget, Set<TestIdentity>> frozen = new TreeMap<>();
		result.forEach((target, tests) -> frozen.put(target, Set.copyOf(tests)));
		return Map.copyOf(frozen);
	}

	static String gradleFilter(TestIdentity test) {
		// Gradle TestFilter selects declared/template methods by class and method name; the
		// canonical JVM parameter signature remains in the assignment and fragment.
		return test.className() + "." + test.methodName();
	}
}
