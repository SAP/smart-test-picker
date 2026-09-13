// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
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
}
