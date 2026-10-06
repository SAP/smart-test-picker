// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Aggregates method observations independently for each concrete HTTP request. */
public final class RemoteRecorder {
	private static final Map<RemoteRequestIdentity, Set<String>> OBSERVATIONS = new ConcurrentHashMap<>();
	private static volatile Path output;

	private RemoteRecorder() { }

	static void install(Path outputPath) { output = outputPath; }
	static void clearForTests() { OBSERVATIONS.clear(); }

	public static void methodHit(String className, String methodName, String descriptor) {
		try {
			RemoteRequestIdentity identity = RemoteTestContext.currentIdentity();
			if (identity == null) return;
			OBSERVATIONS.computeIfAbsent(identity, ignored -> ConcurrentHashMap.newKeySet())
					.add(className + "#" + methodName + descriptor);
		} catch (Throwable ignored) {
			// Instrumentation must never alter application behavior.
		}
	}

	static void writeOutput() {
		Path destination = output;
		if (destination == null) return;
		try {
			Path parent = destination.getParent();
			if (parent != null) Files.createDirectories(parent);
			StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": 2,\n  \"requests\": [\n");
			Map<RemoteRequestIdentity, Set<String>> sorted = new TreeMap<>(java.util.Comparator
					.comparing(RemoteRequestIdentity::testSuiteId).thenComparing(RemoteRequestIdentity::testId)
					.thenComparing(RemoteRequestIdentity::requestId));
			sorted.putAll(OBSERVATIONS);
			int testIndex = 0;
			for (var entry : sorted.entrySet()) {
				if (testIndex++ > 0) json.append(",\n");
				RemoteRequestIdentity identity = entry.getKey();
				json.append("    {\"testSuiteId\":\"").append(escape(identity.testSuiteId()))
						.append("\",\"testId\":\"").append(escape(identity.testId()))
						.append("\",\"requestId\":\"").append(escape(identity.requestId())).append("\",\"methods\":[");
				int methodIndex = 0;
				for (String method : new java.util.TreeSet<>(entry.getValue())) {
					if (methodIndex++ > 0) json.append(',');
					json.append('"').append(escape(method)).append('"');
				}
				json.append("]}");
			}
			json.append("\n  ]\n}\n");
			Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
			Files.writeString(temporary, json, StandardCharsets.UTF_8);
			try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException failure) {
			System.err.println("[stp-remote-agent] unable to write observations: " + failure.getClass().getSimpleName());
		}
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}
