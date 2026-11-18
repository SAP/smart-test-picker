// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.util.List;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.process.CommandLineArgumentProvider;

/** Lazy, configuration-cache-safe ASM javaagent argument. */
public abstract class AsmAgentArgumentProvider implements CommandLineArgumentProvider {
	@Classpath
	public abstract ConfigurableFileCollection getAgentClasspath();

	@OutputFile
	public abstract RegularFileProperty getFragmentOutput();

	@OutputFile
	public abstract RegularFileProperty getDiagnosticOutput();

	@Input
	public abstract Property<String> getRevision();

	@Input
	public abstract Property<String> getShardId();

	@Input
	public abstract Property<Integer> getSchemaVersion();

	@Input
	@org.gradle.api.tasks.Optional
	public abstract Property<String> getExecutionTarget();

	@Input
	public abstract Property<String> getRunId();

	@Input
	public abstract ListProperty<String> getIncludes();

	@Input
	public abstract ListProperty<String> getExcludes();

	// Scope is resolved at fork execution, without retaining Project or Test instances.
	// Incremental task/cache policy is separate from this production-boundary change.
	@Internal public abstract ConfigurableFileCollection getRuntimeSources();
	@Internal public abstract ConfigurableFileCollection getProductionCandidates();
	@Internal public abstract ConfigurableFileCollection getTestOutputs();
	@Internal public abstract RegularFileProperty getProductionSourcesFile();
	@Internal public abstract Property<String> getTestTaskPath();

	@Override
	public Iterable<String> asArguments() {
		File agent = getAgentClasspath().getSingleFile();
		List<String> includes = getIncludes().get();
		if (getProductionSourcesFile().isPresent()) {
			try {
				var scope = GradleAsmScope.resolve(getTestTaskPath().get(), getRuntimeSources().getFiles(),
						getProductionCandidates().getFiles(), getTestOutputs().getFiles(), includes);
				includes = scope.includes();
				var sources = getProductionSourcesFile().get().getAsFile().toPath();
				java.nio.file.Files.createDirectories(sources.getParent());
				java.nio.file.Files.write(sources, scope.sources().stream().map(Object::toString).toList());
			} catch (java.io.IOException failure) {
				throw new org.gradle.api.GradleException("Cannot resolve ASM production scope for " + getTestTaskPath().get(), failure);
			}
		}
		StringBuilder arguments = new StringBuilder("output=").append(getDiagnosticOutput().get().getAsFile())
				.append(";fragmentOutput=").append(getFragmentOutput().get().getAsFile())
				.append(";revision=").append(getRevision().get())
				.append(";shardId=").append(getShardId().get())
				.append(";schemaVersion=").append(getSchemaVersion().get())
				.append(";runId=").append(getRunId().get());
		if (getExecutionTarget().isPresent()) {
			arguments.append(";executionTarget=").append(getExecutionTarget().get());
		}
		appendPrefixes(arguments, "includes", includes);
		appendPrefixes(arguments, "excludes", GradleAsmScope.prefixes(getExcludes().get()));
		arguments.append(";requireInstrumentation=true");
		if (getProductionSourcesFile().isPresent()) {
			arguments.append(";productionSourcesFile=").append(getProductionSourcesFile().get().getAsFile());
		}
		return List.of("-javaagent:" + agent.getAbsolutePath() + "=" + arguments);
	}

	private static void appendPrefixes(StringBuilder target, String name, List<String> values) {
		if (!values.isEmpty()) {
			target.append(';').append(name).append('=').append(String.join(",", values));
		}
	}
}
