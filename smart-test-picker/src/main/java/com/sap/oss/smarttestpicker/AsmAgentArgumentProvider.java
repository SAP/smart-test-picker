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
	public abstract Property<String> getRunId();

	@Input
	public abstract ListProperty<String> getIncludes();

	@Input
	public abstract ListProperty<String> getExcludes();

	@Override
	public Iterable<String> asArguments() {
		File agent = getAgentClasspath().getSingleFile();
		StringBuilder arguments = new StringBuilder("output=").append(getDiagnosticOutput().get().getAsFile())
				.append(";fragmentOutput=").append(getFragmentOutput().get().getAsFile())
				.append(";revision=").append(getRevision().get())
				.append(";shardId=").append(getShardId().get())
				.append(";runId=").append(getRunId().get());
		appendPrefixes(arguments, "includes", getIncludes().get());
		appendPrefixes(arguments, "excludes", getExcludes().get());
		return List.of("-javaagent:" + agent.getAbsolutePath() + "=" + arguments);
	}

	private static void appendPrefixes(StringBuilder target, String name, List<String> values) {
		if (!values.isEmpty()) target.append(';').append(name).append('=').append(String.join(",", values));
	}
}
