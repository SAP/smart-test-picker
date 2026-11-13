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
		return gradleFilter(test.className(), test.methodName());
	}

	static String gradleFilter(String className, String methodName) {
		// A leading wildcard bypasses Gradle's dot-segment class prefilter, while
		// retaining the entire method text. configureFilter bounds class discovery.
		return (methodName.contains(".") ? "*" : "") + className + "." + methodName;
	}

	static void configureFilter(org.gradle.api.tasks.testing.Test task, Set<TestIdentity> assigned) {
		Map<String, Set<String>> methods = new TreeMap<>();
		assigned.forEach(test -> methods.computeIfAbsent(test.className(), ignored -> new TreeSet<>()).add(test.methodName()));
		configureFilter(task, methods);
	}

	static void configureFilter(org.gradle.api.tasks.testing.Test task, Map<String, Set<String>> methods) {
		boolean needsClassBoundary = methods.values().stream().flatMap(Set::stream).anyMatch(name -> name.contains("."));
		if (needsClassBoundary) {
			// The wildcard must not admit an unassigned class with the same name suffix.
			// Two assigned suffix-colliding classes cannot be disambiguated by this syntax.
			for (String name : methods.keySet()) {
				if (methods.keySet().stream().anyMatch(other -> !other.equals(name) && other.endsWith(name)))
					throw new IllegalArgumentException("Ambiguous Gradle assignment class suffix: " + name);
			}
			Set<String> classFiles = methods.keySet().stream().map(name -> name.replace('.', '/') + ".class")
					.collect(java.util.stream.Collectors.toSet());
			task.exclude(element -> !element.isDirectory() && !classFiles.contains(element.getPath()));
		}
		task.getFilter().setIncludePatterns();
		if (methods.isEmpty()) task.getFilter().includeTestsMatching("__stp_no_tests_assigned__");
		methods.forEach((className, names) -> names.forEach(name -> task.getFilter().includeTestsMatching(gradleFilter(className, name))));
	}
}
