// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;

/** Called only by mapping preparation after fork and scope validation. */
final class AsmMappingAttachment {
	private AsmMappingAttachment() { }

	static String prefixes(String configured, boolean required) {
		String name = "smartTestPicker.asm." + (required ? "includes" : "excludes");
		if (configured == null || configured.isBlank()) {
			if (required) throw new IllegalArgumentException(name + " must contain explicit package prefixes for ASM mapping");
			return "";
		}
		var prefixes = new TreeSet<String>();
		for (String raw : configured.split(",", -1)) {
			String prefix = raw.trim().replace('/', '.');
			if (!prefix.endsWith(".")) prefix += ".";
			if (!prefix.matches("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+"))
				throw new IllegalArgumentException(name + " contains an empty or malformed package prefix");
			prefixes.add(prefix);
		}
		return String.join(",", prefixes);
	}

	static void attach(MavenProject module, MavenSession session, String executionType, File agent,
			ExecutionTarget target, String revision, String shardId, MavenAsmScope scope) throws Exception {
		String provider = executionType == null || executionType.isBlank() ? "surefire" : executionType;
		var plugin = module.getBuildPlugins().stream().filter(p -> p.getArtifactId().equals("maven-" + provider + "-plugin"))
				.findFirst().orElseThrow();
		String fragment = module.getProperties().getProperty("smartTestPicker.moduleFragmentOutput");
		String diagnostic = module.getProperties().getProperty("smartTestPicker.moduleDiagnosticOutput");
		var sources = java.nio.file.Path.of(fragment).resolveSibling("asm-production-sources" + MavenExecutionTargetResolver.outputSuffix(target) + ".txt");
		java.nio.file.Files.write(sources, scope.sources().stream().map(p -> safe(p.toString())).toList());
		String runId = UUID.nameUUIDFromBytes((revision + "\n" + shardId + "\n" + target).getBytes(StandardCharsets.UTF_8)).toString();
		String argument = "-javaagent:" + safe(agent.getAbsolutePath()) + "=output=" + safe(diagnostic)
				+ ";fragmentOutput=" + safe(fragment) + ";schemaVersion=3;revision=" + safe(revision)
				+ ";shardId=" + safe(shardId) + ";executionTarget=" + safe(target.toString()) + ";runId=" + runId
				+ ";includes=" + safe(scope.includes()) + (scope.excludes().isEmpty() ? "" : ";excludes=" + safe(scope.excludes()))
				+ ";productionSourcesFile=" + safe(sources.toAbsolutePath().toString()) + ";requireInstrumentation=true";
		MavenSession evaluationSession = session.clone(); evaluationSession.setCurrentProject(module);
		var evaluator = new PluginParameterExpressionEvaluator(evaluationSession, new MojoExecution(plugin, "test", "default"));
		Xpp3Dom base = (Xpp3Dom) plugin.getConfiguration();
		String baseLine = argLine(base);
		if (baseLine == null) baseLine = session.getUserProperties().getProperty("argLine",
				session.getSystemProperties().getProperty("argLine", "@{argLine}"));
		// Supply the empty late-expansion property when no project goal/agent writes it.
		// Never copy a user argLine containing @{argLine} into that same property.
		module.getProperties().putIfAbsent("argLine", "");
		for (var execution : plugin.getExecutions()) {
			Xpp3Dom config = (Xpp3Dom) execution.getConfiguration();
			String own = argLine(config);
			setArgLine(config, compose(own == null ? baseLine : own, evaluator, argument));
		}
		setArgLine(base, compose(baseLine, evaluator, argument));
	}

	private static String compose(String original, PluginParameterExpressionEvaluator evaluator, String agent) throws Exception {
		// Do not consume JaCoCo's property before prepare-agent runs. Keep existing agents
		// and literal options first; only the STP agent is appended.
		String late = original.replace("${argLine}", "@{argLine}");
		Object evaluated = evaluator.evaluate(late);
		String result = evaluated == null ? late : evaluated.toString();
		if (result.contains("${")) throw new IllegalArgumentException("Unresolved property in ASM mapping argLine");
		return result + " \"" + agent + "\"";
	}

	private static String argLine(Xpp3Dom config) {
		var child = config.getChild("argLine"); return child == null ? null : child.getValue();
	}
	private static void setArgLine(Xpp3Dom config, String value) {
		var child = config.getChild("argLine");
		if (child == null) { child = new Xpp3Dom("argLine"); config.addChild(child); }
		child.setValue(value);
	}
	private static String safe(String value) {
		if (value == null || value.indexOf(';') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
				|| value.indexOf('\r') >= 0 || value.contains("${") || value.contains("@{"))
			throw new IllegalArgumentException("Unsupported delimiter in ASM mapping agent configuration");
		return value;
	}
}
