// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/** Allocation-free method-entry hook after one explicitly installed sink. */
public final class RuntimeHooks {
	private static final AtomicReference<LongConsumer> SINK = new AtomicReference<>();

	private RuntimeHooks() {
	}

	public static Registration install(LongConsumer sink) {
		Objects.requireNonNull(sink, "sink");
		if (!SINK.compareAndSet(null, sink)) throw new IllegalStateException("runtime hook sink already installed");
		return new Registration(sink);
	}

	public static void methodHit(long methodId) {
		LongConsumer sink = SINK.get();
		if (sink == null) return;
		try {
			sink.accept(methodId);
		} catch (Throwable ignored) {
			// A collector failure must never escape into instrumented application code.
		}
	}

	public static final class Registration implements AutoCloseable {
		private final LongConsumer installed;
		private boolean closed;

		private Registration(LongConsumer installed) {
			this.installed = installed;
		}

		@Override
		public synchronized void close() {
			if (!closed) {
				SINK.compareAndSet(installed, null);
				closed = true;
			}
		}
	}
}

