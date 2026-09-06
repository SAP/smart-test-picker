// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeHooks;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;

/** Bounded, opt-in structural Method and helper-call trace for ROUND 14. */
final class Round14TraceRecorder implements RuntimeHooks.Round14Sink {
	private static final String TARGET = "org.springframework.core.BridgeMethodResolverTests#withGenericParameter";
	private final Path output;
	private final int limit;
	private final AtomicLong sequence = new AtomicLong();
	private final List<String> events = new ArrayList<>();
	private final ThreadLocal<ArrayDeque<Frame>> calls = ThreadLocal.withInitial(ArrayDeque::new);
	private long dropped;

	private Round14TraceRecorder(Path output, int limit) { this.output = output; this.limit = limit; }

	static Round14TraceRecorder fromSystemProperties() {
		String value = System.getProperty("stp.round14.trace.output");
		return new Round14TraceRecorder(value == null || value.isBlank() ? null : Path.of(value),
				Integer.getInteger("stp.round14.trace.limit", 10000));
	}

	boolean enabled() { return output != null; }

	@Override public void testEvent(String event) {
		if (!selected()) return;
		record("TEST_" + (event.endsWith("START") ? "START" : "END"), null, null, null, null, null);
	}

	@Override public void enter(String method, Object receiver, Object[] arguments) {
		if (!selected()) return;
		ArrayDeque<Frame> stack = calls.get();
		Frame frame = new Frame(method, receiver, arguments, candidate(method, arguments), bridge(method, arguments), declaringClass(arguments));
		stack.push(frame);
		String candidates = method.contains("#searchCandidates(") ? methodList(arguments.length > 0 ? arguments[0] : null) : null;
		record("HELPER_ENTER", method, arguments, null, frame, candidates);
	}

	@Override public void exit(String method, Object result) {
		if (!selected()) return;
		ArrayDeque<Frame> stack = calls.get();
		Frame frame = stack.isEmpty() ? null : stack.pop();
		record("HELPER_EXIT", method, frame == null ? null : frame.arguments, result, frame, null);
	}

	private boolean selected() {
		RuntimeContextService.DiagnosticContext context = RuntimeContextRegistry.current()
				.map(RuntimeContextService::diagnosticContext).orElse(null);
		TestIdentity test = context == null ? null : context.testIdentity();
		return test != null && (test.testClass() + "#" + test.testMethod()).equals(TARGET);
	}

	private synchronized void record(String event, String helper, Object[] arguments, Object result,
			Frame frame, String candidates) {
		long seq = sequence.incrementAndGet();
		if (events.size() >= limit) { dropped++; return; }
		Frame context = effectiveFrame(frame);
		Object receiver = helper != null && helper.contains("ResolvableType#getInterfaces") && frame != null ? frame.receiver : null;
		String json = "{\"sequence\":" + seq + ",\"event\":" + q(event) + ",\"testIdentity\":" + q(TARGET)
				+ ",\"helper\":" + q(helper) + ",\"depth\":" + calls.get().size()
				+ ",\"arguments\":" + values(arguments) + ",\"result\":" + value(result)
				+ ",\"currentCandidate\":" + methodValue(context == null ? null : context.candidate)
				+ ",\"bridgeMethod\":" + methodValue(context == null ? null : context.bridge)
				+ ",\"declaringClass\":" + classValue(context == null ? null : context.declaringClass)
				+ ",\"candidateCollection\":" + (candidates == null ? "null" : candidates)
				+ ",\"resolvableTypeReceiver\":" + resolvableState(receiver) + "}";
		events.add(json);
	}

	private Frame effectiveFrame(Frame direct) {
		if (direct != null && (direct.candidate != null || direct.bridge != null)) return direct;
		for (Frame frame : calls.get()) if (frame.candidate != null || frame.bridge != null) return frame;
		return direct;
	}

	private static Method candidate(String helper, Object[] args) {
		if (args == null) return null;
		if (helper.contains("#isBridgedCandidateFor(") && args.length > 0 && args[0] instanceof Method value) return value;
		if ((helper.contains("#isBridgeMethodFor(") || helper.contains("#isResolvedTypeMatch(")) &&
				args.length > 1 && args[1] instanceof Method value) return value;
		if (args.length > 0 && args[0] instanceof Method value) return value;
		return null;
	}

