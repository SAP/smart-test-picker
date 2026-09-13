// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/** Generates one exact schema-v2 inventory for the effective Maven reactor. */
@Mojo(name = "generate-reactor-head-test-inventory", aggregator = true,
		defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
		requiresDependencyResolution = ResolutionScope.TEST)
public final class GenerateReactorHeadTestInventoryMojo extends AbstractMojo {
	@Parameter(defaultValue = "${project}", readonly = true, required = true) private MavenProject project;
	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;
	@Parameter(defaultValue = "${session.executionRootDirectory}/target/head-test-inventory.json",
			property = "smartTestPicker.outputFile", required = true)
	private File outputFile;
	@Parameter(property = "smartTestPicker.prHeadRevision") private String prHeadRevision;
	@Parameter(defaultValue = "2", property = "smartTestPicker.schemaVersion", required = true) private int schemaVersion;
	@Parameter(property = "smartTestPicker.executionType") private String executionType;
	@Parameter(property = "smartTestPicker.executionId") private String executionId;
	@Parameter(property = "smartTestPicker.executionProfile") private String executionProfile;

	@Override public void execute() throws MojoExecutionException {
		MavenProject executionRoot = reactorProjects.stream().filter(MavenProject::isExecutionRoot).findFirst()
				.orElseGet(() -> project.isExecutionRoot() ? project : null);
		if (executionRoot == null || executionRoot.getBasedir() == null)
			throw new MojoExecutionException("Cannot determine the Maven execution root");
		boolean generated;
		if (schemaVersion == 2) generated = MavenHeadTestInventory.generate(
				reactorProjects, outputFile, executionRoot.getBasedir(), prHeadRevision, getLog());
		else if (schemaVersion == 3) generated = MavenHeadTestInventory.generateExecutable(
				reactorProjects, outputFile, executionRoot.getBasedir(), prHeadRevision,
				executionType, executionId, executionProfile, getLog());
		else throw new MojoExecutionException("Unsupported Maven inventory schema version: " + schemaVersion);
		if (!generated)
			throw new MojoExecutionException("JUnit head inventory discovery failed");
	}
}
