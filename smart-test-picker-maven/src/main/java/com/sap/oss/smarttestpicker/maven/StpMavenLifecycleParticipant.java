// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
			Map<ExecutionTarget, MavenProject> modules) throws IOException
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
			Path directory = Path.of(project.getBuild().getDirectory(), "stp"); Files.createDirectories(directory);
			Path none = writeIncludes(directory.resolve("selected-none.includes"), Set.of());
			Path generalIncludes = writeIncludes(directory.resolve("selected-tests.includes"), general);
			Properties properties = project.getProperties();
			properties.setProperty("surefire.includesFile", (general.isEmpty() ? none : generalIncludes).toString());
			properties.setProperty("failsafe.includesFile", (general.isEmpty() ? none : generalIncludes).toString());
			properties.setProperty("surefire.failIfNoSpecifiedTests", "false");
			properties.setProperty("failsafe.failIfNoSpecifiedTests", "false");
			for (var assignment : exact.entrySet())
			{
				QualifiedTarget target = QualifiedTarget.parse(assignment.getKey());
				Path includes = writeIncludes(directory.resolve("selected-" + safe(target.type + "-" + target.executionId)
						+ ".includes"), assignment.getValue());
				Path evidence = directory.resolve("evidence-" + safe(assignment.getKey().replace('@', '-')));
				configureExecution(project, target, includes, evidence, "maven:" + module + "@" + assignment.getKey());
			}
		}
	}

	private static Path writeIncludes(Path output, Set<TestIdentity> identities) throws IOException
	{
		List<String> lines = identities.isEmpty() ? List.of("**/__stp_no_tests__*.java") : identities.stream()
				.map(value -> value.className().replace('.', '/') + ".java#" + value.methodName()).toList();
		Files.write(output, lines); return output;
	}

	private static void configureExecution(MavenProject project, QualifiedTarget target, Path includes, Path evidence,
			String canonicalTarget)
	{
		String artifactId = "maven-" + target.type + "-plugin";
		Plugin plugin = project.getBuildPlugins().stream().filter(value -> artifactId.equals(value.getArtifactId()))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("Qualified Maven target plugin is absent: " + artifactId));
		PluginExecution execution = plugin.getExecutions().stream()
				.filter(value -> target.executionId.equals(value.getId())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Qualified Maven execution is absent: " + target.executionId));
		Xpp3Dom configuration = execution.getConfiguration() instanceof Xpp3Dom existing
				? new Xpp3Dom(existing) : new Xpp3Dom("configuration");
		set(configuration, "includesFile", includes.toString());
		set(configuration, "failIfNoSpecifiedTests", "false");
		Xpp3Dom properties = configuration.getChild("systemPropertyVariables");
		if (properties == null) { properties = new Xpp3Dom("systemPropertyVariables"); configuration.addChild(properties); }
		set(properties, "stp.exec.dir", evidence.toString());
		set(properties, "smartTestPicker.executionTarget", canonicalTarget);
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