	private static Method bridge(String helper, Object[] args) {
		if (args == null) return null;
		if (helper.contains("#isBridgedCandidateFor(") && args.length > 1 && args[1] instanceof Method value) return value;
		if (helper.contains("#isBridgeMethodFor(") && args.length > 0 && args[0] instanceof Method value) return value;
		for (Object arg : args) if (arg instanceof Method value && value.isBridge()) return value;
		return null;
	}

	private static Class<?> declaringClass(Object[] args) {
		if (args == null) return null;
		for (Object arg : args) if (arg instanceof Class<?> value) return value;
		return null;
	}

	private static String methodList(Object value) {
		if (!(value instanceof List<?> list)) return "null";
		StringBuilder out = new StringBuilder("{\"size\":").append(list.size()).append(",\"methods\":[");
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) out.append(',');
			out.append("{\"index\":").append(i).append(",\"method\":").append(methodValue(list.get(i))).append('}');
		}
		return out.append("]}").toString();
	}

	private static String values(Object[] values) {
		if (values == null) return "null";
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) out.append(',');
			out.append(value(values[i]));
		}
		return out.append(']').toString();
	}

	private static String value(Object value) {
		if (value instanceof Method) return methodValue(value);
		if (value instanceof Class<?>) return classValue(value);
		if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
		if (value instanceof List<?>) return methodList(value);
		return value == null ? "null" : q(value.getClass().getName());
	}

	private static String methodValue(Object value) {
		if (!(value instanceof Method method)) return "null";
		StringBuilder key = new StringBuilder(method.getDeclaringClass().getName()).append('#').append(method.getName()).append('(');
		Class<?>[] parameters = method.getParameterTypes();
		for (int i = 0; i < parameters.length; i++) { if (i > 0) key.append(','); key.append(parameters[i].getTypeName()); }
		key.append(")->").append(method.getReturnType().getTypeName());
		StringBuilder parameterNames = new StringBuilder("[");
		for (int i = 0; i < parameters.length; i++) { if (i > 0) parameterNames.append(','); parameterNames.append(q(parameters[i].getTypeName())); }
		parameterNames.append(']');
		return "{\"structuralKey\":" + q(key.toString()) + ",\"declaringClass\":" + q(method.getDeclaringClass().getName())
				+ ",\"name\":" + q(method.getName()) + ",\"parameterTypes\":" + parameterNames
				+ ",\"returnType\":" + q(method.getReturnType().getTypeName()) + ",\"bridge\":" + method.isBridge()
				+ ",\"synthetic\":" + method.isSynthetic() + ",\"modifiers\":" + method.getModifiers()
				+ ",\"identityHashCode\":" + System.identityHashCode(method) + "}";
	}

	private static String classValue(Object value) {
		return value instanceof Class<?> type ? q(type.getName()) : "null";
	}

	private static String resolvableState(Object receiver) {
		if (receiver == null || !receiver.getClass().getName().equals("org.springframework.core.ResolvableType")) return "null";
		Object type = field(receiver, "type");
		Object resolved = field(receiver, "resolved");
		return "{\"identityHashCode\":" + System.identityHashCode(receiver) + ",\"typeClass\":" + q(type == null ? null : type.getClass().getName())
				+ ",\"typeName\":" + q(type instanceof Type t ? t.getTypeName() : null)
				+ ",\"resolvedClass\":" + q(resolved instanceof Class<?> c ? c.getName() : null)
				+ ",\"interfacesInitialized\":" + (field(receiver, "interfaces") != null) + "}";
	}

	private static Object field(Object target, String name) {
		try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
		catch (Throwable ignored) { return null; }
	}

	void write() throws IOException {
		if (!enabled()) return;
		List<String> snapshot;
		synchronized (this) { snapshot = List.copyOf(events); }
		StringBuilder out = new StringBuilder("{\n  \"schemaVersion\": \"round14-runtime-trace-1\",\n  \"identityScope\": \"identityHashCode is same-JVM correlation only\",\n  \"droppedEvents\": ").append(dropped).append(",\n  \"events\": [");
		for (int i = 0; i < snapshot.size(); i++) out.append(i == 0 ? "\n    " : ",\n    ").append(snapshot.get(i));
		if (!snapshot.isEmpty()) out.append('\n').append("  ");
		out.append("]\n}\n");
		Files.writeString(output, out, StandardCharsets.UTF_8);
	}

	private static String q(String value) { return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
	private record Frame(String method, Object receiver, Object[] arguments, Method candidate, Method bridge, Class<?> declaringClass) { }
}
