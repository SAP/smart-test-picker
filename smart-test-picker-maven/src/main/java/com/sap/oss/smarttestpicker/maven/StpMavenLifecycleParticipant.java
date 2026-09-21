// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlanMode;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutionPlanCodec;

/** Maven extension entry point for plan validation, reactor restriction and execution verification. */
@Named
@Singleton
public final class StpMavenLifecycleParticipant extends AbstractMavenLifecycleParticipant
{
	private ExecutionPlan plan;
	private Map<ExecutionTarget, MavenProject> selectedModules = Map.of();
	private List<String> selectedModuleIds = List.of();
	private Path resultPath;
	private String invocationId = "unspecified";
	private boolean active;

	@Override public void afterProjectsRead(MavenSession session) throws MavenExecutionException
	{
		active = false; plan = null; selectedModules = Map.of(); selectedModuleIds = List.of();
		resultPath = null; invocationId = "unspecified";
		if (!"true".equalsIgnoreCase(property("stp.maven.execution.management"))) return;
		active = true;
		String configured = property("stp.execution.plan");
		if (configured == null) return;
		try
		{
			plan = new ExecutionPlanCodec().deserialize(Files.readAllBytes(Path.of(configured)));
			resultPath = requiredPath("stp.execution.result");
			String configuredInvocation = property("stp.invocation.id");
			if (configuredInvocation != null) invocationId = configuredInvocation;
			System.out.println("[STP] Execution plan: " + Path.of(configured).toAbsolutePath().normalize());
			System.out.println("[STP] Execution mode: " + plan.mode());
			if (plan.mode() == ExecutionPlanMode.RUN_ALL)
			{
				System.out.println("[STP] Full suite requested; Maven reactor and test configuration are unchanged");
				return;
			}
			if (plan.selectsNothing())
				throw new IllegalArgumentException("SELECT plan is empty; smartTestPicker must skip the Maven body for NONE");
			ensureNoConflictingReactorSelection(session);
			Map<String, MavenProject> reactor = reactor(session);
			Map<ExecutionTarget, MavenProject> resolved = new TreeMap<>();
			for (ExecutableTestIdentity identity : plan.tests())
			{
				if (identity.target().buildTool() != BuildTool.MAVEN)
					throw new IllegalArgumentException("Maven execution rejects non-Maven target: " + identity.target());
				String moduleId = new MavenExecutionTargetResolver().reactorModuleId(identity.target());
				MavenProject project = reactor.get(moduleId);
				if (project == null) throw new IllegalArgumentException("Execution target is absent from active Maven reactor: " + identity.target());
				resolved.put(identity.target(), project);
			}
			selectedModules = Map.copyOf(resolved);
			selectedModuleIds = resolved.keySet().stream().map(new MavenExecutionTargetResolver()::reactorModuleId)
					.distinct().toList();
			MavenExecutionEvidence.clean(selectedModules);
			enableRuntimeEvidence(session);
			activateFilters(plan.tests(), selectedModules);
			Set<MavenProject> owningProjects = Set.copyOf(resolved.values());
			List<MavenProject> narrowed = session.getProjects().stream().filter(owningProjects::contains).toList();
			if (narrowed.size() != owningProjects.size()) throw new IllegalArgumentException("Ambiguous Maven reactor module resolution");
			session.setProjects(narrowed);
			System.out.println("[STP] Selected Maven reactor modules: " + String.join(",", selectedModuleIds));
		}
		catch (Exception failure)
		{
			writePreparationFailure(failure);
			throw new MavenExecutionException("[STP] selective Maven execution preparation failed: " + failure.getMessage(), failure);
		}
	}

	private static void enableRuntimeEvidence(MavenSession session)
	{
		for (String name : List.of("junit.platform.listeners.autodetection.enabled",
				"junit.jupiter.extensions.autodetection.enabled"))
		{
			String configured = session.getUserProperties().getProperty(name);
			if (configured != null && !"true".equalsIgnoreCase(configured))
				throw new IllegalArgumentException("STP managed execution conflicts with " + name + "=" + configured);
			session.getUserProperties().setProperty(name, "true");
		}
	}

