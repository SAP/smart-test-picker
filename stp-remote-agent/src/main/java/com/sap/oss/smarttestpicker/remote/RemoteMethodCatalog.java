// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maps a precomputed {@code long} method id to its serialized method key ({@code className#name+desc}).
 *
 * <p>The id is computed once at class-transform time (FNV-1a 64-bit, matching the JUnit-side
 * {@code stp-agent} hasher) and pushed into the injected hook, so the per-call hot path carries a
 * single {@code long} instead of three strings. Serialization resolves the id back to the exact
 * string the output contract expects, keeping {@code observations.json} byte-identical.
 */
final class RemoteMethodCatalog {
	private static final long SEED = 0xcbf29ce484222325L;
	private static final long PRIME = 0x100000001b3L;

	private final ConcurrentMap<Long, String> methods = new ConcurrentHashMap<>();

	/**
	 * Registers a method and returns its stable id. Called at transform time (one class-load wave),
	 * never on the hot path. A collision (same id, different key) is reported once and the first
	 * registration wins so resolution stays deterministic.
	 */
	long register(String className, String methodName, String descriptor) {
		String key = className + "#" + methodName + descriptor;
		long id = hash(key);
		String previous = methods.putIfAbsent(id, key);
		if (previous != null && !previous.equals(key)) {
			System.err.println("[stp-remote-agent] method-id-collision:" + Long.toUnsignedString(id)
					+ " keeps '" + previous + "' ignores '" + key + "'");
		}
		return id;
	}

	/** Resolves an id to its serialized method key, or {@code null} if never registered. */
	String resolve(long id) {
		return methods.get(id);
	}

	static long hash(String canonicalMethodKey) {
		long hash = SEED;
		for (byte value : canonicalMethodKey.getBytes(StandardCharsets.UTF_8)) {
			hash ^= value & 0xffL;
			hash *= PRIME;
		}
		return hash;
	}

	void clearForTests() {
		methods.clear();
	}
}
