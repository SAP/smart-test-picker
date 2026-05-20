// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker;

import java.io.IOException;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import io.github.ljubisap.smarttestpicker.engine.ReportEngine;


/**
 * Gradle task that generates an HTML dashboard report of test selection results.
 *
 * <p>Thin Gradle wrapper that delegates to {@link ReportEngine}
 * for the actual report generation logic.</p>
 *
 * <p>Output: {@code build/reports/smart-test-picker/index.html}</p>
 *
 * @see ReportEngine
 */
public abstract class GenerateTestReportTask extends DefaultTask
{

	/** The coverage map JSON file (typically {@code build/test-coverage-map.json}). */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getCoverageMapFile();

	/** The selected-tests.json file produced by the selectTests task. */
	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getSelectedTestsFile();

	/** Output HTML report file. */
	@OutputFile
	public abstract RegularFileProperty getReportFile();

	/** Maximum commit distance (for display in the report). */
	@Input
	public abstract Property<Integer> getMaxCommitDistance();

	/** Whether class-level selection is enabled (for display in the report). */
	@Input
	@Optional
	public abstract Property<Boolean> getClassLevelSelection();

	/** Directory containing per-test JaCoCo XML reports (for source coverage pages). */
	@InputDirectory
	@Optional
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getJacocoXmlDir();

	/** Java source directory (for reading source files for coverage pages). */
	@InputDirectory
	@Optional
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract DirectoryProperty getSourceDir();

	/**
	 * Delegates to {@link ReportEngine} to generate the HTML report.
	 */
	@TaskAction
	public void generate() throws IOException
	{
		new ReportEngine().generate(
				getCoverageMapFile().getAsFile().get(),
				getSelectedTestsFile().getAsFile().get(),
				getReportFile().getAsFile().get(),
				getJacocoXmlDir().isPresent() ? getJacocoXmlDir().getAsFile().get() : null,
				getSourceDir().isPresent() ? getSourceDir().getAsFile().get() : null,
				getProject().getProjectDir(),
				getMaxCommitDistance().get(),
				getClassLevelSelection().getOrElse(false),
				new GradleEngineLogger(getLogger()));
	}
}
