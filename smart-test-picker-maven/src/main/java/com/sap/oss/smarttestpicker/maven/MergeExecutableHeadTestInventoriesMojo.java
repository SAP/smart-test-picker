// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventory;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;

@Mojo(name = "merge-executable-head-test-inventories", aggregator = true)
public final class MergeExecutableHeadTestInventoriesMojo extends AbstractMojo {
	@Parameter(property = "smartTestPicker.inputFiles", required = true) private String inputFiles;
	@Parameter(defaultValue = "${session.executionRootDirectory}/target/head-test-inventory.json",
			property = "smartTestPicker.outputFile", required = true) private File outputFile;

	@Override public void execute() throws MojoExecutionException {
		try {
			if (inputFiles == null || inputFiles.isBlank()) throw new IllegalArgumentException("Input inventories are required");
			var files = new ArrayList<File>();
			for (String value : inputFiles.split(",", -1)) {
				if (value.isBlank()) throw new IllegalArgumentException("Malformed input inventory list");
				files.add(new File(value));
			}
			int count = merge(files, outputFile);
			getLog().info("[SmartTestPicker] Merged " + count + " executable JUnit tests");
		} catch (Exception failure) {
			throw new MojoExecutionException("Cannot merge executable head inventories: " + failure.getMessage(), failure);
		}
	}

	static int merge(List<File> files, File output) throws Exception {
			var codec = new ExecutableHeadTestInventoryCodec();
			String revision = null;
			var tests = new ArrayList<ExecutableTestIdentity>();
			for (File file : files) {
				var inventory = codec.read(file);
				if (revision == null) revision = inventory.revision();
				else if (!revision.equals(inventory.revision())) throw new IllegalArgumentException("Input inventory revision mismatch");
				tests.addAll(inventory.runnableTests());
			}
			if (new HashSet<>(tests).size() != tests.size())
				throw new IllegalArgumentException("Duplicate executable identity across input inventories");
			codec.write(output, ExecutableHeadTestInventory.atRevision(revision, tests));
			return tests.size();
	}
}
