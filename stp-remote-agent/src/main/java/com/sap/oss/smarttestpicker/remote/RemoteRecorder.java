// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Aggregates pending observations and periodically persists a cumulative schema-v2 snapshot. */
public final class RemoteRecorder {
	private static final Object STATE_LOCK = new Object();
	private static final Object CHECKPOINT_LOCK = new Object();
	private static final Object SCHEDULER_LOCK = new Object();
	/** Hits not yet covered by a successful checkpoint. */
	private static final Map<RemoteRequestIdentity, Set<String>> OBSERVATIONS = new HashMap<>();
	/** Cumulative observations already persisted during this JVM run. */
	private static final Map<RemoteRequestIdentity, Set<String>> PERSISTED = new HashMap<>();
	private static RemoteOutputFile outputFile;
	private static boolean acceptingHits;
	private static boolean finalized;
	private static ScheduledExecutorService checkpointScheduler;
	private static volatile Runnable beforeCheckpointPersistForTests = () -> { };

	private RemoteRecorder() { }

	/** Creates parent directories and reserves a new output file. Existing paths are never reused. */
	static void install(java.nio.file.Path outputPath) {
		synchronized (STATE_LOCK) {
			outputFile = RemoteOutputFile.reserve(outputPath);
			OBSERVATIONS.clear();
			PERSISTED.clear();
			acceptingHits = true;
			finalized = false;
		}
	}

	static void startPeriodicCheckpoints(int intervalSeconds) {
		if (intervalSeconds < 1) throw new IllegalArgumentException("flushIntervalSeconds must be >= 1");
		synchronized (SCHEDULER_LOCK) {
			if (checkpointScheduler != null) throw new IllegalStateException("Remote STP checkpoint scheduler is already running");
			checkpointScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
				Thread thread = new Thread(task, "stp-remote-checkpoint");
				thread.setDaemon(true);
				return thread;
			});
			checkpointScheduler.scheduleWithFixedDelay(() -> {
				try { checkpointNow(); }
				catch (Throwable failure) { System.err.println("[stp-remote-agent] periodic checkpoint failed: " + failure); }
			}, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
		}
	}

	static void stopPeriodicCheckpoints() {
		ScheduledExecutorService scheduler;
		synchronized (SCHEDULER_LOCK) {
			scheduler = checkpointScheduler;
			checkpointScheduler = null;
			if (scheduler != null) scheduler.shutdown();
		}
		if (scheduler == null) return;
		try {
			if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) scheduler.shutdownNow();
		} catch (InterruptedException interrupted) {
			scheduler.shutdownNow();
			Thread.currentThread().interrupt();
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

	/** Stops scheduling and accepting hits, then persists all remaining observations. */
	static void writeOutput() {
		stopPeriodicCheckpoints();
		synchronized (STATE_LOCK) {
			if (outputFile == null || finalized) return;
			acceptingHits = false;
		}
		persistSnapshot(true);
	}

	/** Persists current pending hits without finalizing the output lease. */
	static void checkpointNow() { persistSnapshot(false); }

	private static void persistSnapshot(boolean finalCheckpoint) {
		synchronized (CHECKPOINT_LOCK) {
			RemoteOutputFile destination;
			Map<RemoteRequestIdentity, Set<String>> pending;
			Map<RemoteRequestIdentity, Set<String>> merged;
			synchronized (STATE_LOCK) {
				destination = outputFile;
				if (destination == null || finalized || (!acceptingHits && !finalCheckpoint)) return;
				pending = snapshotLocked(OBSERVATIONS);
				if (!finalCheckpoint && pending.isEmpty()) return;
				merged = snapshotLocked(PERSISTED);
				merge(merged, pending);
			}
			String json;
			try {
				beforeCheckpointPersistForTests.run();
				json = serialize(merged);
			} catch (RuntimeException | Error failure) {
				if (finalCheckpoint) destination.abandon();
				throw destination.serializationFailure(failure);
			}
			if (finalCheckpoint) destination.finish(json);
			else destination.checkpoint(json);
			synchronized (STATE_LOCK) {
				merge(PERSISTED, pending);
				compact(OBSERVATIONS, pending);
				if (finalCheckpoint) finalized = true;
			}
		}
	}

	/** Returns a consistent schema-v2 view of observations currently held in memory. */
	static String memorySnapshot() {
		synchronized (STATE_LOCK) { return serialize(snapshotLocked(OBSERVATIONS)); }
	}

	/** The persisted destination is visible only after finalization (updated to checkpoints in Task 2). */
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

	private static Map<RemoteRequestIdentity, Set<String>> snapshotLocked(Map<RemoteRequestIdentity, Set<String>> source) {
		Map<RemoteRequestIdentity, Set<String>> snapshot = new HashMap<>();
		source.forEach((identity, methods) -> snapshot.put(identity, new HashSet<>(methods)));
		return snapshot;
	}

	private static void merge(Map<RemoteRequestIdentity, Set<String>> destination,
			Map<RemoteRequestIdentity, Set<String>> additions) {
		additions.forEach((identity, methods) -> destination.computeIfAbsent(identity, ignored -> new HashSet<>()).addAll(methods));
	}

	private static void compact(Map<RemoteRequestIdentity, Set<String>> pending,
			Map<RemoteRequestIdentity, Set<String>> checkpointed) {
		checkpointed.forEach((identity, methods) -> {
			Set<String> remaining = pending.get(identity);
			if (remaining == null) return;
			remaining.removeAll(methods);
			if (remaining.isEmpty()) pending.remove(identity);
		});
	}

	static void beforeCheckpointPersistForTests(Runnable action) {
		beforeCheckpointPersistForTests = action == null ? () -> { } : action;
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
		stopPeriodicCheckpoints();
		synchronized (STATE_LOCK) {
			OBSERVATIONS.clear();
			PERSISTED.clear();
			if (outputFile != null) outputFile.abandon();
			outputFile = null;
			acceptingHits = false;
			finalized = false;
		}
		beforeCheckpointPersistForTests = () -> { };
	}
}
