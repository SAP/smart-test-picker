// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.TaskAction;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventory;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.JUnitHeadTestInventoryGenerator;
import com.sap.oss.smarttestpicker.selector.WorkspaceRevisionVerifier;

/** Discovers each enabled concrete Gradle Test task independently and qualifies its tests. */
@DisableCachingByDefault(because = "Gradle Test task topology is inspected at execution time")
public abstract class GenerateGradleExecutableHeadTestInventoryTask extends DefaultTask {
	private final List<TargetInput> targets = new ArrayList<>();
	private String revision;

	record TargetInput(com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget target,
			List<java.nio.file.Path> classpath, List<java.nio.file.Path> testClasses) { }

	@Internal List<TargetInput> getTargets() { return targets; }
	void addTarget(org.gradle.api.tasks.testing.Test task) {
		targets.add(new TargetInput(GradleExecutionTargets.forTask(task),
				task.getClasspath().getFiles().stream().map(File::toPath).toList(),
				task.getTestClassesDirs().getFiles().stream().map(File::toPath).toList()));
	}
	@Internal public String getRevision() { return revision; }
	public void setRevision(String revision) { this.revision = revision; }
	@OutputFile public abstract RegularFileProperty getOutputFile();

	@TaskAction public void generate() {
		File output = getOutputFile().get().getAsFile();
		try {
			String exactRevision = WorkspaceRevisionVerifier.requireHead(
					getProject().getRootProject().getProjectDir(), revision);
			TreeSet<ExecutableTestIdentity> executable = new TreeSet<>();
			for (TargetInput input : targets) {
				var logical = new JUnitHeadTestInventoryGenerator().generate(null,
						input.classpath(), input.testClasses());
				var target = input.target();
				for (var test : logical.runnableTests())
					if (!executable.add(new ExecutableTestIdentity(target, test)))
						throw new GradleException("Duplicate Gradle executable identity: " + target + "::" + test);
			}
			new ExecutableHeadTestInventoryCodec().write(output,
					ExecutableHeadTestInventory.atRevision(exactRevision, executable));
			getLogger().lifecycle("[SmartTestPicker] Discovered {} Gradle executable JUnit tests across {} Test tasks",
					executable.size(), targets.size());
		} catch (Exception failure) {
			try { Files.deleteIfExists(output.toPath()); } catch (Exception ignored) { }
			throw failure instanceof GradleException gradle ? gradle
					: new GradleException("Gradle executable head inventory generation failed", failure);
		}
	}
}
