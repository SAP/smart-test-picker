// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.sap.oss.smarttestpicker.engine.TestSelectionEngine;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;


/**
 * Maven Mojo that selects tests impacted by code changes.
 *
 * <p>Equivalent of the Gradle {@code selectTests} task. Reads the coverage map,
 * detects git changes, and writes {@code selected-tests.json} plus a
 * Surefire-compatible includes file.</p>
 *
 * <p>Usage: {@code mvn smart-test-picker:select-tests}</p>
 */
@Mojo(name = "select-tests", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class SelectTestsMojo extends AbstractMojo
{

	@Parameter(defaultValue = "${project.build.directory}/test-coverage-map.json", required = true)
	private File coverageMapFile;

	@Parameter(defaultValue = "${project.build.directory}/selected-tests.json", required = true)
	private File selectedTestsFile;

	@Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
	private File testClassesDir;

	@Parameter(defaultValue = "${project.basedir}", readonly = true)
	private File projectDir;

	@Parameter(defaultValue = "500", property = "smartTestPicker.maxCommitDistance")
	private int maxCommitDistance;

	@Parameter(property = "smartTestPicker.fullSuiteTriggers")
	private List<String> fullSuiteTriggers;

	@Parameter(defaultValue = "false", property = "smartTestPicker.classLevelSelection")
	private boolean classLevelSelection;

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject mavenProject;

	@Override
	public void execute() throws MojoExecutionException
	{
		TestSelectionEngine engine = new TestSelectionEngine();
		SelectionOutput output = engine.select(
				coverageMapFile,
				testClassesDir,
				projectDir,
				maxCommitDistance,
				fullSuiteTriggers != null ? fullSuiteTriggers : List.of(),
				new MavenEngineLogger(getLog()));

		getLog().info("[SmartTestPicker] Status: " + output.getStatus() + " \u2014 " + output.getReason());

		// Write selected-tests.json
		try
		{
			selectedTestsFile.getParentFile().mkdirs();
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			try (FileWriter writer = new FileWriter(selectedTestsFile))
			{
				gson.toJson(output, writer);
			}
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to write selected tests file", e);
		}

		// Write Surefire includes file and inject property
		try
		{
			File includesFile = new File(selectedTestsFile.getParent(), "selected-tests-surefire.txt");
			SmartTestFilter.writeIncludesFile(output, includesFile, classLevelSelection);
			if (includesFile.exists())
			{
				mavenProject.getProperties().setProperty("surefire.includesFile",
						includesFile.getAbsolutePath());
				getLog().info("[SmartTestPicker] Surefire includesFile set to: " + includesFile.getAbsolutePath());
			}
		}
		catch (IOException e)
		{
			getLog().warn("[SmartTestPicker] Failed to write Surefire includes file: " + e.getMessage());
		}
	}
}
