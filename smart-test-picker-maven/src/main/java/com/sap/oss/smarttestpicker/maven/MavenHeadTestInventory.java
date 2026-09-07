// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.logging.Log;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.selector.HeadTestInventory;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.JUnitHeadTestInventoryGenerator;

/** Shared Maven adapter around the build-tool-neutral JUnit discovery producer. */
final class MavenHeadTestInventory {
	private MavenHeadTestInventory() {}

	static boolean generate(List<MavenProject> projects, File output, Log log) {
		try {
			List<TestIdentity> merged = new ArrayList<>();
			boolean discoveredTarget = false;
			for (MavenProject project : projects) {
				if ("pom".equals(project.getPackaging())) continue;
				File root = new File(project.getBuild().getTestOutputDirectory());
				if (!root.isDirectory()) continue;
				discoveredTarget = true;
				HeadTestInventory inventory = new JUnitHeadTestInventoryGenerator().generate(
						project.getTestClasspathElements().stream().map(File::new).map(File::toPath).toList(),
						List.of(root.toPath()));
				merged.addAll(inventory.runnableTests());
			}
			if (!discoveredTarget) throw new IllegalStateException("No compiled Maven test output is available");
			HeadTestInventory inventory = HeadTestInventory.from(merged); // exact cross-module collisions are unsafe
			new HeadTestInventoryCodec().write(output, inventory);
			log.info("[SmartTestPicker] Discovered " + inventory.runnableTests().size() + " logical JUnit tests");
			return true;
		} catch (Exception failure) {
			try { Files.deleteIfExists(output.toPath()); } catch (Exception ignored) { }
			log.error("[SmartTestPicker] Head inventory discovery failed; selection will fail open", failure);
			return false;
		}
	}
}
