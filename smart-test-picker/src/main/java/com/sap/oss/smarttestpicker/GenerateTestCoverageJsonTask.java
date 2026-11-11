// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.io.IOException;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import com.sap.oss.smarttestpicker.engine.CoverageMapEngine;


/**
 * Gradle task that generates the JSON coverage map from per-test JaCoCo XML reports.
 *
 * <p>Thin Gradle wrapper that delegates to {@link CoverageMapEngine}
 * for the actual coverage map generation logic.</p>
 *
 * @see CoverageMapEngine
 */
@CacheableTask
public abstract class GenerateTestCoverageJsonTask extends DefaultTask
{

	/** Directory containing per-test JaCoCo XML reports ({@code session_*.xml}). */
	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getReportsDir();

	/** Output JSON file for the coverage map (typically {@code build/test-coverage-map.json}). */
	@OutputFile
	public abstract RegularFileProperty getOutputFile();

	/** The base branch name to record in the coverage map metadata. */
	@Input
	public abstract Property<String> getBaseBranch();

	/**
	 * Delegates to {@link CoverageMapEngine} to parse XML reports and write JSON.
	 */
	@TaskAction
	public void generate() throws IOException
	{
		// This legacy format cannot encode setup ownership. Never publish a pseudo-test in its place.
		java.nio.file.Files.deleteIfExists(getOutputFile().get().getAsFile().toPath());
		File reports = getReportsDir().getAsFile().get();
		File[] setup = reports.listFiles((dir, name) -> name.startsWith("session_setup_")
				&& (name.endsWith(".status") || name.endsWith(".xml")));
		if (setup != null) for (File file : setup) {
			if (file.getName().endsWith(".status")) {
				String state = java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8).trim();
				if (!state.equals("EMPTY") && !state.equals("COVERED"))
					throw new org.gradle.api.GradleException("Unsafe JaCoCo setup report: " + file + " (" + state + ")");
				if (state.equals("COVERED") && !new File(reports, file.getName().replace(".status", ".xml")).isFile())
					throw new org.gradle.api.GradleException("Missing JaCoCo setup XML: " + file);
			} else if (!new com.sap.oss.smarttestpicker.mapper.CoverageMapperJaxb(reports)
					.readSchemaV2Coverage(file).coveredClasses().isEmpty()) {
				throw new org.gradle.api.GradleException("Legacy Gradle JaCoCo mapping cannot represent positive setup coverage; "
						+ "use executable schema 3 with an authoritative task assignment");
			}
		}
		new CoverageMapEngine().generate(
				getReportsDir().getAsFile().get(),
				getOutputFile().getAsFile().get(),
				getBaseBranch().get(),
				getProject().getProjectDir(),
				new GradleEngineLogger(getLogger()));
	}
}
