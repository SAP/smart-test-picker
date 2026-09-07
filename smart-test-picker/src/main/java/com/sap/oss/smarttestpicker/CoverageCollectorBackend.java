// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.gradle.api.Project;
import org.gradle.api.Task;

/** Internal Gradle-facing setup boundary for exactly one STP collector. */
interface CoverageCollectorBackend {
	CoverageCollectorType type();

	CollectorCapabilities capabilities();

	void configure(Project project, SmartTestPickerExtension extension, StpCoverageTest coverageTest,
			Task mappingTask);
}
