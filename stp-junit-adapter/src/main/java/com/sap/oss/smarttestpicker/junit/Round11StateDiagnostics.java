// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.junit;

import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, opt-in, ROUND-11-only snapshots of directly implicated Spring caches. */
final class Round11StateDiagnostics {
	private static final AtomicLong ORDER = new AtomicLong();
	private static final String[][] OWNERS = {
			{"org.springframework.core.BridgeMethodResolver", "cache"},
			{"org.springframework.core.ResolvableType", "cache"},
			{"org.springframework.core.SerializableTypeWrapper", "cache"},
			{"org.springframework.core.GenericTypeResolver", "typeVariableCache"},
			{"org.springframework.util.ReflectionUtils", "declaredMethodsCache"},
			{"org.springframework.util.ReflectionUtils", "declaredFieldsCache"},
			{"org.springframework.util.ClassUtils", "interfaceMethodCache"}
	};

	private final Path output;
	private final String resetBefore;
	private final String forceGcBefore;

	Round11StateDiagnostics() {
		String value = System.getProperty("stp.round11.state.output");
		this.output = value == null || value.isBlank() ? null : Path.of(value);
		this.resetBefore = System.getProperty("stp.round11.state.resetBefore", "");
		this.forceGcBefore = System.getProperty("stp.round11.state.forceGcBefore", "");
	}

	void before(TestIdentity test) {
		if (output == null) return;
		if (matches(test, forceGcBefore)) {
			System.gc();
			write("FORCED_GC", test, "java.lang.System.gc", null, -1, List.of(), null);
		}
		if (matches(test, resetBefore)) {
			for (String[] owner : OWNERS) clear(owner, test);
		}
		snapshot("BEFORE_TEST", test);
	}

	void after(TestIdentity test) {
		if (output != null) snapshot("AFTER_TEST", test);
	}

	private void snapshot(String operation, TestIdentity test) {
		for (String[] owner : OWNERS) {
			try {
				Object state = field(owner).get(null);
				if (!(state instanceof Map<?, ?> map)) continue;
				List<Integer> hashes = new ArrayList<>();
				for (Object key : map.keySet()) hashes.add(key == null ? 0 : key.hashCode());
				hashes.sort(Comparator.naturalOrder());
				write(operation, test, ownerName(owner), state, map.size(), hashes, null);
			} catch (Throwable failure) {
				write(operation, test, ownerName(owner), null, -1, List.of(), failure.getClass().getName());
			}
		}
	}

	private void clear(String[] owner, TestIdentity test) {
		try {
			Object state = field(owner).get(null);
			if (state instanceof Map<?, ?> map) {
				int before = map.size();
				map.clear();
				write("RESET", test, ownerName(owner), state, before, List.of(), null);
			}
		} catch (Throwable failure) {
			write("RESET", test, ownerName(owner), null, -1, List.of(), failure.getClass().getName());
		}
	}

	private static Field field(String[] owner) throws ReflectiveOperationException {
		Class<?> type = Class.forName(owner[0], false, Thread.currentThread().getContextClassLoader());
		Field field = type.getDeclaredField(owner[1]);
		field.setAccessible(true);
		return field;
	}

	private synchronized void write(String operation, TestIdentity test, String owner, Object state,
			int size, List<Integer> hashes, String error) {
		String line = "{\"order\":" + ORDER.incrementAndGet() + ",\"operation\":" + quote(operation)
				+ ",\"testClass\":" + quote(test.testClass()) + ",\"testMethod\":" + quote(test.testMethod())
				+ ",\"stateOwner\":" + quote(owner) + ",\"ownerIdentity\":"
				+ quote(state == null ? null : state.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(state)))
				+ ",\"size\":" + size + ",\"keyHashes\":" + hashes + ",\"error\":" + quote(error) + "}\n";
		try {
			Files.writeString(output, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException failure) {
			System.err.println("[stp-round11] state trace write failed: " + failure.getClass().getSimpleName());
		}
	}

	private static boolean matches(TestIdentity test, String configured) {
		return !configured.isBlank() && (test.testClass() + "#" + test.testMethod()).equals(configured);
	}

	private static String ownerName(String[] owner) { return owner[0] + "." + owner[1]; }
	private static String quote(String value) {
		if (value == null) return "null";
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
