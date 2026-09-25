// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.sap.oss.smarttestpicker.engine.ExecToXmlEngine;


/**
 * Maven Mojo that converts per-test JaCoCo {@code .exec} files into XML reports.
 *
 * <p>Equivalent of the Gradle {@code generateSmartReports} task.
 * Scans for {@code session_*.exec} files produced by the per-test JaCoCo
 * instrumentation and generates one XML report per test.</p>
 *
 * <p>Usage: {@code mvn smart-test-picker:generate-reports}</p>
 */
@Mojo(name = "generate-reports", defaultPhase = LifecyclePhase.VERIFY)
public class GenerateReportsMojo extends AbstractMojo
{

	/** Directory containing per-test {@code session_*.exec} files. */
	@Parameter(defaultValue = "${project.build.directory}/jacoco", required = true)
	private File execDir;

	/** Compiled production classes directory. */
	@Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
	private File classesDir;

	/** Projects participating in the active Maven reactor. */
	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;

	/** Source directory for source file references in XML reports. */
	@Parameter(defaultValue = "${project.basedir}/src/main/java")
	private File sourceDir;

	/** Output directory for generated XML reports. */
	@Parameter(defaultValue = "${project.build.directory}/jacoco-xml", required = true)
	private File reportDir;

	/** Number of parallel threads for report generation. */
	@Parameter(defaultValue = "0", property = "smartTestPicker.threads")
	private int threads;

	@Override
	public void execute() throws MojoExecutionException
	{
		if (!execDir.exists())
		{
			getLog().warn("[SmartTestPicker] Exec directory not found: " + execDir.getAbsolutePath()
					+ " — run tests with JaCoCo agent first");
			return;
		}

		List<File> reactorClassesDirs = reactorClassesDirectories();
		if (reactorClassesDirs.isEmpty())
		{
			getLog().warn("[SmartTestPicker] No compiled production classes directories found in the active Maven reactor");
			return;
		}

		int threadCount = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();

		try
		{
			new ExecToXmlEngine().generateReports(
					execDir, reactorClassesDirs, sourceDir, reportDir,
						new MavenEngineLogger(getLog()), threadCount,
						(dir, name) -> name.startsWith("session_") && name.endsWith(".exec"));
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to generate XML reports from exec files", e);
		}
	}

	private List<File> reactorClassesDirectories()
	{
		LinkedHashSet<File> directories = new LinkedHashSet<>();
		if (reactorProjects != null)
		{
			for (MavenProject reactorProject : reactorProjects)
			{
				if (reactorProject.getBuild() != null && reactorProject.getBuild().getOutputDirectory() != null)
				{
					File outputDirectory = new File(reactorProject.getBuild().getOutputDirectory());
					if (outputDirectory.isDirectory())
					{
						directories.add(outputDirectory);
					}
				}
			}
		}
		if (classesDir != null && classesDir.isDirectory())
		{
			directories.add(classesDir);
		}
		return List.copyOf(directories);
	}
}
