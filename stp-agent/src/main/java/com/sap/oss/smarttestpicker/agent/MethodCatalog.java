// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class MethodCatalog {
	private final MethodIdHasher hasher;
	private final AgentMetrics metrics;
	private final Consumer<String> errorSink;
	private final Map<Long, TreeSet<MethodIdentity>> methods = new ConcurrentHashMap<>();
	private final Map<MethodIdentity, Integer> ordinals = new java.util.HashMap<>();
	private volatile com.sap.oss.smarttestpicker.runtime.MethodProbe[] probes = new com.sap.oss.smarttestpicker.runtime.MethodProbe[64];
	private volatile int probeCount;

	public MethodCatalog(MethodIdHasher hasher, AgentMetrics metrics, Consumer<String> errorSink) {
		this.hasher = hasher;
		this.metrics = metrics;
		this.errorSink = errorSink;
	}

	public long register(MethodIdentity method) {
		long id = hasher.hash(method.canonicalKey());
		methods.compute(id, (ignored, existing) -> {
			TreeSet<MethodIdentity> values = existing == null ? new TreeSet<>() : existing;
			boolean added = values.add(method);
			if (added && values.size() == 2) {
				metrics.methodIdCollision();
				errorSink.accept("method-id-collision:" + Long.toUnsignedString(id));
			}
			return values;
		});
		return id;
	}

	public List<MethodIdentity> resolve(long id) {
		TreeSet<MethodIdentity> values = methods.get(id);
		return values == null ? List.of() : List.copyOf(values);
	}

	/** Transformation-only registration; one ordinal per identity even when hashes collide. */
	public synchronized int registerProbe(MethodIdentity method) {
		Integer existing = ordinals.get(method);
		if (existing != null) return existing;
		long hash = register(method);
		int ordinal = probeCount;
		if (ordinal == probes.length) probes = java.util.Arrays.copyOf(probes, ordinal * 2);
		probes[ordinal] = new com.sap.oss.smarttestpicker.runtime.MethodProbe(ordinal, hash, method);
		ordinals.put(method, ordinal);
		probeCount = ordinal + 1; // Release publication of the initialized slot.
		return ordinal;
	}

	public com.sap.oss.smarttestpicker.runtime.MethodProbe probe(long ordinal) {
		if (ordinal < 0 || ordinal >= probeCount) throw new IllegalArgumentException("unknown probe ordinal");
		return probes[(int) ordinal];
	}

	public Map<Long, Long> hitSnapshot() {
		Map<Long, Long> result = new TreeMap<>(Long::compareUnsigned);
		int size = probeCount;
		var current = probes;
		for (int index = 0; index < size; index++) {
			var probe = current[index];
			long count = probe.occurrences();
			if (count != 0) result.merge(probe.hash(), count, Long::sum);
		}
		return result;
	}

	public Map<Long, List<MethodIdentity>> snapshot() {
		TreeMap<Long, List<MethodIdentity>> result = new TreeMap<>(Long::compareUnsigned);
		methods.forEach((id, values) -> result.put(id, Collections.unmodifiableList(new ArrayList<>(values))));
		return Collections.unmodifiableMap(result);
	}
}
