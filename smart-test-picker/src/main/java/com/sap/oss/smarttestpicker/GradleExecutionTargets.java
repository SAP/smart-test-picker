// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;

/** Canonical Gradle ownership boundary: one concrete Test task path. */
final class GradleExecutionTargets {
	private GradleExecutionTargets() {}

	static ExecutionTarget forTask(Test task) {
		if (task == null) throw new IllegalArgumentException("Gradle Test task is required");
		ExecutionTarget target = new ExecutionTarget(BuildTool.GRADLE, task.getPath());
		if (!target.equals(ExecutionTarget.parse(target.toString())))
			throw new IllegalArgumentException("Gradle target does not round-trip: " + task.getPath());
		return target;
	}

	static List<Test> resolve(Project root, List<String> configuredTaskPaths) {
		if (!root.equals(root.getRootProject())) throw new IllegalArgumentException("Root Gradle project is required");
		if (!root.getGradle().getIncludedBuilds().isEmpty())
			throw new GradleException("Schema-v3 Gradle executable routing does not support composite/included builds");
		Set<String> scope = new HashSet<>();
		for (String path : configuredTaskPaths) {
			if (path == null || path.isBlank() || !path.startsWith(":") || path.endsWith(":"))
				throw new GradleException("Malformed Gradle Test-task path in mapping scope: " + path);
			if (!scope.add(path)) throw new GradleException("Duplicate Gradle Test-task path in mapping scope: " + path);
		}
		List<Test> concrete = root.getAllprojects().stream()
				.flatMap(candidate -> candidate.getTasks().withType(Test.class).stream())
				.filter(task -> !(task instanceof StpCoverageTest) && !task.getName().equals("smartTest"))
				.sorted(java.util.Comparator.comparing(Test::getPath)).toList();
		Set<String> known = concrete.stream().map(Test::getPath).collect(java.util.stream.Collectors.toSet());
		for (String requested : scope)
			if (!known.contains(requested)) throw new GradleException("Unknown Gradle Test-task path in mapping scope: " + requested);
		List<Test> targets = concrete.stream().filter(task -> scope.isEmpty() || scope.contains(task.getPath()))
				.filter(Test::getEnabled).toList();
		if (targets.isEmpty()) throw new GradleException("Schema-v3 Gradle mapping found no enabled Test tasks in configured scope");
		return targets;
	}

	static boolean participates(Test task, List<String> configuredTaskPaths) {
		return !(task instanceof StpCoverageTest) && !task.getName().equals("smartTest")
				&& task.getEnabled() && (configuredTaskPaths.isEmpty() || configuredTaskPaths.contains(task.getPath()));
	}
}
