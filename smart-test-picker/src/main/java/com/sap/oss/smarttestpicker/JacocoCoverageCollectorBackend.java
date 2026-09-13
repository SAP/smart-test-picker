// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.gradle.api.tasks.testing.Test;

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
	public void configure(Project project, SmartTestPickerExtension extension, Test test, Task mappingTask,
			String executionTarget) {
		if (test instanceof StpCoverageTest tracked) {
			tracked.getCoverageCollector().set(type());
			tracked.getCoverageRevision().set(extension.getRevision());
			tracked.getCoverageShardId().set(extension.getShardId().getOrElse("gradle:" + test.getPath()));
		}
		test.systemProperty("stp.schemaVersion", extension.getRuntimeSchemaVersion().get());
		if (executionTarget != null) test.systemProperty("stp.executionTarget", executionTarget);
		else if (extension.getExecutionTarget().isPresent())
			test.systemProperty("stp.executionTarget", extension.getExecutionTarget().get());
		test.setClasspath(test.getClasspath().plus(collectorRuntime));
		String shard = extension.getShardId().getOrElse("gradle:" + test.getPath());
		java.io.File execDir = executionTarget == null
				? project.getLayout().getBuildDirectory().dir("jacoco").get().getAsFile()
				: executableExecDir(project, test, shard);
		test.systemProperty("stp.exec.dir", execDir);
		test.systemProperty("junit.jupiter.extensions.autodetection.enabled", "true");
		JacocoTaskExtension jacoco = test.getExtensions().findByType(JacocoTaskExtension.class);
		if (jacoco == null) {
			throw new IllegalStateException("JACOCO coverage collector requires the Gradle jacoco plugin");
		}
		jacoco.setDestinationFile(new java.io.File(execDir, "task.exec"));
		if (executionTarget != null) {
			project.getLogger().lifecycle("Smart Test Picker coverage collector: JACOCO (schema-v3 task-local)");
			return;
		}
		Task reports = project.getTasks().getByName("generateSmartReports");
		Task legacyMap = project.getTasks().getByName("generateTestCoverageJson");
		reports.dependsOn(test);
		legacyMap.dependsOn(reports);
		mappingTask.dependsOn(legacyMap);
		project.getLogger().lifecycle("Smart Test Picker coverage collector: JACOCO");
	}

	static java.io.File executableExecDir(Project project, Test test, String shard) {
		return new java.io.File(project.getBuildDir(), "stp/jacoco/" + AsmCoverageCollectorBackend.safe(test.getPath())
				+ "/" + AsmCoverageCollectorBackend.safe(shard) + "/exec");
	}
}
