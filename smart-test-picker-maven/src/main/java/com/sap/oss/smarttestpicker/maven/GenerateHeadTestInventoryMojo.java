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

/** Generates the exact schema-v2 inventory using JUnit Platform discovery only. */
@Mojo(name = "generate-head-test-inventory", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
		requiresDependencyResolution = ResolutionScope.TEST)
public final class GenerateHeadTestInventoryMojo extends AbstractMojo {
	@Parameter(defaultValue = "${project}", readonly = true, required = true) private MavenProject project;
	/** Destination of this goal's generated or downloaded artifact. Default: {@code ${project.build.directory}/head-test-inventory.json}. */
	@Parameter(defaultValue = "${project.build.directory}/head-test-inventory.json", required = true) private File outputFile;
	/** Expected full head revision for inventory or explicit PR selection. Default: {@code unset; inventory uses checked-out HEAD, explicit selection requires a full commit}. */
	@Parameter(property = "smartTestPicker.prHeadRevision") private String prHeadRevision;
	@Override public void execute() throws MojoExecutionException {
		if (!MavenHeadTestInventory.generate(List.of(project), outputFile, project.getBasedir(), prHeadRevision, getLog()))
			throw new MojoExecutionException("JUnit head inventory discovery failed");
	}
}
