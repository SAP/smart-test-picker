// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;

/** Validates and partitions a schema-v3 assignment before Maven test execution. */
@Mojo(name = "prepare-reactor-executable-mapping", aggregator = true, defaultPhase = LifecyclePhase.INITIALIZE)
public final class PrepareReactorExecutableMappingMojo extends AbstractMojo {
	@Parameter(defaultValue = "${project}", readonly = true, required = true) private MavenProject project;
	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true) private List<MavenProject> reactorProjects;
	@Parameter(defaultValue = "${env.STP_MAPPING_TESTS_FILE}", property = "smartTestPicker.testsFile", required = true) private File assignmentFile;
	@Parameter(property = "smartTestPicker.revision", required = true) private String revision;
	@Parameter(property = "smartTestPicker.shardId", required = true) private String shardId;

	@Override public void execute() throws MojoExecutionException {
		try {
			if (assignmentFile == null || !assignmentFile.isFile()) throw new IllegalArgumentException("STP_MAPPING_TESTS_FILE is required and must exist");
			ExecutableShardAssignment assignment = new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(assignmentFile.toPath()));
			if (!assignment.revision().value().equals(revision)) throw new IllegalArgumentException("Executable assignment revision mismatch");
			if (!assignment.shardId().value().equals(shardId)) throw new IllegalArgumentException("Executable assignment shardId mismatch");
			MavenProject root = reactorProjects.stream().filter(MavenProject::isExecutionRoot).findFirst()
					.orElseGet(() -> project.isExecutionRoot() ? project : null);
			if (root == null || root.getBasedir() == null) throw new IllegalArgumentException("Cannot determine the Maven execution root");
			var resolver = new MavenExecutionTargetResolver();
			Map<ExecutionTarget,MavenProject> modules = new TreeMap<>();
			for (MavenProject module : reactorProjects) {
				ExecutionTarget target = resolver.resolve(root.getBasedir(), module);
				if (modules.putIfAbsent(target, module) != null) throw new IllegalArgumentException("Ambiguous Maven execution target: " + target);
			}
			Map<ExecutionTarget,TreeSet<TestIdentity>> partitioned = new TreeMap<>();
			assignment.tests().forEach(identity -> {
				if (identity.target().buildTool() != BuildTool.MAVEN)
					throw new IllegalArgumentException("Maven mapping rejects non-Maven execution target: " + identity.target());
				if (!modules.containsKey(identity.target()))
					throw new IllegalArgumentException("Assigned Maven execution target is absent from reactor: " + identity.target());
				partitioned.computeIfAbsent(identity.target(), ignored -> new TreeSet<>()).add(identity.test());
			});
			for (var entry : modules.entrySet()) configure(entry.getValue(), entry.getKey(), partitioned.getOrDefault(entry.getKey(), new TreeSet<>()));
		} catch (Exception failure) {
			throw new MojoExecutionException("Cannot prepare schema-v3 Maven mapping: " + failure.getMessage(), failure);
		}
	}

	private static void configure(MavenProject module, ExecutionTarget target, TreeSet<TestIdentity> tests) throws Exception {
		File output = new File(module.getBuild().getDirectory(), "stp/selected-tests-surefire-v3.txt");
		Files.createDirectories(output.getParentFile().toPath());
		List<String> patterns = tests.isEmpty() ? List.of("__stp_no_assigned_tests__")
				: tests.stream().map(test -> test.className() + "#" + test.methodName()).distinct().toList();
		Files.write(output.toPath(), patterns);
		module.getProperties().setProperty("surefire.includesFile", output.getAbsolutePath());
		module.getProperties().setProperty("surefire.failIfNoSpecifiedTests", "false");
		module.getProperties().setProperty("smartTestPicker.schemaVersion", "3");
		module.getProperties().setProperty("smartTestPicker.executionTarget", target.toString());
		module.getProperties().setProperty("smartTestPicker.moduleFragmentOutput",
				new File(module.getBuild().getDirectory(), "stp/coverage-fragment-v3.json").getAbsolutePath());
		module.getProperties().setProperty("smartTestPicker.moduleEvidenceOutput",
				new File(module.getBuild().getDirectory(), "stp/execution-evidence-v2.json").getAbsolutePath());
	}
}
