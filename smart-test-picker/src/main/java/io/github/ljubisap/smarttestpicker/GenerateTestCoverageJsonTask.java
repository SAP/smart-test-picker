// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker;

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

import io.github.ljubisap.smarttestpicker.engine.CoverageMapEngine;


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
		new CoverageMapEngine().generate(
				getReportsDir().getAsFile().get(),
				getOutputFile().getAsFile().get(),
				getBaseBranch().get(),
				getProject().getProjectDir(),
				new GradleEngineLogger(getLogger()));
	}
}
