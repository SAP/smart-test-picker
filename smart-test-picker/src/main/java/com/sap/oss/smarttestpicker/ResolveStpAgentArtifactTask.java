// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Materializes the root-owned agent configuration before subproject Test tasks consume it. */
public abstract class ResolveStpAgentArtifactTask extends DefaultTask {
	@Classpath public abstract ConfigurableFileCollection getAgentClasspath();
	@OutputFile public abstract RegularFileProperty getOutputFile();

	@TaskAction public void resolve() {
		var files = getAgentClasspath().getFiles();
		if (files.size() != 1) throw new GradleException("Expected exactly one STP agent artifact, got " + files.size());
		try {
			var output = getOutputFile().get().getAsFile().toPath();
			Files.createDirectories(output.getParent());
			Files.copy(files.iterator().next().toPath(), output, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception failure) {
			throw new GradleException("Cannot materialize the STP agent artifact", failure);
		}
	}
}
