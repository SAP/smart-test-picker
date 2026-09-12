// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

record AgentConfiguration(Path output, Path fragmentOutput, int schemaVersion, String revision, String shardId,
		com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget executionTarget,
		List<String> includes, List<String> excludes, String runId, boolean debug, boolean instrumentationEnabled) {
	private static final List<String> MANDATORY_EXCLUDES = List.of("java.", "javax.", "jakarta.", "jdk.",
			"sun.", "org.junit.", "org.springframework.", "org.hibernate.", "org.mockito.", "net.bytebuddy.",
			"org.jacoco.", "com.sap.oss.smarttestpicker.");
	private static final Set<String> KEYS = Set.of("output", "fragmentOutput", "schemaVersion", "revision", "shardId", "executionTarget", "includes",
			"excludes", "runId", "debug", "instrumentation");
	private static final Pattern PREFIX = Pattern.compile("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+");

	static boolean isMandatoryExclusion(String prefix) {
		return MANDATORY_EXCLUDES.contains(prefix);
	}

	public AgentConfiguration {
		if (output == null) throw new NullPointerException("output");
		boolean anyFragment = fragmentOutput != null || revision != null || shardId != null || executionTarget != null;
		if (anyFragment && (fragmentOutput == null || revision == null || revision.isBlank()
				|| shardId == null || shardId.isBlank())) {
			throw new IllegalArgumentException("fragmentOutput, revision, and shardId must be supplied together");
		}
		if (schemaVersion != 2 && schemaVersion != 3)
			throw new IllegalArgumentException("Unsupported runtime schema version: " + schemaVersion);
		if (schemaVersion == 3 && fragmentOutput != null && executionTarget == null)
			throw new IllegalArgumentException("Schema-v3 mapping requires an execution target");
		if (schemaVersion == 2 && executionTarget != null)
			throw new IllegalArgumentException("Schema-v2 mapping does not accept an execution target");
		includes = List.copyOf(includes);
		excludes = List.copyOf(excludes);
		if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
	}

	/** Arguments are semicolon-separated; prefix lists inside a value are comma-separated. */
	public static AgentConfiguration parse(String arguments) {
		Map<String, String> values = new LinkedHashMap<>();
		if (arguments != null && !arguments.isBlank()) {
			for (String argument : arguments.split(";", -1)) {
				int equals = argument.indexOf('=');
				if (equals <= 0) throw new IllegalArgumentException("malformed agent argument: " + safeKey(argument));
				String key = argument.substring(0, equals).trim();
				String value = argument.substring(equals + 1).trim();
				if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown agent argument: " + key);
				if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate agent argument: " + key);
				if (value.isEmpty()) throw new IllegalArgumentException(key + " must not be empty");
			}
		}

		Path output;
		Path fragmentOutput = null;
		try {
			output = Path.of(values.getOrDefault("output", "stp-agent-output.json"));
			if (values.containsKey("fragmentOutput")) fragmentOutput = Path.of(values.get("fragmentOutput"));
		} catch (InvalidPathException invalid) {
			throw new IllegalArgumentException("output is not a valid path", invalid);
		}
		List<String> includes = values.containsKey("includes")
				? prefixes(values.get("includes"), "includes") : List.of();
		TreeSet<String> exclusions = new TreeSet<>(MANDATORY_EXCLUDES);
		if (values.containsKey("excludes")) exclusions.addAll(prefixes(values.get("excludes"), "excludes"));
		String runId = values.getOrDefault("runId", "run-1");
		if (runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
		String debugValue = values.getOrDefault("debug", "false");
		if (!debugValue.equals("true") && !debugValue.equals("false")) {
			throw new IllegalArgumentException("debug must be true or false");
		}
		String instrumentation = values.getOrDefault("instrumentation", "on");
		if (!instrumentation.equals("on") && !instrumentation.equals("off")) {
			throw new IllegalArgumentException("instrumentation must be on or off");
		}
		int schemaVersion;
		try { schemaVersion = Integer.parseInt(values.getOrDefault("schemaVersion", "2")); }
		catch (NumberFormatException invalid) { throw new IllegalArgumentException("schemaVersion must be an integer", invalid); }
		com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget target = null;
		if (values.containsKey("executionTarget")) {
			try { target = com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget.parse(values.get("executionTarget")); }
			catch (IllegalArgumentException invalid) {
				throw new IllegalArgumentException("Malformed execution target: " + values.get("executionTarget"), invalid);
			}
		}
		return new AgentConfiguration(output, fragmentOutput, schemaVersion, values.get("revision"), values.get("shardId"), target,
				includes, new ArrayList<>(exclusions), runId,
				Boolean.parseBoolean(debugValue), instrumentation.equals("on"));
	}

	private static List<String> prefixes(String value, String key) {
		TreeSet<String> normalized = new TreeSet<>();
		Arrays.stream(value.split(",", -1)).forEach(raw -> {
			String prefix = raw.trim().replace('/', '.');
			if (!prefix.endsWith(".")) prefix += ".";
			if (!PREFIX.matcher(prefix).matches()) {
				throw new IllegalArgumentException(key + " contains malformed package prefix");
			}
			normalized.add(prefix);
		});
		if (normalized.isEmpty()) throw new IllegalArgumentException(key + " must contain a prefix");
		return List.copyOf(normalized);
	}

	private static String safeKey(String argument) {
		int equals = argument.indexOf('=');
		String candidate = (equals < 0 ? argument : argument.substring(0, equals)).trim();
		return candidate.isEmpty() ? "<empty>" : candidate;
	}
}
