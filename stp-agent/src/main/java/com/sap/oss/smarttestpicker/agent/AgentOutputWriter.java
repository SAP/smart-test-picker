// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;

import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class AgentOutputWriter {
	private AgentOutputWriter() {
	}

	static void validate(Path output) {
		Path absolute = output.toAbsolutePath();
		if (Files.exists(absolute) && Files.isDirectory(absolute)) {
			throw new IllegalArgumentException("output must be a file, not a directory");
		}
		Path parent = absolute.getParent();
		if (parent != null && (!Files.isDirectory(parent) || !Files.isWritable(parent))) {
			throw new IllegalArgumentException("output parent must be an existing writable directory");
		}
	}

	static void write(Path output, String json) throws IOException {
		Files.writeString(output, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	/** Removes any result from an earlier collector execution before current production begins. */
	static void invalidate(Path output) throws IOException {
		Files.deleteIfExists(output.toAbsolutePath());
	}

	/** Publishes validated bytes through a sibling temporary file and replaces the target atomically when supported. */
	static boolean publish(Path output, byte[] bytes) throws IOException {
		Path target = output.toAbsolutePath();
		Path parent = target.getParent();
		if (parent == null) throw new IOException("fragment output has no parent");
		Path temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
		try {
			Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				return true;
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
				return false;
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	static void write(Path output, String runId, String jvmId, AgentConfiguration configuration,
			AgentMetrics.Snapshot metrics, List<String> errors, Map<Long, List<MethodIdentity>> catalog,
			Map<Long, Long> hits, String runtimeJson) throws IOException {
		String shell = json(runId, jvmId, configuration, metrics, errors, catalog, hits, null);
		String marker = "  \"runtimeEvents\": null\n}";
		int markerIndex = shell.lastIndexOf(marker);
		if (markerIndex < 0) throw new IllegalStateException("runtime event marker missing");
		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			writer.write(shell, 0, markerIndex);
			writer.write("  \"runtimeEvents\": ");
			writer.write(runtimeJson == null ? "null" : runtimeJson.stripTrailing());
			writer.write("\n}\n");
		}
	}

	static String json(String runId, String jvmId, AgentConfiguration configuration, AgentMetrics.Snapshot metrics,
			List<String> errors) {
		return json(runId, jvmId, configuration, metrics, errors, Map.of(), Map.of(), null);
	}

	static String json(String runId, String jvmId, AgentConfiguration configuration, AgentMetrics.Snapshot metrics,
			List<String> errors, Map<Long, List<MethodIdentity>> catalog, Map<Long, Long> hits, String runtimeJson) {
		List<String> sortedErrors = new ArrayList<>(errors);
		Collections.sort(sortedErrors);
		StringBuilder json = new StringBuilder(1024);
		json.append("{\n");
		field(json, 1, "schemaVersion", "agent-shell-1", true);
		field(json, 1, "collector", "ASM", true);
		field(json, 1, "runId", runId, true);
		field(json, 1, "jvmId", jvmId, true);
		json.append("  \"configuration\": {\n");
		field(json, 2, "output", configuration.output().toString(), true);
		field(json, 2, "fragmentOutput", configuration.fragmentOutput() == null ? "" : configuration.fragmentOutput().toString(), true);
		field(json, 2, "revision", configuration.revision() == null ? "" : configuration.revision(), true);
		field(json, 2, "shardId", configuration.shardId() == null ? "" : configuration.shardId(), true);
		arrayField(json, 2, "includes", configuration.includes(), true);
		arrayField(json, 2, "excludes", configuration.excludes(), true);
		field(json, 2, "debug", Boolean.toString(configuration.debug()), true, false);
		field(json, 2, "instrumentation", configuration.instrumentationEnabled() ? "on" : "off", false);
		json.append("  },\n");
		json.append("  \"metrics\": {\n");
		number(json, 2, "classesSeen", metrics.classesSeen(), true);
		number(json, 2, "classesIncluded", metrics.classesIncluded(), true);
		number(json, 2, "classesExcluded", metrics.classesExcluded(), true);
		number(json, 2, "classesIgnored", metrics.classesIgnored(), true);
		number(json, 2, "transformationErrors", metrics.transformationErrors(), true);
		number(json, 2, "transformerTotalNanos", metrics.transformerTotalNanos(), true);
		number(json, 2, "agentStartNanos", metrics.agentStartNanos(), true);
		number(json, 2, "classLoaderPresent", metrics.classLoaderPresent(), true);
		number(json, 2, "classLoaderMissing", metrics.classLoaderMissing(), true);
		number(json, 2, "protectionDomainPresent", metrics.protectionDomainPresent(), true);
		number(json, 2, "protectionDomainMissing", metrics.protectionDomainMissing(), true);
		number(json, 2, "classesTransformed", metrics.classesTransformed(), true);
		number(json, 2, "methodsConsidered", metrics.methodsConsidered(), true);
		number(json, 2, "methodsInstrumented", metrics.methodsInstrumented(), true);
		number(json, 2, "methodsSkipped", metrics.methodsSkipped(), true);
		number(json, 2, "alreadyInstrumentedClasses", metrics.alreadyInstrumentedClasses(), true);
		number(json, 2, "methodIdCollisions", metrics.methodIdCollisions(), true);
		number(json, 2, "rawMethodHits", metrics.rawMethodHits(), true);
		number(json, 2, "uniqueMethodHits", metrics.uniqueMethodHits(), true);
		number(json, 2, "runtimeRecordingNanos", metrics.runtimeRecordingNanos(), false);
		json.append("  },\n");
		field(json, 1, "methodIdAlgorithm", Fnv1a64MethodIdHasher.ALGORITHM, true);
		writeCatalog(json, catalog);
		json.append(",\n");
		writeHits(json, hits, catalog);
		json.append(",\n");
		arrayField(json, 1, "agentErrors", sortedErrors, true);
		json.append("  \"bytecodeModified\": ").append(metrics.classesTransformed() > 0).append(",\n");
		json.append("  \"runtimeEvents\": ");
		if (runtimeJson == null) {
			json.append("null\n");
		} else {
			String indented = runtimeJson.stripTrailing().replace("\n", "\n  ");
			json.append(indented).append('\n');
		}
		json.append("}\n");
		return json.toString();
	}

	private static void writeCatalog(StringBuilder json, Map<Long, List<MethodIdentity>> catalog) {
		json.append("  \"methodCatalog\": [");
		boolean first = true;
		for (Map.Entry<Long, List<MethodIdentity>> entry : catalog.entrySet()) {
			if (!first) json.append(',');
			json.append("\n    {\"methodId\":").append(quote(Long.toUnsignedString(entry.getKey())))
					.append(",\"canonicalKeys\":[");
			for (int i = 0; i < entry.getValue().size(); i++) {
				if (i > 0) json.append(',');
				json.append(quote(entry.getValue().get(i).canonicalKey()));
			}
			json.append("]}");
			first = false;
		}
		if (!catalog.isEmpty()) json.append('\n').append("  ");
		json.append(']');
	}

	private static void writeHits(StringBuilder json, Map<Long, Long> hits,
			Map<Long, List<MethodIdentity>> catalog) {
		json.append("  \"methodHits\": [");
		boolean first = true;
		for (Map.Entry<Long, Long> entry : hits.entrySet()) {
			if (!first) json.append(',');
			json.append("\n    {\"methodId\":").append(quote(Long.toUnsignedString(entry.getKey())))
					.append(",\"count\":").append(entry.getValue()).append(",\"canonicalKeys\":[");
			List<MethodIdentity> methods = catalog.getOrDefault(entry.getKey(), List.of());
			for (int i = 0; i < methods.size(); i++) {
				if (i > 0) json.append(',');
				json.append(quote(methods.get(i).canonicalKey()));
			}
			json.append("]}");
			first = false;
		}
		if (!hits.isEmpty()) json.append('\n').append("  ");
		json.append(']');
	}

	private static void field(StringBuilder json, int indent, String name, String value, boolean comma) {
		field(json, indent, name, value, comma, true);
	}

	private static void field(StringBuilder json, int indent, String name, String value, boolean comma,
			boolean quoted) {
		json.append("  ".repeat(indent)).append(quote(name)).append(": ")
				.append(quoted ? quote(value) : value).append(comma ? ",\n" : "\n");
	}

	private static void number(StringBuilder json, int indent, String name, long value, boolean comma) {
		json.append("  ".repeat(indent)).append(quote(name)).append(": ").append(value)
				.append(comma ? ",\n" : "\n");
	}

	private static void arrayField(StringBuilder json, int indent, String name, List<String> values, boolean comma) {
		json.append("  ".repeat(indent)).append(quote(name)).append(": [");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) json.append(", ");
			json.append(quote(values.get(i)));
		}
		json.append(']').append(comma ? ",\n" : "\n");
	}

	private static String quote(String value) {
		StringBuilder result = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> result.append("\\\"");
				case '\\' -> result.append("\\\\");
				case '\n' -> result.append("\\n");
				case '\r' -> result.append("\\r");
				case '\t' -> result.append("\\t");
				default -> result.append(c);
			}
		}
		return result.append('"').toString();
	}
}
