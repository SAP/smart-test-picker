// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import io.github.ljubisap.smarttestpicker.engine.CoverageMapEngine;


/**
 * Maven Mojo that generates the JSON coverage map from per-test JaCoCo XML reports.
 *
 * <p>Equivalent of the Gradle {@code generateTestCoverageJson} task.
 * Reads per-test XML reports produced by JaCoCo and writes a unified JSON
 * coverage map with metadata.</p>
 *
 * <p>Usage: {@code mvn smart-test-picker:generate-coverage-map}</p>
 */
@Mojo(name = "generate-coverage-map", defaultPhase = LifecyclePhase.VERIFY)
public class GenerateCoverageMapMojo extends AbstractMojo
{

	@Parameter(defaultValue = "${project.build.directory}/jacoco-xml", required = true)
	private File reportsDir;

	@Parameter(defaultValue = "${project.build.directory}/test-coverage-map.json", required = true)
	private File outputFile;

	@Parameter(defaultValue = "main", property = "smartTestPicker.baseBranch")
	private String baseBranch;

	@Parameter(defaultValue = "${project.basedir}", readonly = true)
	private File projectDir;

	@Override
	public void execute() throws MojoExecutionException
	{
		if (!reportsDir.exists())
		{
			getLog().warn("[SmartTestPicker] Reports directory not found: " + reportsDir.getAbsolutePath());
			return;
		}

		try
		{
			new CoverageMapEngine().generate(
					reportsDir,
					outputFile,
					baseBranch,
					projectDir,
					new MavenEngineLogger(getLog()));
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to generate coverage map", e);
		}
	}
}
