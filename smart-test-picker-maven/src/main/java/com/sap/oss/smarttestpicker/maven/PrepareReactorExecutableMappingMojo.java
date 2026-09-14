// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;

/** Validates and partitions a schema-v3 assignment before Maven test execution. */
@Mojo(name = "prepare-reactor-executable-mapping", aggregator = true, defaultPhase = LifecyclePhase.INITIALIZE)
public final class PrepareReactorExecutableMappingMojo extends AbstractMojo {
	@Parameter(defaultValue = "${project}", readonly = true, required = true) private MavenProject project;
	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true) private List<MavenProject> reactorProjects;
	@Parameter(defaultValue = "${env.STP_MAPPING_TESTS_FILE}", property = "smartTestPicker.testsFile", required = true) private File assignmentFile;
	@Parameter(property = "smartTestPicker.revision", required = true) private String revision;
	@Parameter(property = "smartTestPicker.shardId", required = true) private String shardId;
	@Parameter(property = "smartTestPicker.executionType") private String executionType;
	@Parameter(property = "smartTestPicker.executionId") private String executionId;
	@Parameter(property = "smartTestPicker.executionProfile") private String executionProfile;
	@Parameter(property = "smartTestPicker.completeInventoryFile") private File completeInventoryFile;
	@Parameter(property = "smartTestPicker.reactorRoot") private File reactorRoot;

	@Override public void execute() throws MojoExecutionException {
		try {
			if (assignmentFile == null || !assignmentFile.isFile()) throw new IllegalArgumentException("STP_MAPPING_TESTS_FILE is required and must exist");
			ExecutableShardAssignment assignment = new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(assignmentFile.toPath()));
			if (!assignment.revision().value().equals(revision)) throw new IllegalArgumentException("Executable assignment revision mismatch");
			if (!assignment.shardId().value().equals(shardId)) throw new IllegalArgumentException("Executable assignment shardId mismatch");
			MavenProject root = reactorProjects.stream().filter(MavenProject::isExecutionRoot).findFirst()
					.orElseGet(() -> project.isExecutionRoot() ? project : null);
			if (root == null || root.getBasedir() == null) throw new IllegalArgumentException("Cannot determine the Maven execution root");
			File canonicalRoot = reactorRoot == null ? root.getBasedir() : reactorRoot;
			var resolver = new MavenExecutionTargetResolver();
			Map<ExecutionTarget,MavenProject> modules = new TreeMap<>();
			for (MavenProject module : reactorProjects) {
				ExecutionTarget target = resolver.resolve(canonicalRoot, module,
						executionType, executionId, executionProfile);
				if (modules.putIfAbsent(target, module) != null) throw new IllegalArgumentException("Ambiguous Maven execution target: " + target);
			}
			var completeInventory = completeInventoryFile == null ? null
					: new ExecutableHeadTestInventoryCodec().read(completeInventoryFile);
			if (completeInventory != null && !completeInventory.revision().equals(revision))
				throw new IllegalArgumentException("Complete Maven inventory revision mismatch");
			Map<ExecutionTarget,Set<TestIdentity>> partitioned = new MavenExecutableAssignmentRouter()
					.partition(assignment, revision, shardId, modules.keySet(),
							completeInventory == null ? null : completeInventory.runnableTests());
			for (var entry : modules.entrySet()) configure(entry.getValue(), entry.getKey(),
					partitioned.get(entry.getKey()), executionType);
		} catch (Exception failure) {
			throw new MojoExecutionException("Cannot prepare schema-v3 Maven mapping: " + failure.getMessage(), failure);
		}
	}

	private static void configure(MavenProject module, ExecutionTarget target, Set<TestIdentity> tests,
			String executionType) throws Exception {
		String provider = executionType == null || executionType.isBlank() ? "surefire" : executionType;
		if (!provider.equals("surefire") && !provider.equals("failsafe"))
			throw new IllegalArgumentException("Unsupported Maven test execution type: " + provider);
		boolean qualified = executionType != null && !executionType.isBlank();
		String scope = target.targetId().replaceAll("[^A-Za-z0-9_.-]", "_");
		String suffix = qualified ? "-" + scope : "";
		File output = new File(module.getBuild().getDirectory(),
				"stp/selected-tests-" + provider + "-v3" + suffix + ".txt");
		Files.createDirectories(output.getParentFile().toPath());
		List<String> patterns = tests.isEmpty() ? List.of("**/__stp_no_assigned_tests__*.java")
				: tests.stream().map(test -> test.className() + "#" + test.methodName()).distinct().toList();
		Files.write(output.toPath(), patterns);
		String otherProvider = provider.equals("surefire") ? "failsafe" : "surefire";
		File suppressed = new File(module.getBuild().getDirectory(),
				"stp/selected-tests-" + otherProvider + "-v3" + suffix + ".txt");
		Files.write(suppressed.toPath(), List.of("**/__stp_no_assigned_tests__*.java"));
		module.getProperties().setProperty(otherProvider + ".includesFile", suppressed.getAbsolutePath());
		module.getProperties().setProperty(otherProvider + ".failIfNoSpecifiedTests", "false");
		module.getProperties().setProperty(provider + ".includesFile", output.getAbsolutePath());
		module.getProperties().setProperty(provider + ".failIfNoSpecifiedTests", "false");
		module.getProperties().setProperty("smartTestPicker.schemaVersion", "3");
		module.getProperties().setProperty("smartTestPicker.executionTarget", target.toString());
		module.getProperties().setProperty("smartTestPicker.moduleFragmentOutput",
				new File(module.getBuild().getDirectory(), "stp/coverage-fragment-v3" + suffix + ".json").getAbsolutePath());
		module.getProperties().setProperty("smartTestPicker.moduleEvidenceOutput",
				new File(module.getBuild().getDirectory(), "stp/execution-evidence-v2" + suffix + ".json").getAbsolutePath());
	}
}
