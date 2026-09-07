// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;

final class JacocoCoverageCollectorBackend implements CoverageCollectorBackend {
	private final Configuration collectorRuntime;

	JacocoCoverageCollectorBackend(Configuration collectorRuntime) {
		this.collectorRuntime = collectorRuntime;
	}

	@Override public CoverageCollectorType type() { return CoverageCollectorType.JACOCO; }

	@Override public CollectorCapabilities capabilities() {
		return new CollectorCapabilities(true, false, false, false, false);
	}

	@Override
	public void configure(Project project, SmartTestPickerExtension extension, StpCoverageTest test, Task mappingTask) {
		test.getCoverageCollector().set(type());
		test.getCoverageRevision().set(extension.getRevision());
		test.getCoverageShardId().set(extension.getShardId().getOrElse("gradle:" + test.getPath()));
		test.setClasspath(test.getClasspath().plus(collectorRuntime));
		test.systemProperty("stp.exec.dir", project.getLayout().getBuildDirectory().dir("jacoco").get().getAsFile());
		test.systemProperty("junit.jupiter.extensions.autodetection.enabled", "true");
		JacocoTaskExtension jacoco = test.getExtensions().findByType(JacocoTaskExtension.class);
		if (jacoco == null) {
			throw new IllegalStateException("JACOCO coverage collector requires the Gradle jacoco plugin");
		}
		jacoco.setDestinationFile(project.getLayout().getBuildDirectory().file("jacoco/test.exec").get().getAsFile());
		Task reports = project.getTasks().getByName("generateSmartReports");
		Task legacyMap = project.getTasks().getByName("generateTestCoverageJson");
		reports.dependsOn(test);
		legacyMap.dependsOn(reports);
		mappingTask.dependsOn(legacyMap);
		project.getLogger().lifecycle("Smart Test Picker coverage collector: JACOCO");
	}
}
