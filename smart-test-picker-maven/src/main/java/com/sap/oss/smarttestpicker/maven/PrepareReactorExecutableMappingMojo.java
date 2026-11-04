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
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import com.sap.oss.smarttestpicker.StpCollectorSelection;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;

/** Validates and partitions a schema-v3 assignment before Maven test execution. */
@Mojo(name = "prepare-reactor-executable-mapping", aggregator = true, defaultPhase = LifecyclePhase.INITIALIZE)
public final class PrepareReactorExecutableMappingMojo extends AbstractMojo {
	@Parameter(defaultValue = "${project}", readonly = true, required = true) private MavenProject project;
	@Parameter(defaultValue = "${session}", readonly = true, required = true) private MavenSession session;
	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true) private List<MavenProject> reactorProjects;
	@Parameter(defaultValue = "${env.STP_MAPPING_TESTS_FILE}", property = "smartTestPicker.testsFile", required = true) private File assignmentFile;
	@Parameter(property = "smartTestPicker.revision", required = true) private String revision;
	@Parameter(property = "smartTestPicker.shardId", required = true) private String shardId;
	@Parameter(property = "smartTestPicker.executionType") private String executionType;
	@Parameter(property = "smartTestPicker.executionId") private String executionId;
	@Parameter(property = "smartTestPicker.executionProfile") private String executionProfile;
	@Parameter(property = "smartTestPicker.completeInventoryFile") private File completeInventoryFile;
	@Parameter(property = "smartTestPicker.reactorRoot") private File reactorRoot;
	@Parameter(defaultValue = "${plugin}", readonly = true, required = true) private PluginDescriptor pluginDescriptor;
	@Component private RepositorySystem repositorySystem;
	@Parameter(property = "smartTestPicker.asm.includes") private String asmIncludes;
	@Parameter(property = "smartTestPicker.asm.excludes") private String asmExcludes;

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
			// Validate the entire assignment before mutating configuration or starting tests.
			for (var entry : modules.entrySet()) {
				if (!partitioned.get(entry.getKey()).isEmpty()
						&& StpMavenLifecycleParticipant.selectedCollector(entry.getValue(), session.getUserProperties())
						== StpCollectorSelection.ASM) {
					AsmMappingForkGuard.validate(entry.getValue(), session, executionType);
				}
			}
			boolean needsAgent = modules.entrySet().stream().anyMatch(entry -> !partitioned.get(entry.getKey()).isEmpty()
					&& StpMavenLifecycleParticipant.selectedCollector(entry.getValue(), session.getUserProperties()) == StpCollectorSelection.ASM);
			boolean asmMapping = modules.values().stream().anyMatch(module ->
					StpMavenLifecycleParticipant.selectedCollector(module, session.getUserProperties()) == StpCollectorSelection.ASM);
			String includes = asmMapping ? AsmMappingAttachment.prefixes(asmIncludes, true) : null;
			String excludes = asmMapping ? AsmMappingAttachment.prefixes(asmExcludes, false) : null;
			File agent = needsAgent ? resolveAgent() : null;
			for (var entry : modules.entrySet()) {
				var collector = StpMavenLifecycleParticipant.selectedCollector(entry.getValue(), session.getUserProperties());
				configure(entry.getValue(), entry.getKey(), partitioned.get(entry.getKey()), executionType, collector);
				if (collector == StpCollectorSelection.ASM && !partitioned.get(entry.getKey()).isEmpty()) {
					AsmMappingAttachment.attach(entry.getValue(), session, executionType, agent, entry.getKey(),
							assignment.revision().value(), assignment.shardId().value(), includes, excludes);
				}
			}
		} catch (Exception failure) {
			throw new MojoExecutionException("Cannot prepare schema-v3 Maven mapping: " + failure.getMessage(), failure);
		}
	}

	private File resolveAgent() throws Exception {
		String version = pluginDescriptor == null ? null : pluginDescriptor.getVersion();
		if (version == null || version.isBlank() || version.contains("${"))
			throw new IllegalArgumentException("Cannot determine the running Maven plugin version for ASM agent resolution");
		var artifact = new DefaultArtifact("com.sap.oss.smart-test-picker", "stp-agent", "", "jar", version);
		try {
			File file = repositorySystem.resolveArtifact(session.getRepositorySession(),
					new ArtifactRequest(artifact, project.getRemoteProjectRepositories(), "stp-asm-mapping")).getArtifact().getFile();
			if (file == null || !file.isFile()) throw new IllegalArgumentException("Resolved agent file is missing");
			getLog().info("ASM mapping agent " + artifact + " resolved to " + file);
			getLog().debug("ASM mapping plugin realm: " + java.util.Arrays.toString(pluginDescriptor.getClassRealm().getURLs()));
			return file;
		} catch (Exception failure) {
			throw new IllegalArgumentException("Cannot resolve ASM mapping agent " + artifact, failure);
		}
	}

	private static void configure(MavenProject module, ExecutionTarget target, Set<TestIdentity> tests,
			String executionType, StpCollectorSelection collector) throws Exception {
		String provider = executionType == null || executionType.isBlank() ? "surefire" : executionType;
		if (!provider.equals("surefire") && !provider.equals("failsafe"))
			throw new IllegalArgumentException("Unsupported Maven test execution type: " + provider);
		// Mapping preparation owns activation; output-directory defaults are independent.
		module.getProperties().setProperty(StpCollectorSelection.ACTIVATION_PROPERTY, "true");
		module.getProperties().setProperty(StpCollectorSelection.PROPERTY, collector.name());
		String artifact = "maven-" + provider + "-plugin";
		Plugin plugin = module.getBuild().getPlugins().stream()
				.filter(value -> artifact.equals(value.getArtifactId())).findFirst().orElse(null);
		if (plugin == null) {
			plugin = new Plugin(); plugin.setGroupId("org.apache.maven.plugins"); plugin.setArtifactId(artifact);
			module.getBuild().addPlugin(plugin);
		}
		Xpp3Dom configuration = plugin.getConfiguration() instanceof Xpp3Dom configured
				? new Xpp3Dom(configured) : new Xpp3Dom("configuration");
		StpMavenLifecycleParticipant.configureCollector(configuration, collector);
		if (collector == StpCollectorSelection.ASM) pinSingleFork(configuration);
		plugin.setConfiguration(configuration);
		for (var execution : plugin.getExecutions()) {
			Xpp3Dom configured = execution.getConfiguration() instanceof Xpp3Dom value
					? new Xpp3Dom(value) : new Xpp3Dom("configuration");
			StpMavenLifecycleParticipant.configureCollector(configured, collector);
			if (collector == StpCollectorSelection.ASM) pinSingleFork(configured);
			execution.setConfiguration(configured);
		}
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
		module.getProperties().setProperty(otherProvider + ".excludesFile", suppressAll(module, otherProvider, suffix));
		module.getProperties().setProperty(otherProvider + ".failIfNoSpecifiedTests", "false");
		module.getProperties().setProperty(provider + ".includesFile", output.getAbsolutePath());
		if (tests.isEmpty()) module.getProperties().setProperty(provider + ".excludesFile", suppressAll(module, provider, suffix));
		module.getProperties().setProperty(provider + ".failIfNoSpecifiedTests", "false");
		module.getProperties().setProperty("smartTestPicker.schemaVersion", "3");
		module.getProperties().setProperty("smartTestPicker.executionTarget", target.toString());
		module.getProperties().setProperty("smartTestPicker.moduleFragmentOutput",
				new File(module.getBuild().getDirectory(), "stp/coverage-fragment-v3" + suffix + ".json").getAbsolutePath());
		module.getProperties().setProperty("smartTestPicker.moduleEvidenceOutput",
				new File(module.getBuild().getDirectory(), "stp/execution-evidence-v2" + suffix + ".json").getAbsolutePath());
	}

	private static void pinSingleFork(Xpp3Dom configuration) {
		// Freeze the validated settings so later property-producing goals cannot change them.
		for (String name : List.of("forkCount", "reuseForks")) {
			Xpp3Dom child = configuration.getChild(name);
			if (child == null) { child = new Xpp3Dom(name); configuration.addChild(child); }
			child.setValue(name.equals("forkCount") ? "1" : "true");
		}
	}

	private static String suppressAll(MavenProject module, String provider, String suffix) throws Exception {
		File file = new File(module.getBuild().getDirectory(), "stp/suppress-all-" + provider + "-v3" + suffix + ".txt");
		Files.write(file.toPath(), List.of("**/*"));
		return file.getAbsolutePath();
	}
}
