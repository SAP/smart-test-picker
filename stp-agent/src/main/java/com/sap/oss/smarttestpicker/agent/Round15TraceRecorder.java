// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeHooks;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;

/** Bounded, synchronous, opt-in reflection pipeline trace for ROUND 15. */
final class Round15TraceRecorder implements RuntimeHooks.Round15Sink {
	private static final String TARGET = "org.springframework.core.BridgeMethodResolverTests#withGenericParameter";
	private final Path output;
	private final int limit;
	private final AtomicLong sequence = new AtomicLong();
	private final List<String> events = new ArrayList<>();
	private long dropped;

	private Round15TraceRecorder(Path output, int limit) { this.output = output; this.limit = limit; }
	static Round15TraceRecorder fromSystemProperties() {
		String value = System.getProperty("stp.round15.trace.output");
		return new Round15TraceRecorder(value == null || value.isBlank() ? null : Path.of(value),
				Integer.getInteger("stp.round15.trace.limit", 10000));
	}
	boolean enabled() { return output != null; }

	@Override public void methods(String stage, Class<?> clazz, Method[] methods) {
		if (selected()) record(stage, clazz, methodsJson(methods), null, null);
	}
	@Override public void filter(Method method, boolean accepted) {
		if (selected()) record("DOWITHMETHODS_CALLBACK", method.getDeclaringClass(), methodValue(method), accepted, null);
	}
	@Override public void callback(Method method) {
		if (selected()) record("CANDIDATE_INSERT", method.getDeclaringClass(), methodValue(method), true,
				"accepted by isBridgedCandidateFor and passed to candidateMethods::add");
	}
	@Override public void searchCandidates(List<?> methods) {
		if (selected()) record("SEARCH_CANDIDATES_INPUT", null, listJson(methods), null, null);
	}
	@Override public void testEvent(String event) {
		if (selected()) record(event, null, "null", null, null);
	}

	private boolean selected() {
		RuntimeContextService.DiagnosticContext context = RuntimeContextRegistry.current()
				.map(RuntimeContextService::diagnosticContext).orElse(null);
		TestIdentity test = context == null ? null : context.testIdentity();
		return test != null && (test.testClass() + "#" + test.testMethod()).equals(TARGET);
	}
	private synchronized void record(String event, Class<?> clazz, String methods, Boolean accepted, String reason) {
		long seq = sequence.incrementAndGet();
		if (events.size() >= limit) { dropped++; return; }
		events.add("{\"sequence\":" + seq + ",\"event\":" + q(event) + ",\"testIdentity\":" + q(TARGET)
				+ ",\"classQueried\":" + q(clazz == null ? null : clazz.getName()) + ",\"methods\":" + methods
				+ ",\"accepted\":" + (accepted == null ? "null" : accepted) + ",\"reason\":" + q(reason) + "}");
	}
	private static String methodsJson(Method[] methods) {
		StringBuilder out = new StringBuilder("{\"length\":").append(methods.length).append(",\"order\":[");
		for (int i=0;i<methods.length;i++) { if (i>0) out.append(','); out.append("{\"index\":").append(i).append(",\"method\":").append(methodValue(methods[i])).append('}'); }
		return out.append("]}").toString();
	}
	private static String listJson(List<?> methods) {
		StringBuilder out = new StringBuilder("{\"length\":").append(methods.size()).append(",\"order\":[");
		for (int i=0;i<methods.size();i++) { if (i>0) out.append(','); out.append("{\"index\":").append(i).append(",\"method\":").append(methodValue(methods.get(i))).append('}'); }
		return out.append("]}").toString();
	}
	private static String methodValue(Object value) {
		if (!(value instanceof Method method)) return "null";
		StringBuilder key = new StringBuilder(method.getDeclaringClass().getName()).append('#').append(method.getName()).append('(');
		Class<?>[] parameters = method.getParameterTypes();
		for (int i=0;i<parameters.length;i++) { if (i>0) key.append(','); key.append(parameters[i].getTypeName()); }
		key.append(")->").append(method.getReturnType().getTypeName());
		return "{\"structuralKey\":" + q(key.toString()) + ",\"bridge\":" + method.isBridge() + ",\"synthetic\":"
				+ method.isSynthetic() + ",\"modifiers\":" + method.getModifiers() + ",\"identityHashCode\":"
				+ System.identityHashCode(method) + "}";
	}
	void write() throws IOException {
		if (!enabled()) return;
		List<String> snapshot; synchronized (this) { snapshot = List.copyOf(events); }
		StringBuilder out = new StringBuilder("{\n  \"schemaVersion\": \"round15-runtime-trace-1\",\n  \"identityScope\": \"identityHashCode is same-JVM correlation only\",\n  \"droppedEvents\": ").append(dropped).append(",\n  \"events\": [");
		for (int i=0;i<snapshot.size();i++) out.append(i==0 ? "\n    " : ",\n    ").append(snapshot.get(i));
		if (!snapshot.isEmpty()) out.append('\n').append("  ");
		Files.writeString(output, out.append("]\n}\n"), StandardCharsets.UTF_8);
	}
	private static String q(String value) { return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
