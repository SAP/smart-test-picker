// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import com.sap.oss.smarttestpicker.engine.FileUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.sap.oss.smarttestpicker.selector.SchemaV2SelectorFlow;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionResult;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionStatus;


/**
 * Maven Mojo that selects tests impacted by code changes.
 *
 * <p>Equivalent of the Gradle {@code selectTests} task. Reads the coverage map,
 * detects git changes, and writes {@code selected-tests.json} plus a
 * Surefire-compatible includes file.</p>
 *
 * <p>Usage: {@code mvn smart-test-picker:select-tests}</p>
 */
@Mojo(name = "select-tests", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
		requiresDependencyResolution = ResolutionScope.TEST)
public class SelectTestsMojo extends AbstractMojo
{

	/** Coverage map input used for selection or report generation. Default: {@code ${project.build.directory}/test-coverage-map.json}. */
	@Parameter(defaultValue = "${project.build.directory}/test-coverage-map.json", required = true)
	private File coverageMapFile;

	/** Selected-test JSON output or input, according to the selection/report goal. Default: {@code ${project.build.directory}/selected-tests.json}. */
	@Parameter(defaultValue = "${project.build.directory}/selected-tests.json", required = true)
	private File selectedTestsFile;

	/** Revision-bound inventory of runnable head tests used during selection. Default: {@code ${project.build.directory}/head-test-inventory.json}. */
	@Parameter(defaultValue = "${project.build.directory}/head-test-inventory.json", required = true)
	private File headTestInventoryFile;

	/** Compiled test-class directory used by legacy inventory/selection paths. Default: {@code ${project.build.testOutputDirectory}}. */
	@Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
	private File testClassesDir;

	@Parameter(defaultValue = "${project.basedir}", readonly = true)
	private File projectDir;

	/** Maximum accepted distance from map revision to head before conservative full-suite selection. Default: {@code 500}. */
	@Parameter(defaultValue = "500", property = "smartTestPicker.maxCommitDistance")
	private int maxCommitDistance;

	/** File glob patterns whose changed paths force a full test suite. Default: {@code unset (no triggers)}. */
	@Parameter(property = "smartTestPicker.fullSuiteTriggers")
	private List<String> fullSuiteTriggers;

	/** Expand selection to whole test classes instead of individual methods. Default: {@code false}. */
	@Parameter(defaultValue = "false", property = "smartTestPicker.classLevelSelection")
	private boolean classLevelSelection;

	/** Frozen full integration commit for explicit PR selection; supply all three explicit revisions together. Default: {@code unset; explicit PR mode is disabled unless configured}. */
	@Parameter(property = "smartTestPicker.integrationRevision") private String integrationRevision;
	/** Frozen full PR base commit for explicit PR selection. Default: {@code unset; required with explicit PR selection}. */
	@Parameter(property = "smartTestPicker.prBaseRevision") private String prBaseRevision;
	/** Expected full head revision for inventory or explicit PR selection. Default: {@code unset; inventory uses checked-out HEAD, explicit selection requires a full commit}. */
	@Parameter(property = "smartTestPicker.prHeadRevision") private String prHeadRevision;

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject mavenProject;

	@Override
	public void execute() throws MojoExecutionException
	{
		SelectionOutput output;
		if (MavenExplicitPrSelection.requested(integrationRevision, prBaseRevision, prHeadRevision))
		{
			ExplicitPrSelectionResult explicit = MavenExplicitPrSelection.run(List.of(mavenProject), projectDir,
					coverageMapFile, headTestInventoryFile, new File(selectedTestsFile.getParent(),
					"explicit-pr-selection-result.json"), integrationRevision, prBaseRevision, prHeadRevision,
					maxCommitDistance, fullSuiteTriggers, getLog());
			if (explicit.status() == ExplicitPrSelectionStatus.ERROR)
				throw new MojoExecutionException(explicit.reason());
			if (explicit.status() == ExplicitPrSelectionStatus.BASE_OUT_OF_DATE)
			{
				selectedTestsFile.delete();
				new File(selectedTestsFile.getParent(), "selected-tests-surefire.txt").delete();
				mavenProject.getProperties().remove("surefire.includesFile");
				return;
			}
			output = explicit.output().orElseThrow();
		}
		else
		{
			new File(selectedTestsFile.getParent(), "explicit-pr-selection-result.json").delete();
			MavenHeadTestInventory.generate(List.of(mavenProject), headTestInventoryFile, getLog());
			output = new SchemaV2SelectorFlow().select(
				coverageMapFile,
				headTestInventoryFile,
				projectDir,
				maxCommitDistance,
				fullSuiteTriggers != null ? fullSuiteTriggers : List.of());
		}

		getLog().info("[SmartTestPicker] Status: " + output.getStatus() + " \u2014 " + output.getReason());

		// Write selected-tests.json
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
