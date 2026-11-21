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

/** Generates a revision-bound logical schema-v2 or executable schema-v3 inventory for the effective Maven reactor. */
@Mojo(name = "generate-reactor-head-test-inventory", aggregator = true,
		defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
		requiresDependencyResolution = ResolutionScope.TEST)
public final class GenerateReactorHeadTestInventoryMojo extends AbstractMojo {
    @org.apache.maven.plugins.annotations.Component private org.apache.maven.rtinfo.RuntimeInformation runtimeInformation;
	@Parameter(defaultValue = "${project}", readonly = true, required = true) private MavenProject project;
	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;
	/** Destination of this goal's generated or downloaded artifact. Default: {@code ${session.executionRootDirectory}/.stp/head-test-inventory.json}. */
	@Parameter(defaultValue = "${session.executionRootDirectory}/.stp/head-test-inventory.json",
			property = "smartTestPicker.outputFile", required = true)
	private File outputFile;
	/** Expected full head revision for inventory or explicit PR selection. Default: {@code unset; inventory uses checked-out HEAD, explicit selection requires a full commit}. */
	@Parameter(property = "smartTestPicker.prHeadRevision") private String prHeadRevision;
	/** Select the logical schema 2 or executable schema 3 path supported by this goal. Default: {@code 2}. */
	@Parameter(defaultValue = "2", property = "smartTestPicker.schemaVersion", required = true) private int schemaVersion;
	/** Provider kind, surefire or failsafe, used for target resolution and effective test selection. Default: {@code unset (unqualified Surefire target)}. */
	@Parameter(property = "smartTestPicker.executionType") private String executionType;
	/** Provider execution ID used to distinguish a qualified Maven execution target. Default: {@code unset (provider default execution)}. */
	@Parameter(property = "smartTestPicker.executionId") private String executionId;
	/** Profile qualifier in the canonical Maven execution target; does not replace Maven profile activation. Default: {@code unset (no profile qualifier)}. */
	@Parameter(property = "smartTestPicker.executionProfile") private String executionProfile;
	/** Additional narrowing filter applied after the provider's effective test selection. Default: {@code unset (no additional narrowing)}. */
	@Parameter(property = "smartTestPicker.testFilter") private String testFilter;
	/** Canonical reactor root used to derive stable module-relative execution targets. Default: {@code Maven execution root}. */
	@Parameter(property = "smartTestPicker.reactorRoot") private File reactorRoot;
	@Parameter(defaultValue = "${session}", readonly = true, required = true) private org.apache.maven.execution.MavenSession session;
	@org.apache.maven.plugins.annotations.Component private org.apache.maven.plugin.MavenPluginManager pluginManager;
	@org.apache.maven.plugins.annotations.Component private org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator executionPlans;
	@org.apache.maven.plugins.annotations.Component private org.eclipse.aether.RepositorySystem repositorySystem;

	@Override public void execute() throws MojoExecutionException {
		if (schemaVersion == 3) MavenMappingVersion.requireSupported("generate-reactor-head-test-inventory", runtimeInformation);
		MavenProject executionRoot = reactorProjects.stream().filter(MavenProject::isExecutionRoot).findFirst()
				.orElseGet(() -> project.isExecutionRoot() ? project : null);
		if (executionRoot == null || executionRoot.getBasedir() == null)
			throw new MojoExecutionException("Cannot determine the Maven execution root");
		boolean generated;
		File canonicalRoot = reactorRoot == null ? executionRoot.getBasedir() : reactorRoot;
		if (schemaVersion == 2) generated = MavenHeadTestInventory.generate(
				reactorProjects, outputFile, canonicalRoot, prHeadRevision, getLog());
		else if (schemaVersion == 3) generated = MavenHeadTestInventory.generateExecutable(
				reactorProjects, outputFile, canonicalRoot, prHeadRevision,
				executionType, executionId, executionProfile, testFilter,
				new MavenProviderSelection(session, pluginManager, executionPlans, repositorySystem, getLog()::debug), getLog());
		else throw new MojoExecutionException("Unsupported Maven inventory schema version: " + schemaVersion);
		if (!generated)
			throw new MojoExecutionException("JUnit head inventory discovery failed");
	}
}
