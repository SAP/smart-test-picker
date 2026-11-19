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
import com.sap.oss.smarttestpicker.selector.WorkspaceRevisionVerifier;

/** Discovers configured target data without retaining live Test task objects. */
@DisableCachingByDefault(because = "Inventory must observe the checked-out revision on every invocation")
public abstract class GenerateGradleExecutableProjectInventoryTask extends DefaultTask {
	private final List<Target> targets = new ArrayList<>();
	private String revision;

	public GenerateGradleExecutableProjectInventoryTask() { GradleMappingExecution.fresh(this); }

	public abstract static class Target {
		@org.gradle.api.tasks.Input public abstract org.gradle.api.provider.Property<String> getIdentity();
		@org.gradle.api.tasks.Nested public abstract org.gradle.api.provider.Property<GradleTestSelection> getSelection();
	}

	void addTarget(org.gradle.api.tasks.testing.Test task, GradleTestSelection selection) {
		Target target = getProject().getObjects().newInstance(Target.class);
		target.getIdentity().set(GradleExecutionTargets.forTask(task).toString());
		target.getSelection().set(selection);
		targets.add(target);
	}
	@org.gradle.api.tasks.Nested public List<Target> getTargets() { return targets; }
	@org.gradle.api.tasks.Input public String getRevision() { return revision; }
	public void setRevision(String revision) { this.revision = revision; }
	@Internal public abstract org.gradle.api.file.DirectoryProperty getWorkspace();
	@OutputFile public abstract RegularFileProperty getOutputFile();

	@TaskAction public void generate() {
		var output = getOutputFile().get().getAsFile();
		try {
			String exactRevision = WorkspaceRevisionVerifier.requireHead(
					getWorkspace().get().getAsFile(), revision);
			var executable = new TreeSet<ExecutableTestIdentity>();
			for (var task : targets) {
				var logical = task.getSelection().get().discover();
				var target = com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget.parse(task.getIdentity().get());
				for (var test : logical)
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
