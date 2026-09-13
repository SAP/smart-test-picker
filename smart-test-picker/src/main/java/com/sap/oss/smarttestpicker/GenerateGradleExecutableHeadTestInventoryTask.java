// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.util.TreeSet;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.TaskAction;

import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventory;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.WorkspaceRevisionVerifier;

/** Aggregates project-local executable inventories without resolving another project's configurations. */
@DisableCachingByDefault(because = "The inventory is bound to the checked-out Git revision")
public abstract class GenerateGradleExecutableHeadTestInventoryTask extends DefaultTask {
	private String revision;
	@InputFiles public abstract ConfigurableFileCollection getProjectInventories();
	@org.gradle.api.tasks.Internal public String getRevision() { return revision; }
	public void setRevision(String revision) { this.revision = revision; }
	@OutputFile public abstract RegularFileProperty getOutputFile();

	@TaskAction public void generate() {
		var output = getOutputFile().get().getAsFile();
		try {
			String exactRevision = WorkspaceRevisionVerifier.requireHead(
					getProject().getRootProject().getProjectDir(), revision);
			var executable = new TreeSet<com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity>();
			for (var part : getProjectInventories().getFiles()) {
				var inventory = new ExecutableHeadTestInventoryCodec().read(part);
				if (!exactRevision.equals(inventory.revision()))
					throw new GradleException("Gradle project inventory revision mismatch: " + part);
				for (var test : inventory.runnableTests())
					if (!executable.add(test)) throw new GradleException("Duplicate Gradle executable identity: " + test);
			}
			new ExecutableHeadTestInventoryCodec().write(output,
					ExecutableHeadTestInventory.atRevision(exactRevision, executable));
			getLogger().lifecycle("[SmartTestPicker] Aggregated {} Gradle executable JUnit tests from {} projects",
					executable.size(), getProjectInventories().getFiles().size());
		} catch (Exception failure) {
			try { Files.deleteIfExists(output.toPath()); } catch (Exception ignored) { }
			throw failure instanceof GradleException gradle ? gradle
					: new GradleException("Gradle executable head inventory generation failed", failure);
		}
	}
}
