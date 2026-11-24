// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
	@Component private org.apache.maven.rtinfo.RuntimeInformation runtimeInformation;
	@Parameter(defaultValue = "${project}", readonly = true, required = true) private MavenProject project;
	@Parameter(defaultValue = "${session}", readonly = true, required = true) private MavenSession session;
	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true) private List<MavenProject> reactorProjects;
	/** Revision-bound executable shard assignment; preparation may only narrow each target's effective selection. Default: {@code ${env.STP_MAPPING_TESTS_FILE}}. */
	@Parameter(defaultValue = "${env.STP_MAPPING_TESTS_FILE}", property = "smartTestPicker.testsFile", required = true) private File assignmentFile;
	/** Exact source revision to which the assignment and fragment must be bound. Default: {@code unset; required for mapping}. */
	@Parameter(property = "smartTestPicker.revision", required = true) private String revision;
	/** Shard identifier that must match the assignment and all collected fragments. Default: {@code unset; required for mapping}. */
	@Parameter(property = "smartTestPicker.shardId", required = true) private String shardId;
	/** Provider kind, surefire or failsafe, used for target resolution and effective test selection. Default: {@code unset (unqualified Surefire target)}. */
	@Parameter(property = "smartTestPicker.executionType") private String executionType;
	/** Provider execution ID used to distinguish a qualified Maven execution target. Default: {@code unset (provider default execution)}. */
	@Parameter(property = "smartTestPicker.executionId") private String executionId;
	/** Profile qualifier in the canonical Maven execution target; does not replace Maven profile activation. Default: {@code unset (no profile qualifier)}. */
	@Parameter(property = "smartTestPicker.executionProfile") private String executionProfile;
	/** Complete revision-bound executable inventory used to validate a narrowed reactor invocation. Default: {@code unset (no complete external inventory)}. */
	@Parameter(property = "smartTestPicker.completeInventoryFile") private File completeInventoryFile;
	/** Canonical reactor root used to derive stable module-relative execution targets. Default: {@code Maven execution root}. */
	@Parameter(property = "smartTestPicker.reactorRoot") private File reactorRoot;
	@Parameter(defaultValue = "${plugin}", readonly = true, required = true) private PluginDescriptor pluginDescriptor;
	@Component private RepositorySystem repositorySystem;
	@Component private org.apache.maven.plugin.MavenPluginManager pluginManager;
	@Component private org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator executionPlans;
	@Component private org.apache.maven.lifecycle.internal.LifecycleStarter lifecycleStarter;
	/** Additional narrowing filter applied after the provider's effective test selection. Default: {@code unset (no additional narrowing)}. */
	@Parameter(property = "smartTestPicker.testFilter") private String testFilter;
	/** Override for derived ASM package prefixes; explicit blank or malformed values are rejected. Default: {@code unset (derive from compiled production sources)}. */
	@Parameter(property = "smartTestPicker.asm.includes") private String asmIncludes;
	/** Comma-separated package prefixes excluded from ASM instrumentation within the production boundary. Default: {@code unset (no additional exclusions)}. */
	@Parameter(property = "smartTestPicker.asm.excludes") private String asmExcludes;
	@Parameter(defaultValue = "${env.STP_MAPPING_COLLECTOR}", readonly = true) private String expectedCollector;
	/** Destination for the accepted reactor shard fragment; stale output is invalidated before preparation. Default: {@code unset}. */
	@Parameter(property = "smartTestPicker.fragmentOutput") private File aggregateOutput;
	/** Destination for the combined reactor execution evidence. Default: {@code unset}. */
	@Parameter(property = "smartTestPicker.evidenceOutput") private File aggregateEvidenceOutput;

	@Override public void execute() throws MojoExecutionException {
		MavenMappingVersion.requireSupported("prepare-reactor-executable-mapping", runtimeInformation);
		try {
			// A failed dependency prevents the aggregate goal from running its own invalidation.
			if (aggregateOutput != null) Files.deleteIfExists(aggregateOutput.toPath());
			if (aggregateEvidenceOutput != null) Files.deleteIfExists(aggregateEvidenceOutput.toPath());
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
				var selected = StpMavenLifecycleParticipant.selectedCollector(entry.getValue(), session.getUserProperties(), 3);
				// A project property or CLI override must not bypass the orchestrator's frozen collector.
				if (expectedCollector != null && !expectedCollector.isBlank()
						&& selected != StpCollectorSelection.parse(expectedCollector))
					throw new IllegalArgumentException("Mapping collector mismatch: manifest=" + expectedCollector
							+ ", effective=" + selected + ", module=" + entry.getValue().getId());
				if (!partitioned.get(entry.getKey()).isEmpty()
						&& StpMavenLifecycleParticipant.selectedCollector(entry.getValue(), session.getUserProperties(), 3)
						== StpCollectorSelection.ASM) {
					AsmMappingForkGuard.validate(entry.getValue(), session, executionType);
				}
			}
			boolean needsAgent = modules.entrySet().stream().anyMatch(entry -> !partitioned.get(entry.getKey()).isEmpty()
					&& StpMavenLifecycleParticipant.selectedCollector(entry.getValue(), session.getUserProperties(), 3) == StpCollectorSelection.ASM);
			boolean asmMapping = modules.values().stream().anyMatch(module ->
					StpMavenLifecycleParticipant.selectedCollector(module, session.getUserProperties(), 3) == StpCollectorSelection.ASM);
			// Preserve an explicitly blank CLI value so it cannot silently request derivation.
			String requestedIncludes = session.getUserProperties().getProperty("smartTestPicker.asm.includes",
					asmIncludes == null ? project.getProperties().getProperty("smartTestPicker.asm.includes") : asmIncludes);
			// Explicit scope is validated before compilation; derived scope needs compiled classes.
			if (asmMapping) AsmMappingAttachment.prefixes(asmExcludes, false);
			var scope = asmMapping && requestedIncludes != null
					? MavenAsmScope.resolve(session, reactorProjects, requestedIncludes, asmExcludes) : null;
			File agent = needsAgent ? resolveAgent() : null;
			MavenJacocoMappingIsolation.install(session, reactorProjects, getLog());
			if (!assignment.tests().isEmpty()) {
				compileForSelection();
				if (needsAgent && requestedIncludes == null) {
					scope = MavenAsmScope.resolve(session, reactorProjects, null, asmExcludes);
				}
				var selection = new MavenProviderSelection(session, pluginManager, executionPlans, repositorySystem, getLog()::debug);
				var violations = new java.util.ArrayList<String>();
				for (var entry : modules.entrySet()) {
					var assigned = partitioned.get(entry.getKey());
					if (assigned.isEmpty()) continue;
					var effective = selection.discover(entry.getValue(), executionType, executionId, entry.getKey().toString());
					if (testFilter != null && !testFilter.isBlank()) effective.removeIf(MavenSurefireTestFilter.parse(testFilter).negate());
					var outside = new java.util.TreeSet<>(assigned);
					outside.removeAll(effective);
					if (!outside.isEmpty()) violations.add(entry.getKey() + " assigned identities outside effective provider selection: " + outside);
					else {
						try {
							com.sap.oss.smarttestpicker.selector.MethodNameAssignment.requireCompleteOverloads(
									entry.getKey().toString(), assigned, effective);
						} catch (IllegalArgumentException failure) { violations.add(failure.getMessage()); }
					}
				}
				if (!violations.isEmpty()) throw new IllegalArgumentException(String.join("\n", violations));
			}
			if (scope != null) getLog().info("ASM mapping scope includes=" + scope.includes() + "; excludes=" + scope.excludes()
					+ "; productionSources=" + scope.sources());
			for (var entry : modules.entrySet()) {
				var collector = StpMavenLifecycleParticipant.selectedCollector(entry.getValue(), session.getUserProperties(), 3);
				var backend = MavenCoverageCollectorBackend.selected(collector);
				configure(entry.getValue(), entry.getKey(), partitioned.get(entry.getKey()), executionType, executionId, collector);
				if (backend == MavenCoverageCollectorBackend.ASM && !partitioned.get(entry.getKey()).isEmpty()) {
					AsmMappingAttachment.attach(entry.getValue(), session, executionType, agent, entry.getKey(),
							assignment.revision().value(), assignment.shardId().value(), scope);
				}
			}
		} catch (Exception failure) {
			throw new MojoExecutionException("Cannot prepare schema-v3 Maven mapping: " + failure.getMessage(), failure);
		}
	}

	private void compileForSelection() throws Exception {
		// A CLI preparation goal may precede test-compile. Compile the complete active reactor
		// before checking any target, so a later module cannot fail validation after earlier tests ran.
		var request = org.apache.maven.execution.DefaultMavenExecutionRequest.copy(session.getRequest());
		request.setGoals(List.of("test-compile"));
		request.setStartTime(session.getStartTime());
		var result = new org.apache.maven.execution.DefaultMavenExecutionResult();
		var compilation = new MavenSession(session.getContainer(), session.getRepositorySession(), request, result);
		compilation.setProjects(session.getProjects());
		compilation.setAllProjects(session.getAllProjects());
		compilation.setProjectDependencyGraph(session.getProjectDependencyGraph());
		compilation.setProjectMap(session.getProjectMap());
		compilation.setCurrentProject(session.getCurrentProject());
		getLog().info("Compiling reactor test classes before validating executable assignment selection");
		lifecycleStarter.execute(compilation);
		if (result.hasExceptions()) throw new IllegalArgumentException("Cannot compile reactor for assignment selection", result.getExceptions().get(0));
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
			String executionType, String executionId, StpCollectorSelection collector) throws Exception {
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
		String suffix = MavenExecutionTargetResolver.outputSuffix(target);
		File output = new File(module.getBuild().getDirectory(),
				"stp/selected-tests-" + provider + "-v3" + suffix + ".txt");
		Files.createDirectories(output.getParentFile().toPath());
		List<String> patterns = tests.isEmpty() ? List.of("**/__stp_no_assigned_tests__*.java")
				: tests.stream().map(test -> MavenMethodIncludes.include(test.className(), test.methodName(), false)).distinct().toList();
		Files.write(output.toPath(), patterns);
		String selectedExecution = executionId == null || executionId.isBlank()
				? (provider.equals("surefire") ? "default-test" : "default-integration-test") : executionId;
		pinAssignedMethods(plugin, selectedExecution, String.join(",", patterns));
		String otherProvider = provider.equals("surefire") ? "failsafe" : "surefire";
		Plugin other = module.getPlugin("org.apache.maven.plugins:maven-" + otherProvider + "-plugin");
		if (other != null) pinAssignedMethods(other, "", "__stp_no_assigned_tests__");
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
		module.getProperties().setProperty("smartTestPicker.moduleDiagnosticOutput",
				new File(module.getBuild().getDirectory(), "stp/asm-agent-diagnostic" + suffix + ".json").getAbsolutePath());
		if (collector == StpCollectorSelection.ASM) {
			// Scope cleanup to this prepared target; other provider outputs must survive.
			for (String name : List.of("moduleFragmentOutput", "moduleEvidenceOutput", "moduleDiagnosticOutput"))
				Files.deleteIfExists(Path.of(module.getProperties().getProperty("smartTestPicker." + name)));
		}
	}

	private static void pinAssignedMethods(Plugin plugin, String selectedExecution, String patterns) {
		// Exact method names take precedence over literal POM includes/test/includesFile.
		// Original excludes have already participated in the effective-selection validation;
		// tags and engine filters remain in the provider configuration and still apply.
		Xpp3Dom base = plugin.getConfiguration() instanceof Xpp3Dom value ? new Xpp3Dom(value) : new Xpp3Dom("configuration");
		setTestPattern(base, patterns);
		plugin.setConfiguration(base);
		for (var execution : plugin.getExecutions()) {
			Xpp3Dom config = execution.getConfiguration() instanceof Xpp3Dom value ? new Xpp3Dom(value) : new Xpp3Dom("configuration");
			setTestPattern(config, execution.getId().equals(selectedExecution) ? patterns : "__stp_no_assigned_tests__");
			execution.setConfiguration(config);
		}
	}

	private static void setTestPattern(Xpp3Dom configuration, String pattern) {
		Xpp3Dom test = configuration.getChild("test");
		if (test == null) { test = new Xpp3Dom("test"); configuration.addChild(test); }
		test.setValue(pattern);
		// A literal provider configuration overrides its user property, including for suppressed executions.
		Xpp3Dom failIfNoSpecifiedTests = configuration.getChild("failIfNoSpecifiedTests");
		if (failIfNoSpecifiedTests == null) {
			failIfNoSpecifiedTests = new Xpp3Dom("failIfNoSpecifiedTests");
			configuration.addChild(failIfNoSpecifiedTests);
		}
		failIfNoSpecifiedTests.setValue("false");
	}

	private static void pinSingleFork(Xpp3Dom configuration) {
		// Freeze the validated settings so later property-producing goals cannot change them.
		for (String name : List.of("forkCount", "reuseForks")) {
			Xpp3Dom child = configuration.getChild(name);
			if (child == null) {
				child = new Xpp3Dom(name);
				configuration.addChild(child);
			}
			child.setValue(name.equals("forkCount") ? "1" : "true");
		}
	}

	private static String suppressAll(MavenProject module, String provider, String suffix) throws Exception {
		File file = new File(module.getBuild().getDirectory(), "stp/suppress-all-" + provider + "-v3" + suffix + ".txt");
		Files.write(file.toPath(), List.of("**/*"));
		return file.getAbsolutePath();
	}
}
