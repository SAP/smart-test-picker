// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;

final class AsmCoverageCollectorBackend implements CoverageCollectorBackend {
	private final Configuration agent;

	AsmCoverageCollectorBackend(Configuration agent) {
		this.agent = agent;
	}

	@Override public CoverageCollectorType type() { return CoverageCollectorType.ASM; }

	@Override public CollectorCapabilities capabilities() {
		return new CollectorCapabilities(true, true, true, true, true);
	}

	@Override
	public void configure(Project project, SmartTestPickerExtension extension, StpCoverageTest test, Task mappingTask) {
		String taskSegment = safe(test.getPath());
		String shard = extension.getShardId().getOrElse("gradle:" + test.getPath());
		String shardSegment = safe(shard);
		var outputDir = project.getLayout().getBuildDirectory().dir("stp/coverage/" + taskSegment + "/" + shardSegment);

		test.getCoverageCollector().set(type());
		test.getCoverageRevision().set(extension.getRevision());
		test.getCoverageShardId().set(shard);
		test.getAgentConfigurationVersion().set(project.getVersion().toString());
		test.setMaxParallelForks(1);
		var fragment = outputDir.map(dir -> dir.file("fragment.json"));
		var diagnostic = outputDir.map(dir -> dir.file("agent-diagnostic.json"));
		test.doFirst(ignored -> {
			outputDir.get().getAsFile().mkdirs();
			deleteStale(fragment.get().getAsFile(), "fragment");
			deleteStale(diagnostic.get().getAsFile(), "diagnostic");
		});
		test.doLast(ignored -> verifyFragment(fragment.get().getAsFile(), extension.getRevision().get(), shard));

		AsmAgentArgumentProvider arguments = project.getObjects().newInstance(AsmAgentArgumentProvider.class);
		arguments.getAgentClasspath().from(agent);
		arguments.getFragmentOutput().set(fragment);
		arguments.getDiagnosticOutput().set(diagnostic);
		arguments.getRevision().set(extension.getRevision());
		arguments.getShardId().set(shard);
		arguments.getRunId().set("gradle:" + test.getPath() + ":" + shard);
		arguments.getIncludes().set(extension.getCoverageIncludes());
		arguments.getExcludes().set(extension.getCoverageExcludes());
		test.getJvmArgumentProviders().add(arguments);
		project.getLogger().lifecycle("Smart Test Picker coverage collector: ASM");
	}

	static void deleteStale(java.io.File file, String kind) {
		try {
			java.nio.file.Files.deleteIfExists(file.toPath());
		} catch (java.io.IOException failure) {
			throw new GradleException("Cannot invalidate stale ASM " + kind + " output: " + file, failure);
		}
	}

	static void verifyFragment(java.io.File file, String revision, String shard) {
		try {
			var value = new CoverageFragmentCodec().deserialize(java.nio.file.Files.readAllBytes(file.toPath()));
			if (!revision.equals(value.revision().value())) throw new GradleException("ASM fragment revision mismatch");
			if (!shard.equals(value.shardId().value())) throw new GradleException("ASM fragment shard mismatch");
			if (!value.collectionCompleted()) throw new GradleException("ASM fragment collection is incomplete");
		} catch (GradleException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new GradleException("Fresh valid ASM fragment was not produced: " + file, failure);
		}
	}

	private static String safe(String value) {
		String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
		return safe.isBlank() ? "default" : safe;
	}
}
