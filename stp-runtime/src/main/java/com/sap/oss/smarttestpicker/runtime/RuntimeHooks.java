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
	private static final AtomicReference<Round13Sink> ROUND13_SINK = new AtomicReference<>();
	private static final AtomicReference<Round14Sink> ROUND14_SINK = new AtomicReference<>();
	private static final AtomicReference<Round15Sink> ROUND15_SINK = new AtomicReference<>();

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

	/** Opt-in diagnostic boundary used only by the ROUND 13 transformer. */
	public static void round13Enter(String method, Object receiver, Object argument) {
		Round13Sink sink = ROUND13_SINK.get();
		if (sink != null) try { sink.enter(method, receiver, argument); } catch (Throwable ignored) { }
	}

	/** Opt-in diagnostic boundary used only by the ROUND 13 transformer. */
	public static void round13Exit(String method, Object receiver, Object result) {
		Round13Sink sink = ROUND13_SINK.get();
		if (sink != null) try { sink.exit(method, receiver, result); } catch (Throwable ignored) { }
	}

	public static void round13TestEvent(String event) {
		Round13Sink sink = ROUND13_SINK.get();
		if (sink != null) try { sink.testEvent(event); } catch (Throwable ignored) { }
	}

	/** Opt-in diagnostic boundary used only by the ROUND 14 transformer. */
	public static void round14Enter(String method, Object receiver, Object[] arguments) {
		Round14Sink sink = ROUND14_SINK.get();
		if (sink != null) try { sink.enter(method, receiver, arguments); } catch (Throwable ignored) { }
	}

	/** Opt-in diagnostic boundary used only by the ROUND 14 transformer. */
	public static void round14Exit(String method, Object result) {
		Round14Sink sink = ROUND14_SINK.get();
		if (sink != null) try { sink.exit(method, result); } catch (Throwable ignored) { }
	}

	public static void round14TestEvent(String event) {
		Round14Sink sink = ROUND14_SINK.get();
		if (sink != null) try { sink.testEvent(event); } catch (Throwable ignored) { }
	}

	public static Round14Registration installRound14(Round14Sink sink) {
		Objects.requireNonNull(sink, "sink");
		if (!ROUND14_SINK.compareAndSet(null, sink)) throw new IllegalStateException("ROUND 14 sink already installed");
		return new Round14Registration(sink);
	}

	public interface Round14Sink {
		void enter(String method, Object receiver, Object[] arguments);
		void exit(String method, Object result);
		void testEvent(String event);
	}

	public static final class Round14Registration implements AutoCloseable {
		private final Round14Sink installed;
		private Round14Registration(Round14Sink installed) { this.installed = installed; }
		@Override public void close() { ROUND14_SINK.compareAndSet(installed, null); }
	}

	/** Opt-in diagnostic boundaries used only by the ROUND 15 transformer. */
	public static java.lang.reflect.Method[] round15RawDeclaredMethods(Class<?> clazz, java.lang.reflect.Method[] methods) {
		Round15Sink sink = ROUND15_SINK.get();
		if (sink != null) try { sink.methods("RAW_REFLECTION_RESULT", clazz, methods); } catch (Throwable ignored) { }
		return methods;
	}

	public static void round15ReflectionUtilsReturn(java.lang.reflect.Method[] methods, Class<?> clazz) {
		Round15Sink sink = ROUND15_SINK.get();
		if (sink != null) try { sink.methods("REFLECTIONUTILS_RETURN", clazz, methods); } catch (Throwable ignored) { }
	}

	public static boolean round15Filter(java.lang.reflect.Method method, boolean accepted) {
		Round15Sink sink = ROUND15_SINK.get();
		if (sink != null) try { sink.filter(method, accepted); } catch (Throwable ignored) { }
		return accepted;
	}

	public static void round15Callback(java.lang.reflect.Method method) {
		Round15Sink sink = ROUND15_SINK.get();
		if (sink != null) try { sink.callback(method); } catch (Throwable ignored) { }
	}

	public static void round15SearchCandidates(java.util.List<?> methods) {
		Round15Sink sink = ROUND15_SINK.get();
		if (sink != null) try { sink.searchCandidates(methods); } catch (Throwable ignored) { }
	}

	public static void round15TestEvent(String event) {
		Round15Sink sink = ROUND15_SINK.get();
		if (sink != null) try { sink.testEvent(event); } catch (Throwable ignored) { }
	}

	public static Round15Registration installRound15(Round15Sink sink) {
		Objects.requireNonNull(sink, "sink");
		if (!ROUND15_SINK.compareAndSet(null, sink)) throw new IllegalStateException("ROUND 15 sink already installed");
		return new Round15Registration(sink);
	}

	public interface Round15Sink {
		void methods(String stage, Class<?> clazz, java.lang.reflect.Method[] methods);
		void filter(java.lang.reflect.Method method, boolean accepted);
		void callback(java.lang.reflect.Method method);
		void searchCandidates(java.util.List<?> methods);
		void testEvent(String event);
	}

	public static final class Round15Registration implements AutoCloseable {
		private final Round15Sink installed;
		private Round15Registration(Round15Sink installed) { this.installed = installed; }
		@Override public void close() { ROUND15_SINK.compareAndSet(installed, null); }
	}


	public static Round13Registration installRound13(Round13Sink sink) {
		Objects.requireNonNull(sink, "sink");
		if (!ROUND13_SINK.compareAndSet(null, sink)) throw new IllegalStateException("ROUND 13 sink already installed");
		return new Round13Registration(sink);
	}

	public interface Round13Sink {
		void enter(String method, Object receiver, Object argument);
		void exit(String method, Object receiver, Object result);
		void testEvent(String event);
	}

	public static final class Round13Registration implements AutoCloseable {
		private final Round13Sink installed;
		private Round13Registration(Round13Sink installed) { this.installed = installed; }
		@Override public void close() { ROUND13_SINK.compareAndSet(installed, null); }
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
