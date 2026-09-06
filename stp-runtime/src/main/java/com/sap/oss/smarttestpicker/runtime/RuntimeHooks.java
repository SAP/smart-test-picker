// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

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

	public static Runnable wrap(Runnable task) {
		if (task == null) return null;
		try {
			return RuntimeContextRegistry.current().map(service -> service.wrap(task)).orElse(task);
		} catch (Throwable ignored) {
			return task;
		}
	}

	public static <V> Callable<V> wrap(Callable<V> task) {
		if (task == null) return null;
		try {
			return RuntimeContextRegistry.current().map(service -> service.wrap(task)).orElse(task);
		} catch (Throwable ignored) {
			return task;
		}
	}

	public static <V> Supplier<V> wrapSupplier(Supplier<V> task) {
		if (task == null) return null;
		try {
			return RuntimeContextRegistry.current().map(service -> service.wrapSupplier(task)).orElse(task);
		} catch (Throwable ignored) {
			return task;
		}
	}

	public static <T, R> Function<T, R> wrapFunction(Function<T, R> task) {
		if (task == null) return null;
		try {
			return RuntimeContextRegistry.current().map(service -> service.wrapFunction(task)).orElse(task);
		} catch (Throwable ignored) {
			return task;
		}
	}

	public static ScheduledFuture<?> schedule(ScheduledExecutorService executor, Runnable task,
			long delay, TimeUnit unit) {
		return executor.schedule(wrap(task), delay, unit);
	}

	public static <V> ScheduledFuture<V> schedule(ScheduledExecutorService executor, Callable<V> task,
			long delay, TimeUnit unit) {
		return executor.schedule(wrap(task), delay, unit);
	}

	public static ScheduledFuture<?> scheduleAtFixedRate(ScheduledExecutorService executor, Runnable task,
			long initialDelay, long period, TimeUnit unit) {
		return executor.scheduleAtFixedRate(wrap(task), initialDelay, period, unit);
	}

	public static ScheduledFuture<?> scheduleWithFixedDelay(ScheduledExecutorService executor, Runnable task,
			long initialDelay, long delay, TimeUnit unit) {
		return executor.scheduleWithFixedDelay(wrap(task), initialDelay, delay, unit);
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
