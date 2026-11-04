// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.testing.Test;

/** Gradle wiring stays outside the shared collector contract. */
interface GradleCoverageCollectorBackend extends CoverageCollectorBackend {
	void configure(Project project, SmartTestPickerExtension extension, Test coverageTest,
			Task mappingTask, String executionTarget);
}
