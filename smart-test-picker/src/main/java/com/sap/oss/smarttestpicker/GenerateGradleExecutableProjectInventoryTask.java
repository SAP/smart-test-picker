// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventory;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.JUnitHeadTestInventoryGenerator;
import com.sap.oss.smarttestpicker.selector.WorkspaceRevisionVerifier;

/** Resolves and discovers Test tasks only while Gradle holds their owning project lock. */
@DisableCachingByDefault(because = "Gradle Test task topology is inspected at execution time")
public abstract class GenerateGradleExecutableProjectInventoryTask extends DefaultTask {
	private final List<org.gradle.api.tasks.testing.Test> targets = new ArrayList<>();
	private String revision;

	void addTarget(org.gradle.api.tasks.testing.Test task) { targets.add(task); }
	@Internal List<org.gradle.api.tasks.testing.Test> getTargets() { return targets; }
	@Internal public String getRevision() { return revision; }
	public void setRevision(String revision) { this.revision = revision; }
	@OutputFile public abstract RegularFileProperty getOutputFile();

	@TaskAction public void generate() {
		var output = getOutputFile().get().getAsFile();
		try {
			String exactRevision = WorkspaceRevisionVerifier.requireHead(
					getProject().getRootProject().getProjectDir(), revision);
			var executable = new TreeSet<ExecutableTestIdentity>();
			for (var task : targets) {
				var logical = new JUnitHeadTestInventoryGenerator().generate(null,
						task.getClasspath().getFiles().stream().map(java.io.File::toPath).toList(),
						task.getTestClassesDirs().getFiles().stream().map(java.io.File::toPath).toList());
				var target = GradleExecutionTargets.forTask(task);
				for (var test : logical.runnableTests())
					if (!executable.add(new ExecutableTestIdentity(target, test)))
						throw new GradleException("Duplicate Gradle executable identity: " + target + "::" + test);
			}
			new ExecutableHeadTestInventoryCodec().write(output,
					ExecutableHeadTestInventory.atRevision(exactRevision, executable));
		} catch (Exception failure) {
			try { Files.deleteIfExists(output.toPath()); } catch (Exception ignored) { }
			throw failure instanceof GradleException gradle ? gradle
					: new GradleException("Gradle project executable inventory generation failed", failure);
		}
	}
}
