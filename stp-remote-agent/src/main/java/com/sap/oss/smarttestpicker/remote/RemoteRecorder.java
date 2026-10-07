// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.io.IOException;
import java.nio.file.Files;

/** Aggregates observations in memory and atomically persists one schema-v2 snapshot at JVM shutdown. */
public final class RemoteRecorder {
	private static final Object STATE_LOCK = new Object();
	private static final Map<RemoteRequestIdentity, Set<String>> OBSERVATIONS = new HashMap<>();
	private static RemoteOutputFile outputFile;
	private static boolean acceptingHits;
	private static boolean finalized;

	private RemoteRecorder() { }

	/** Creates parent directories and reserves a new output file. Existing paths are never reused. */
	static void install(java.nio.file.Path outputPath) {
		synchronized (STATE_LOCK) {
			outputFile = RemoteOutputFile.reserve(outputPath);
			acceptingHits = true;
			finalized = false;
		}
	}

	public static void methodHit(String className, String methodName, String descriptor) {
		try {
			RemoteRequestIdentity identity = RemoteTestContext.currentIdentity();
			if (identity == null) return;
			synchronized (STATE_LOCK) {
				if (!acceptingHits) return;
				OBSERVATIONS.computeIfAbsent(identity, ignored -> new HashSet<>())
						.add(className + "#" + methodName + descriptor);
			}
		} catch (Throwable ignored) {
			// Instrumentation must never alter application behavior.
		}
	}

	/** Stops accepting hits, takes one consistent snapshot, and replaces the reserved file. */
	static void writeOutput() {
		RemoteOutputFile destination;
		Map<RemoteRequestIdentity, Set<String>> snapshot = new HashMap<>();
		synchronized (STATE_LOCK) {
			destination = outputFile;
			if (destination == null || !acceptingHits) return;
			acceptingHits = false;
			snapshot = snapshotLocked();
		}

		String json;
		try {
			json = serialize(snapshot); // Complete serialization before touching the final path.
		} catch (RuntimeException | Error failure) {
			destination.abandon();
			throw destination.serializationFailure(failure);
		}
		destination.finish(json);
		synchronized (STATE_LOCK) { finalized = true; }
	}

	/** Returns a consistent schema-v2 view of all observations collected so far. */
	static String memorySnapshot() {
		synchronized (STATE_LOCK) { return serialize(snapshotLocked()); }
	}

	/** The persisted destination is visible only after this recorder completed finalization. */
	static FinalizedOutput finalizedOutput() throws IOException {
		synchronized (STATE_LOCK) {
			if (!finalized || outputFile == null) return null;
			return new FinalizedOutput(outputFile.path(), Files.readAllBytes(outputFile.path()));
		}
	}

	static java.nio.file.Path configuredOutput() {
		synchronized (STATE_LOCK) { return outputFile == null ? null : outputFile.path(); }
	}

	record FinalizedOutput(java.nio.file.Path path, byte[] content) { }

	private static Map<RemoteRequestIdentity, Set<String>> snapshotLocked() {
		Map<RemoteRequestIdentity, Set<String>> snapshot = new HashMap<>();
		OBSERVATIONS.forEach((identity, methods) -> snapshot.put(identity, Set.copyOf(methods)));
		return snapshot;
	}

	private static String serialize(Map<RemoteRequestIdentity, Set<String>> observations) {
		List<RemoteRequestIdentity> identities = new ArrayList<>(observations.keySet());
		identities.sort(Comparator.comparing(RemoteRequestIdentity::testSuiteId)
				.thenComparing(RemoteRequestIdentity::testId).thenComparing(RemoteRequestIdentity::requestId));
		StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": 2,\n  \"requests\": [\n");
		for (int requestIndex = 0; requestIndex < identities.size(); requestIndex++) {
			if (requestIndex > 0) json.append(",\n");
			RemoteRequestIdentity identity = identities.get(requestIndex);
			json.append("    {\"testSuiteId\":\"").append(escape(identity.testSuiteId()))
					.append("\",\"testId\":\"").append(escape(identity.testId()))
					.append("\",\"requestId\":\"").append(escape(identity.requestId())).append("\",\"methods\":[");
			int methodIndex = 0;
			for (String method : new TreeSet<>(observations.get(identity))) {
				if (methodIndex++ > 0) json.append(',');
				json.append('"').append(escape(method)).append('"');
			}
			json.append("]}");
		}
		return json.append("\n  ]\n}\n").toString();
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char current = value.charAt(i);
			switch (current) {
				case '\\' -> escaped.append("\\\\");
				case '"' -> escaped.append("\\\"");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (current < 0x20) escaped.append(String.format("\\u%04x", (int) current));
					else escaped.append(current);
				}
			}
		}
		return escaped.toString();
	}

	static void clearForTests() {
		synchronized (STATE_LOCK) {
			OBSERVATIONS.clear();
			if (outputFile != null) outputFile.abandon();
			outputFile = null;
			acceptingHits = false;
			finalized = false;
		}
	}
}
