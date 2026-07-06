// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import com.sap.oss.smarttestpicker.engine.FileUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.sap.oss.smarttestpicker.engine.TestSelectionEngine;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;


/**
 * Aggregator Mojo that runs test selection on the merged coverage map at the root level
 * and writes Surefire includes files to each reactor module.
 *
 * <p>Uses the merged {@code test-coverage-map.json} from the root project's target directory
 * (produced by a prior baseline run with {@code smart-test-picker} profile).
 * Writes {@code selected-tests.json} to root target plus a per-module
 * {@code selected-tests-surefire.txt} so Surefire filters tests automatically.</p>
 */
@Mojo(name = "root-select-tests", aggregator = true)
public class RootSelectTestsMojo extends AbstractMojo
{

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;

	@Parameter(defaultValue = "500", property = "smartTestPicker.maxCommitDistance")
	private int maxCommitDistance;

	@Parameter(property = "smartTestPicker.fullSuiteTriggers")
	private List<String> fullSuiteTriggers;

	@Parameter(defaultValue = "false", property = "smartTestPicker.classLevelSelection")
	private boolean classLevelSelection;

	@Override
	public void execute() throws MojoExecutionException
	{
		MavenProject root = findExecutionRoot();
		File rootTarget = new File(root.getBuild().getDirectory());

		File coverageMapFile = new File(rootTarget, "test-coverage-map.json");
		if (!coverageMapFile.exists())
		{
			getLog().warn("[SmartTestPicker] Merged coverage map not found: " + coverageMapFile.getAbsolutePath()
					+ " — run baseline first (mvn verify -Psmart-test-picker)");
			return;
		}

		List<File> testClassesDirs = new ArrayList<>();
		List<File> testSourceDirs = new ArrayList<>();
		for (MavenProject module : reactorProjects)
		{
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}
			File dir = new File(module.getBuild().getDirectory(), "test-classes");
			if (dir.exists())
			{
				testClassesDirs.add(dir);
			}
			for (String srcRoot : module.getTestCompileSourceRoots())
			{
				File srcDir = new File(srcRoot);
				if (srcDir.exists())
				{
					testSourceDirs.add(srcDir);
				}
			}
		}

		TestSelectionEngine engine = new TestSelectionEngine();
		SelectionOutput output = engine.select(
				coverageMapFile,
				testClassesDirs,
				testSourceDirs,
				root.getBasedir(),
				maxCommitDistance,
				fullSuiteTriggers != null ? fullSuiteTriggers : List.of(),
				new MavenEngineLogger(getLog()));

		getLog().info("[SmartTestPicker] Status: " + output.getStatus() + " — " + output.getReason());

		// Write selected-tests.json to root target
		File selectedTestsFile = new File(rootTarget, "selected-tests.json");
		try
		{
			FileUtils.ensureParentDirExists(selectedTestsFile);
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

		// Write Surefire includesFile to EACH reactor module's target
		int modulesWritten = 0;
		for (MavenProject module : reactorProjects)
		{
			if ("pom".equals(module.getPackaging()))
			{
				continue;
			}
			try
			{
				File moduleTarget = new File(module.getBuild().getDirectory());
				moduleTarget.mkdirs();
				File includesFile = new File(moduleTarget, "selected-tests-surefire.txt");
				SmartTestFilter.writeIncludesFile(output, includesFile, classLevelSelection);

				module.getProperties().setProperty("surefire.includesFile",
						includesFile.getAbsolutePath());
				modulesWritten++;
			}
			catch (IOException e)
			{
				getLog().warn("[SmartTestPicker] Failed to write includes file for "
						+ module.getArtifactId() + ": " + e.getMessage());
			}
		}

		getLog().info("[SmartTestPicker] Selected tests written to root + "
				+ modulesWritten + " module includes files");
	}

	/** Returns the reactor root project, falling back to the current project if none is marked as execution root. */
	private MavenProject findExecutionRoot()
	{
		for (MavenProject p : reactorProjects)
		{
			if (p.isExecutionRoot())
			{
				return p;
			}
		}
		return project;
	}
}
