// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, opt-in, ROUND-10-only per-hit diagnostic trace. */
final class CausalTraceRecorder {
	private final Path output;
	private final Set<String> methods;
	private final Set<String> tests;
	private final int limit;
	private final long origin = System.nanoTime();
	private final AtomicLong sequence = new AtomicLong();
	private final List<String> events = new ArrayList<>();
	private long matched;

	private CausalTraceRecorder(Path output, Set<String> methods, Set<String> tests, int limit) {
		this.output = output; this.methods = methods; this.tests = tests; this.limit = limit;
	}

	static CausalTraceRecorder fromSystemProperties() {
		String target = System.getProperty("stp.round10.trace.output");
		if (target == null || target.isBlank()) return new CausalTraceRecorder(null, Set.of(), Set.of(), 0);
		try {
			Set<String> methods = lines(System.getProperty("stp.round10.trace.methods"));
			Set<String> tests = lines(System.getProperty("stp.round10.trace.tests"));
			int limit = Integer.getInteger("stp.round10.trace.limit", 20000);
			return new CausalTraceRecorder(Path.of(target), methods, tests, Math.max(1, limit));
		} catch (IOException | RuntimeException failure) {
			throw new IllegalArgumentException("invalid ROUND-10 trace configuration", failure);
		}
	}

	private static Set<String> lines(String path) throws IOException {
		if (path == null || path.isBlank()) return Set.of();
		Set<String> values = new HashSet<>();
		for (String line : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8))
			if (!line.isBlank()) values.add(line.strip());
		return Set.copyOf(values);
	}

	boolean enabled() { return output != null; }

	void hit(MethodIdentity method, RuntimeContextService service) {
		if (!enabled() || !methods.contains(nameOnly(method.canonicalKey()))) return;
		RuntimeContextService.DiagnosticContext context = service.diagnosticContext();
		TestIdentity test = context.testIdentity();
		String testKey = test == null ? null : test.testClass() + "#" + test.testMethod();
		if (testKey != null && !tests.contains(testKey)) return;
		long order = sequence.incrementAndGet();
		synchronized (events) {
			matched++;
			if (events.size() >= limit) return;
			Thread thread = Thread.currentThread();
			RuntimeContextService.TaskIdentity task = context.task();
			events.add("{\"order\":" + order + ",\"nanoOffset\":" + (System.nanoTime() - origin)
					+ ",\"testIdentity\":" + quote(test == null ? null : test.platformUniqueId())
					+ ",\"testKey\":" + quote(testKey) + ",\"threadId\":" + thread.getId()
					+ ",\"thread\":" + quote(thread.getName()) + ",\"logicalContextId\":"
					+ (context.logicalContextId() == null ? "null" : context.logicalContextId())
					+ ",\"propagatedTask\":" + (task != null) + ",\"taskIdentity\":"
					+ quote(task == null ? null : task.className() + "@" + Integer.toHexString(task.identityHash()))
					+ ",\"method\":" + quote(method.canonicalKey()) + ",\"ownerFinished\":"
					+ context.ownerFinished() + "}");
		}
	}

	void write() throws IOException {
		if (!enabled()) return;
		List<String> snapshot;
		long total;
		synchronized (events) { snapshot = List.copyOf(events); total = matched; }
		StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": \"round10-per-hit-causal-trace-1\",\n")
				.append("  \"limit\": ").append(limit).append(",\n  \"matchedEvents\": ").append(total)
				.append(",\n  \"droppedEvents\": ").append(Math.max(0, total - snapshot.size())).append(",\n  \"events\": [");
		for (int i=0; i<snapshot.size(); i++) json.append(i == 0 ? "\n    " : ",\n    ").append(snapshot.get(i));
		if (!snapshot.isEmpty()) json.append('\n').append("  ");
		json.append("]\n}\n");
		Files.writeString(output, json, StandardCharsets.UTF_8);
	}

	private static String nameOnly(String key) {
		int open = key.indexOf('(', key.indexOf('#'));
		return open < 0 ? key : key.substring(0, open);
	}
	private static String quote(String value) {
		if (value == null) return "null";
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