	@Override public void afterSessionEnd(MavenSession session) throws MavenExecutionException
	{
		if (!active || plan == null || resultPath == null) return;
		active = false;
		boolean mavenSucceeded = session.getResult() == null || !session.getResult().hasExceptions();
		try
		{
			MavenExecutionEvidence.Verification verification;
			if (plan.mode() == ExecutionPlanMode.RUN_ALL)
				verification = new MavenExecutionEvidence.Verification(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), true);
			else verification = MavenExecutionEvidence.verify(plan, selectedModules, true);
			String summary = verification.valid() ? "execution evidence verified"
					: "execution evidence incomplete or different from plan";
			MavenExecutionEvidence.write(resultPath, invocationId, plan, selectedModuleIds, verification,
					mavenSucceeded, summary);
			System.out.println("[STP] " + summary + ": " + resultPath);
			if (mavenSucceeded && !verification.valid())
				throw new MavenExecutionException("[STP] " + summary, (java.io.File)null);
		}
		catch (MavenExecutionException failure) { throw failure; }
		catch (Exception failure)
		{
			if (mavenSucceeded) throw new MavenExecutionException("[STP] execution evidence finalization failed", failure);
			System.err.println("[STP] execution evidence finalization failed after Maven failure: " + failure.getMessage());
		}
	}

	private static void activateFilters(Set<ExecutableTestIdentity> tests,
			Map<ExecutionTarget, MavenProject> modules) throws IOException, URISyntaxException
	{
		Map<String, Set<TestIdentity>> unqualified = new TreeMap<>();
		Map<String, Map<String, Set<TestIdentity>>> qualified = new TreeMap<>();
		MavenExecutionTargetResolver resolver = new MavenExecutionTargetResolver();
		for (ExecutableTestIdentity identity : tests) {
			String module = resolver.reactorModuleId(identity.target());
			String suffix = identity.target().targetId().substring(module.length());
			if (suffix.isEmpty()) unqualified.computeIfAbsent(module, ignored -> new TreeSet<>()).add(identity.test());
			else qualified.computeIfAbsent(module, ignored -> new TreeMap<>())
					.computeIfAbsent(suffix.substring(1), ignored -> new TreeSet<>()).add(identity.test());
		}
		Map<String, MavenProject> distinctModules = new TreeMap<>();
		for (var entry : modules.entrySet())
			distinctModules.put(new MavenExecutionTargetResolver().reactorModuleId(entry.getKey()), entry.getValue());
		for (var entry : distinctModules.entrySet())
		{
			MavenProject project = entry.getValue();
			String module = entry.getKey();
			Set<TestIdentity> general = unqualified.getOrDefault(module, Set.of());
			Map<String, Set<TestIdentity>> exact = qualified.getOrDefault(module, Map.of());
			Path evidenceDirectory = Path.of(project.getBuild().getDirectory(), "stp");
			Path filterDirectory = Files.createTempDirectory("stp-maven-selection-" + safe(module) + "-");
			filterDirectory.toFile().deleteOnExit();
			Path none = writeIncludes(filterDirectory.resolve("selected-none.includes"), Set.of());
			Path generalIncludes = writeIncludes(filterDirectory.resolve("selected-tests.includes"), general);
			Properties properties = project.getProperties();
			Path generalEvidence = Path.of(project.getBuild().getDirectory(), "stp", "evidence-selected");
			properties.setProperty("surefire.includesFile", (general.isEmpty() ? none : generalIncludes).toString());
			properties.setProperty("failsafe.includesFile", (general.isEmpty() ? none : generalIncludes).toString());
			properties.setProperty("surefire.failIfNoSpecifiedTests", "false");
			properties.setProperty("failsafe.failIfNoSpecifiedTests", "false");
			configureRuntime(project, "surefire", generalEvidence, "maven:" + module);
			configureRuntime(project, "failsafe", generalEvidence, "maven:" + module);
			for (var assignment : exact.entrySet())
			{
				QualifiedTarget target = QualifiedTarget.parse(assignment.getKey());
				Path includes = writeIncludes(filterDirectory.resolve("selected-" + safe(target.type + "-" + target.executionId)
						+ ".includes"), assignment.getValue());
				Path evidence = evidenceDirectory.resolve("evidence-" + safe(assignment.getKey().replace('@', '-')));
				configureExecution(project, target, includes, evidence, "maven:" + module + "@" + assignment.getKey());
			}
		}
	}

	private static void configureRuntime(MavenProject project, String type, Path evidence,
			String canonicalTarget) throws URISyntaxException
	{
		Properties properties = project.getProperties();
		String runtime = runtimeClasspath().stream().map(Path::toString)
				.collect(java.util.stream.Collectors.joining(","));
		String existing = properties.getProperty("maven.test.additionalClasspath");
		if (existing == null || existing.isBlank()) properties.setProperty("maven.test.additionalClasspath", runtime);
		else if (!java.util.Arrays.asList(existing.split(",")).containsAll(List.of(runtime.split(","))))
			properties.setProperty("maven.test.additionalClasspath", existing + "," + runtime);
		String artifactId = "maven-" + type + "-plugin";
		Plugin plugin = project.getBuild().getPlugins().stream().filter(value -> artifactId.equals(value.getArtifactId()))
				.findFirst().orElse(null);
		if (plugin == null)
		{
			// Surefire is supplied by Maven's default lifecycle even when it is not
			// declared in build/plugins. Add a model entry before lifecycle planning so
			// managed STP configuration also reaches that implicit execution.
			if (!"surefire".equals(type)) return;
			plugin = new Plugin();
			plugin.setGroupId("org.apache.maven.plugins");
			plugin.setArtifactId(artifactId);
			project.getBuild().addPlugin(plugin);
		}
		Xpp3Dom configuration = plugin.getConfiguration() instanceof Xpp3Dom configured
				? new Xpp3Dom(configured) : new Xpp3Dom("configuration");
		configureRuntime(configuration, evidence, canonicalTarget);
		plugin.setConfiguration(configuration);
	}

	private static void configureRuntime(Xpp3Dom configuration, Path evidence,
			String canonicalTarget) throws URISyntaxException
	{
		Xpp3Dom properties = configuration.getChild("systemPropertyVariables");
		if (properties == null) { properties = new Xpp3Dom("systemPropertyVariables"); configuration.addChild(properties); }
		set(properties, "stp.exec.dir", evidence.toAbsolutePath().normalize().toString());
		set(properties, "smartTestPicker.executionTarget", canonicalTarget);
		enableJunitAutodetection(configuration);
		Xpp3Dom classpath = configuration.getChild("additionalClasspathElements");
		if (classpath == null) { classpath = new Xpp3Dom("additionalClasspathElements"); configuration.addChild(classpath); }
		for (Path runtime : runtimeClasspath())
		{
			boolean present = java.util.Arrays.stream(classpath.getChildren("additionalClasspathElement"))
					.anyMatch(value -> runtime.toString().equals(value.getValue()));
			if (!present) { Xpp3Dom element = new Xpp3Dom("additionalClasspathElement"); element.setValue(runtime.toString()); classpath.addChild(element); }
		}
	}

	private static void enableJunitAutodetection(Xpp3Dom configuration)
	{
		Xpp3Dom properties = configuration.getChild("properties");
		if (properties == null) { properties = new Xpp3Dom("properties"); configuration.addChild(properties); }
		Xpp3Dom parameters = properties.getChild("configurationParameters");
		if (parameters == null) { parameters = new Xpp3Dom("configurationParameters"); properties.addChild(parameters); }
		String existing = parameters.getValue() == null ? "" : parameters.getValue().trim();
		String required = "junit.platform.listeners.autodetection.enabled=true\n"
				+ "junit.jupiter.extensions.autodetection.enabled=true";
		parameters.setValue(existing.isEmpty() ? required : existing + "\n" + required);
	}

	private static Set<Path> runtimeClasspath() throws URISyntaxException
	{
		Set<Path> result = new TreeSet<>();
		addResourceRoot(result, "com/sap/oss/smarttestpicker/JacocoPerTestListener.class");
		addResourceRoot(result, "META-INF/services/org.junit.platform.launcher.TestExecutionListener");
		if (result.isEmpty()) throw new IllegalStateException("Packaged STP Maven adapter is missing its execution evidence runtime");
		return result;
	}

	private static void addResourceRoot(Set<Path> result, String resource) throws URISyntaxException
	{
		URL location = StpMavenLifecycleParticipant.class.getClassLoader().getResource(resource);
		if (location == null) return;
		if ("jar".equals(location.getProtocol()))
		{
			try { result.add(Path.of(((JarURLConnection)location.openConnection()).getJarFileURL().toURI()).toAbsolutePath().normalize()); }
			catch (IOException failure) { throw new IllegalStateException("Cannot resolve packaged STP runtime", failure); }
			return;
		}
		if (!"file".equals(location.getProtocol()))
			throw new IllegalStateException("Unsupported STP runtime resource URL: " + location);
		Path root = Path.of(location.toURI());
		for (int index = 0; index < resource.split("/").length; index++) root = root.getParent();
		result.add(root.toAbsolutePath().normalize());
	}

	private static Path writeIncludes(Path output, Set<TestIdentity> identities) throws IOException
	{
		List<String> lines = identities.isEmpty() ? List.of("**/__stp_no_tests__*.java") : identities.stream()
				.map(value -> value.className().replace('.', '/') + ".java#" + value.methodName()).toList();
		Files.write(output, lines); output.toFile().deleteOnExit(); return output;
	}

	private static void configureExecution(MavenProject project, QualifiedTarget target, Path includes, Path evidence,
			String canonicalTarget) throws URISyntaxException
	{
		String artifactId = "maven-" + target.type + "-plugin";
		Plugin plugin = project.getBuild().getPlugins().stream().filter(value -> artifactId.equals(value.getArtifactId()))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("Qualified Maven target plugin is absent: " + artifactId));
		PluginExecution execution = plugin.getExecutions().stream()
				.filter(value -> target.executionId.equals(value.getId())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Qualified Maven execution is absent: " + target.executionId));
		Xpp3Dom configuration = execution.getConfiguration() instanceof Xpp3Dom existing
				? new Xpp3Dom(existing) : new Xpp3Dom("configuration");
		set(configuration, "includesFile", includes.toString());
		set(configuration, "failIfNoSpecifiedTests", "false");
		configureRuntime(configuration, evidence, canonicalTarget);
		execution.setConfiguration(configuration);
	}

	private static void set(Xpp3Dom configuration, String name, String value)
	{
		Xpp3Dom child = configuration.getChild(name);
		if (child == null) { child = new Xpp3Dom(name); configuration.addChild(child); }
		child.setValue(value);
	}

	private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_.-]", "_"); }

	private record QualifiedTarget(String type, String executionId, String profile)
	{
		static QualifiedTarget parse(String value)
		{
			String[] parts = value.split("@", -1);
			if (parts.length < 2 || parts.length > 3 || !(parts[0].equals("surefire") || parts[0].equals("failsafe"))
					|| parts[1].isBlank()) throw new IllegalArgumentException("Unsupported qualified Maven target: " + value);
			return new QualifiedTarget(parts[0], parts[1], parts.length == 3 ? parts[2] : null);
		}
	}

	private static Map<String, MavenProject> reactor(MavenSession session)
	{
		Map<String, MavenProject> result = new LinkedHashMap<>();
		MavenExecutionTargetResolver resolver = new MavenExecutionTargetResolver();
		for (MavenProject project : session.getProjects())
		{
			String id = resolver.resolve(session.getRequest().getMultiModuleProjectDirectory(), project).targetId();
			if (result.put(id, project) != null) throw new IllegalArgumentException("Duplicate Maven reactor module path: " + id);
		}
		return result;
	}

	private static void ensureNoConflictingReactorSelection(MavenSession session)
	{
		var request = session.getRequest();
		if (!request.getSelectedProjects().isEmpty() || !request.getExcludedProjects().isEmpty()
				|| request.getResumeFrom() != null || request.getMakeBehavior() != null)
			throw new IllegalArgumentException("STP selective execution conflicts with user-supplied Maven reactor selection");
	}

	private void writePreparationFailure(Exception failure)
	{
		if (resultPath == null) return;
		try
		{
			ExecutionPlan effective = plan == null
					? new ExecutionPlan(com.sap.oss.smarttestpicker.coverage.ExecutionPlanContract.VERSION,
							ExecutionPlanMode.SELECT, Set.of(), null) : plan;
			var incomplete = new MavenExecutionEvidence.Verification(Set.of(), Set.of(), Set.of(),
					effective.tests(), Set.of(), Set.of(), false);
			MavenExecutionEvidence.write(resultPath, invocationId, effective, selectedModuleIds, incomplete, false,
					"preparation failed: " + failure.getMessage());
		}
		catch (Exception ignored) { failure.addSuppressed(ignored); }
	}

	private static Path requiredPath(String name)
	{
		String value = property(name);
		if (value == null) throw new IllegalArgumentException("Missing required system property " + name);
		return Path.of(value).toAbsolutePath().normalize();
	}

	private static String property(String name)
	{
		String value = System.getProperty(name);
		return value == null || value.isBlank() ? null : value.trim();
	}
}
