// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.nio.file.Files;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;

import com.sap.oss.smarttestpicker.selector.HeadTestInventory;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.JUnitHeadTestInventoryGenerator;
import com.sap.oss.smarttestpicker.selector.WorkspaceRevisionVerifier;
import org.gradle.api.provider.Property;
import org.gradle.api.GradleException;

/** Discovers, but never executes, the standard test task's logical JUnit tests. */
public abstract class GenerateHeadTestInventoryTask extends DefaultTask {
	@Classpath public abstract ConfigurableFileCollection getRuntimeClasspath();
	@InputFiles public abstract ConfigurableFileCollection getTestClassesDirs();
	@OutputFile public abstract RegularFileProperty getOutputFile();
	@Input @Optional public abstract Property<String> getRevision();

	@TaskAction public void generate() {
		File output = getOutputFile().get().getAsFile();
		try {
			getLogger().info("[SmartTestPicker] Discovering JUnit tests in {}", getTestClassesDirs().getFiles());
			String revision = getRevision().getOrNull();
			if (revision != null) revision = WorkspaceRevisionVerifier.requireHead(getProject().getProjectDir(), revision);
			HeadTestInventory inventory = new JUnitHeadTestInventoryGenerator().generate(revision,
					getRuntimeClasspath().getFiles().stream().map(File::toPath).toList(),
					getTestClassesDirs().getFiles().stream().map(File::toPath).toList());
			new HeadTestInventoryCodec().write(output, inventory);
			getLogger().lifecycle("[SmartTestPicker] Discovered {} logical JUnit tests", inventory.runnableTests().size());
		} catch (Exception failure) {
			try { Files.deleteIfExists(output.toPath()); } catch (Exception ignored) { }
			getLogger().error("[SmartTestPicker] Head inventory discovery failed; selection will fail open", failure);
			if (getRevision().isPresent())
				throw new GradleException("Explicit PR head inventory generation failed", failure);
		}
	}
}
