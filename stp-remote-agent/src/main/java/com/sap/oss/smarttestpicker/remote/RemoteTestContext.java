// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;

/** Reads STP identity from OpenTelemetry Context and supplies explicit fallbacks only at measured gaps. */
public final class RemoteTestContext {
	private static final Map<ListenerReference, Context> LISTENER_CONTEXTS = new HashMap<>();
	private static final ReferenceQueue<Object> LISTENER_QUEUE = new ReferenceQueue<>();

	private RemoteTestContext() { }

	/** Test and fixture utility: installs structured STP identity in standard OTel Baggage. */
	public static Scope enter(RemoteRequestIdentity identity) {
		return new Scope(identity.toBaggage(withoutStpIdentity(Context.current())).makeCurrent());
	}

	public static RemoteRequestIdentity currentIdentity() {
		try { return RemoteRequestIdentity.fromBaggage(Baggage.current()); }
		catch (RuntimeException invalid) { return null; }
	}
	public static String currentId() {
		RemoteRequestIdentity identity = currentIdentity();
		return identity == null ? null : identity.testId();
	}

	/** Servlet callbacks invoked later by a container restore the Context captured at listener registration. */
	public static void associateListener(Object listener) {
		if (listener == null) return;
		synchronized (LISTENER_CONTEXTS) {
			expungeListeners();
			LISTENER_CONTEXTS.put(new ListenerReference(listener, LISTENER_QUEUE), Context.current());
		}
	}
	public static void clearListener(Object listener) {
		if (listener == null) return;
		synchronized (LISTENER_CONTEXTS) { expungeListeners(); LISTENER_CONTEXTS.remove(new ListenerReference(listener)); }
	}
	public static Scope enterListenerCallback(Object listener) {
		Context context;
		synchronized (LISTENER_CONTEXTS) {
			expungeListeners();
			context = LISTENER_CONTEXTS.get(new ListenerReference(listener));
		}
		if (context == null) context = withoutStpIdentity(Context.current());
		return new Scope(context.makeCurrent());
	}

	public static Context capture() { return Context.current(); }
	public static Runnable wrap(Runnable task) { return task == null ? null : capture().wrap(task); }
	public static <T> Callable<T> wrap(Callable<T> task) { return task == null ? null : capture().wrap(task); }
	public static <T, R> Function<T, R> wrapFunction(Function<T, R> task) { return task == null ? null : capture().wrapFunction(task); }

	public static <T> Consumer<T> wrapConsumer(Consumer<T> task) { return task == null ? null : capture().wrapConsumer(task); }
	public static <T, U, R> BiFunction<T, U, R> wrapBiFunction(BiFunction<T, U, R> task) { return task == null ? null : capture().wrapFunction(task); }
	public static <T, U> BiConsumer<T, U> wrapBiConsumer(BiConsumer<T, U> task) { return task == null ? null : capture().wrapConsumer(task); }

	public static ScheduledFuture<?> schedule(ScheduledExecutorService executor, Runnable task, long delay, TimeUnit unit) { return executor.schedule(wrap(task), delay, unit); }
	public static <T> ScheduledFuture<T> schedule(ScheduledExecutorService executor, Callable<T> task, long delay, TimeUnit unit) { return executor.schedule(wrap(task), delay, unit); }
	public static ScheduledFuture<?> scheduleAtFixedRate(ScheduledExecutorService executor, Runnable task, long initialDelay, long period, TimeUnit unit) { return executor.scheduleAtFixedRate(wrap(task), initialDelay, period, unit); }
	public static ScheduledFuture<?> scheduleWithFixedDelay(ScheduledExecutorService executor, Runnable task, long initialDelay, long delay, TimeUnit unit) { return executor.scheduleWithFixedDelay(wrap(task), initialDelay, delay, unit); }

	private static Context withoutStpIdentity(Context context) {
		Baggage baggage = Baggage.fromContext(context).toBuilder()
				.remove(RemoteRequestIdentity.SUITE_BAGGAGE_KEY)
				.remove(RemoteRequestIdentity.TEST_BAGGAGE_KEY)
				.remove(RemoteRequestIdentity.REQUEST_BAGGAGE_KEY)
				.build();
		return baggage.storeInContext(context);
	}
	private static void expungeListeners() {
		ListenerReference reference;
		while ((reference = (ListenerReference) LISTENER_QUEUE.poll()) != null) LISTENER_CONTEXTS.remove(reference);
	}
	private static final class ListenerReference extends WeakReference<Object> {
		private final int hash;
		private ListenerReference(Object listener) { super(listener); hash = System.identityHashCode(listener); }
		private ListenerReference(Object listener, ReferenceQueue<Object> queue) { super(listener, queue); hash = System.identityHashCode(listener); }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof ListenerReference reference)) return false;
			Object value = get();
			return value != null && value == reference.get();
		}
	}

	public static final class Scope implements AutoCloseable {
		private final io.opentelemetry.context.Scope delegate;
		private Scope(io.opentelemetry.context.Scope delegate) { this.delegate = delegate; }
		@Override public void close() { delegate.close(); }
	}
}
