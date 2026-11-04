// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Single-output ASM collection cannot combine either concurrent or successive test JVMs. */
final class AsmMappingForkGuard {

	private AsmMappingForkGuard() { }

	static void validate(MavenProject project, MavenSession session, String executionType) throws Exception {
		String provider = executionType == null || executionType.isBlank() ? "surefire" : executionType;
		String artifact = "maven-" + provider + "-plugin";
		Plugin plugin = project.getBuildPlugins().stream()
				.filter(p -> artifact.equals(p.getArtifactId()) && "org.apache.maven.plugins".equals(p.getGroupId()))
				.findFirst().orElse(null);
		if (plugin == null && project.getPluginManagement() != null) {
			plugin = project.getPluginManagement().getPlugins().stream()
					.filter(p -> artifact.equals(p.getArtifactId()) && "org.apache.maven.plugins".equals(p.getGroupId()))
					.findFirst().orElse(null);
		}
		if (plugin == null) {
			plugin = new Plugin();
			plugin.setGroupId("org.apache.maven.plugins");
			plugin.setArtifactId(artifact);
		}
		MavenSession evaluationSession = session.clone();
		evaluationSession.setCurrentProject(project);
		Xpp3Dom base = configuration(plugin.getConfiguration());
		check(project, evaluationSession, plugin, "default", base);
		// Preparation configures every execution of this provider. Validate all of them,
		// including custom execution IDs, rather than guessing which one will run later.
		for (var execution : plugin.getExecutions()) {
			Xpp3Dom effective = Xpp3Dom.mergeXpp3Dom(configuration(execution.getConfiguration()), new Xpp3Dom(base));
			check(project, evaluationSession, plugin, execution.getId(), effective);
		}
	}

	private static Xpp3Dom configuration(Object configuration) {
		return configuration instanceof Xpp3Dom dom ? new Xpp3Dom(dom) : new Xpp3Dom("configuration");
	}

	private static void check(MavenProject project, MavenSession session, Plugin plugin,
			String executionId, Xpp3Dom configuration) throws Exception {
		var evaluator = new PluginParameterExpressionEvaluator(session,
				new MojoExecution(plugin, "test", executionId));
		String forkCount = value(configuration, evaluator, "forkCount", "1");
		// CPU-relative values are deliberately rejected: the supported contract must not
		// depend on the agent's CPU count. Unknown expressions are not assumed safe.
		if (!"1".equals(forkCount)) reject(project, plugin, executionId, "forkCount", forkCount);
		String reuse = value(configuration, evaluator, "reuseForks", "true");
		if (!"true".equalsIgnoreCase(reuse)) reject(project, plugin, executionId, "reuseForks", reuse);
		String legacy = value(configuration, evaluator, "forkMode", null);
		if (legacy != null) reject(project, plugin, executionId, "forkMode", legacy);
	}

	private static String value(Xpp3Dom configuration, PluginParameterExpressionEvaluator evaluator,
			String name, String defaultValue) throws Exception {
		Xpp3Dom child = configuration.getChild(name);
		String expression = child == null || child.getValue() == null ? "${" + name + "}" : child.getValue();
		Object resolved = evaluator.evaluate(expression);
		if (resolved == null || expression.equals(resolved.toString()) && expression.contains("${")) {
			String configuredDefault = child == null ? null : child.getAttribute("default-value");
			if (configuredDefault != null) {
				Object fallback = evaluator.evaluate(configuredDefault);
				return fallback == null ? configuredDefault : fallback.toString().trim();
			}
			// Only absent parameter values use the provider default. Explicit unresolved
			// expressions are rejected conservatively, not guessed to mean one fork.
			return child == null || child.getValue() == null ? defaultValue : expression;
		}
		return resolved.toString().trim();
	}

	private static void reject(MavenProject project, Plugin plugin, String executionId,
			String setting, String value) {
		throw new IllegalArgumentException("ASM mapping requires forkCount=1 and reuseForks=true without legacy forkMode; "
				+ "unsupported " + setting + "=" + value + " in " + project.getArtifactId() + " / "
				+ plugin.getArtifactId() + " / " + executionId
				+ ". Multiple test JVMs overwrite the single ASM fragment; per-fork merging is not supported.");
	}
}
