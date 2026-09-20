// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

/** Invocation-scoped verification of authoritative STP runtime identity evidence. */
final class MavenExecutionEvidence
{
	record Verification(Set<ExecutableTestIdentity> executed, Set<ExecutableTestIdentity> failed,
			Set<ExecutableTestIdentity> nonExecuted, Set<ExecutableTestIdentity> missing,
			Set<ExecutableTestIdentity> unexpected, Set<ExecutableTestIdentity> duplicates, boolean complete)
	{
		boolean valid() { return complete && missing.isEmpty() && unexpected.isEmpty() && duplicates.isEmpty(); }
	}

	static void clean(Map<ExecutionTarget, org.apache.maven.project.MavenProject> modules) throws IOException
	{
		for (var entry : modules.entrySet())
		{
			Path directory = evidenceDirectory(entry.getKey(), entry.getValue());
			if (!Files.isDirectory(directory)) continue;
			try (var files = Files.list(directory))
			{
				for (Path file : files.filter(MavenExecutionEvidence::isIdentityArtifact).toList()) Files.deleteIfExists(file);
			}
		}
	}

	static Verification verify(ExecutionPlan plan, Map<ExecutionTarget, org.apache.maven.project.MavenProject> modules,
			boolean processCompleted) throws IOException
	{
		Set<ExecutableTestIdentity> executed = new TreeSet<>(), failed = new TreeSet<>(), nonExecuted = new TreeSet<>();
		Set<ExecutableTestIdentity> duplicates = new TreeSet<>();
		for (var entry : modules.entrySet())
		{
			Path directory = evidenceDirectory(entry.getKey(), entry.getValue());
			if (!Files.isDirectory(directory)) continue;
			try (var files = Files.list(directory))
			{
				for (Path file : files.filter(MavenExecutionEvidence::isIdentityArtifact).sorted().toList())
				{
					Properties values = new Properties();
					try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
					TestIdentity test = new TestIdentity(required(values, "className"), required(values, "methodName"),
							values.getProperty("methodParameterTypes", ""));
					ExecutableTestIdentity identity = matchTarget(plan.tests(), entry.getKey(), test);
					Set<ExecutableTestIdentity> destination = file.getFileName().toString().endsWith(".non-executed")
							? nonExecuted : executed;
					if (!destination.add(identity)) duplicates.add(identity);
					if (destination == executed && "FAIL".equals(values.getProperty("outcome"))) failed.add(identity);
				}
			}
		}
		Set<ExecutableTestIdentity> observed = new TreeSet<>(executed); observed.addAll(nonExecuted);
		Set<ExecutableTestIdentity> missing = difference(plan.tests(), observed);
		Set<ExecutableTestIdentity> unexpected = difference(observed, plan.tests());
		return new Verification(Set.copyOf(executed), Set.copyOf(failed), Set.copyOf(nonExecuted),
				Set.copyOf(missing), Set.copyOf(unexpected), Set.copyOf(duplicates), processCompleted);
	}

	static void write(Path output, String invocationId, ExecutionPlan plan, List<String> selectedModules,
			Verification verification, boolean mavenSucceeded, String message) throws IOException
	{
		JsonObject root = new JsonObject();
		root.addProperty("version", 1); root.addProperty("invocationId", invocationId);
		root.addProperty("mode", plan.mode().name());
		plan.boundRevision().ifPresent(value -> root.addProperty("revision", value.value()));
		root.addProperty("mavenSucceeded", mavenSucceeded); root.addProperty("evidenceComplete", verification.complete());
		root.addProperty("verified", verification.valid()); root.addProperty("message", message);
		root.add("selectedModules", strings(selectedModules)); root.add("planned", identities(plan.tests()));
		root.add("executed", identities(verification.executed())); root.add("failed", identities(verification.failed()));
		root.add("nonExecuted", identities(verification.nonExecuted())); root.add("missing", identities(verification.missing()));
		root.add("unexpected", identities(verification.unexpected())); root.add("duplicates", identities(verification.duplicates()));
		Files.createDirectories(output.toAbsolutePath().normalize().getParent());
		Files.writeString(output, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n",
				StandardCharsets.UTF_8);
	}

	private static ExecutableTestIdentity matchTarget(Set<ExecutableTestIdentity> planned, ExecutionTarget module,
			TestIdentity test)
	{
		List<ExecutableTestIdentity> matches = planned.stream()
				.filter(value -> value.target().equals(module) && value.test().equals(test)).toList();
		if (matches.size() == 1) return matches.get(0);
		if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous execution evidence ownership: " + test);
		return new ExecutableTestIdentity(module, test);
	}

	private static Path evidenceDirectory(ExecutionTarget target, org.apache.maven.project.MavenProject project)
	{
		String module = new MavenExecutionTargetResolver().reactorModuleId(target);
		String suffix = target.targetId().substring(module.length());
		if (!suffix.isEmpty()) return Path.of(project.getBuild().getDirectory(), "stp",
				"evidence-" + suffix.substring(1).replace('@', '-').replaceAll("[^A-Za-z0-9_.-]", "_"));
		String configured = project.getProperties().getProperty("stp.exec.dir");
		return configured == null || configured.isBlank()
				? Path.of(project.getBuild().getDirectory(), "jacoco") : Path.of(configured);
	}

	private static boolean isIdentityArtifact(Path file)
	{
		String name = file.getFileName().toString();
		return name.startsWith("session_") && (name.endsWith(".identity") || name.endsWith(".non-executed"));
	}

	private static String required(Properties values, String name)
	{
		String value = values.getProperty(name);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing evidence field " + name);
		return value;
	}

	private static Set<ExecutableTestIdentity> difference(Set<ExecutableTestIdentity> left,
			Set<ExecutableTestIdentity> right)
	{
		Set<ExecutableTestIdentity> result = new TreeSet<>(left); result.removeAll(right); return result;
	}

	private static JsonArray identities(Set<ExecutableTestIdentity> values)
	{
		JsonArray result = new JsonArray(); values.stream().sorted().forEach(value -> result.add(value.toString())); return result;
	}

	private static JsonArray strings(List<String> values)
	{
		JsonArray result = new JsonArray(); values.forEach(result::add); return result;
	}
}
