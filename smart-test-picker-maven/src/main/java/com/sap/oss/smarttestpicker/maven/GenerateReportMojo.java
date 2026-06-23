// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.sap.oss.smarttestpicker.engine.ReportEngine;


/**
 * Maven Mojo that generates an HTML dashboard report of test selection results.
 *
 * <p>Equivalent of the Gradle {@code generateTestReport} task.
 * Reads coverage map and selected tests, produces a self-contained HTML
 * dashboard with stat cards, donut charts, coverage matrix, and source pages.</p>
 *
 * <p>Usage: {@code mvn smart-test-picker:generate-report}</p>
 */
@Mojo(name = "generate-report", defaultPhase = LifecyclePhase.VERIFY)
public class GenerateReportMojo extends AbstractMojo
{

	@Parameter(defaultValue = "${project.build.directory}/test-coverage-map.json")
	private File coverageMapFile;

	@Parameter(defaultValue = "${project.build.directory}/selected-tests.json")
	private File selectedTestsFile;

	@Parameter(defaultValue = "${project.build.directory}/reports/smart-test-picker/index.html")
	private File reportFile;

	@Parameter(defaultValue = "${project.build.directory}/jacoco-xml")
	private File jacocoXmlDir;

	@Parameter(defaultValue = "${project.basedir}/src/main/java")
	private File sourceDir;

	@Parameter(defaultValue = "${project.basedir}", readonly = true)
	private File projectDir;

	@Parameter(defaultValue = "500", property = "smartTestPicker.maxCommitDistance")
	private int maxCommitDistance;

	@Parameter(defaultValue = "false", property = "smartTestPicker.classLevelSelection")
	private boolean classLevelSelection;

	@Override
	public void execute() throws MojoExecutionException
	{
		if (!coverageMapFile.exists())
		{
			getLog().warn("[SmartTestPicker] Coverage map not found: " + coverageMapFile.getAbsolutePath());
			return;
		}
		if (!selectedTestsFile.exists())
		{
			getLog().warn("[SmartTestPicker] Selected tests file not found: " + selectedTestsFile.getAbsolutePath());
			return;
		}

		try
		{
			new ReportEngine().generate(
					coverageMapFile,
					selectedTestsFile,
					reportFile,
					jacocoXmlDir.exists() ? jacocoXmlDir : null,
					sourceDir.exists() ? sourceDir : null,
					projectDir,
					maxCommitDistance,
					classLevelSelection,
					new MavenEngineLogger(getLog()));
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to generate report", e);
		}
	}
}
