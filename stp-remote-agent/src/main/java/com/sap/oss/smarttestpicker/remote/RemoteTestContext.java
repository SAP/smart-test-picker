// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.lang.reflect.Method;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** Request-scoped remote identity, independent of any test-runner lifecycle. */
public final class RemoteTestContext {
	private static final ThreadLocal<RemoteRequestIdentity> CURRENT = new ThreadLocal<>();
	private static final String REQUEST_IDENTITY_ATTRIBUTE = "com.sap.oss.smarttestpicker.remote.requestIdentity";
	private static final ReferenceQueue<Object> LISTENER_QUEUE = new ReferenceQueue<>();
	private static final Map<ListenerReference, RemoteRequestIdentity> LISTENER_IDENTITIES = new HashMap<>();

	private RemoteTestContext() { }

	public static Scope enter(Object request) {
		RemoteRequestIdentity previous = CURRENT.get();
		Object servletRequest = servletRequest(request);
		RemoteRequestIdentity identity = readAttribute(servletRequest);
		if (identity == null) {
			identity = readHeaders(servletRequest);
			if (identity != null) writeAttribute(servletRequest, identity);
		} else {
			RemoteRequestIdentity header = readHeaders(servletRequest);
			if (header != null && !identity.equals(header))
				System.err.println("[stp-remote-agent] request-identity-header-conflict: preserving accepted request identity");
		}
		install(identity);
		return new Scope(previous);
	}

	public static Scope enterAsyncListener(Object event) {
		RemoteRequestIdentity previous = CURRENT.get();
		RemoteRequestIdentity identity = readAttribute(asyncEventRequest(event));
		install(identity);
		return new Scope(previous);
	}

	public static RemoteRequestIdentity currentIdentity() { return CURRENT.get(); }
	/** Compatibility accessor for diagnostics/fixtures; identity storage remains structured. */
	public static String currentId() { RemoteRequestIdentity value = CURRENT.get(); return value == null ? null : value.testId(); }

	public static void associateListener(Object listener, RemoteRequestIdentity identity) {
		if (listener == null) return;
		synchronized (LISTENER_IDENTITIES) {
			expungeListeners();
			ListenerReference lookup = new ListenerReference(listener, true);
			if (identity != null) {
				if (LISTENER_IDENTITIES.containsKey(lookup)) LISTENER_IDENTITIES.put(lookup, identity);
				else LISTENER_IDENTITIES.put(new ListenerReference(listener), identity);
			} else LISTENER_IDENTITIES.remove(lookup);
		}
	}

	public static Scope enterListenerCallback(Object listener) {
		RemoteRequestIdentity previous = CURRENT.get();
		RemoteRequestIdentity identity;
		synchronized (LISTENER_IDENTITIES) {
			expungeListeners();
			identity = LISTENER_IDENTITIES.get(new ListenerReference(listener, true));
		}
		install(identity);
		return new Scope(previous);
	}

	public static RemoteRequestIdentity capture() { return CURRENT.get(); }

	public static Runnable wrap(Runnable task) {
		if (task == null) return null;
		RemoteRequestIdentity captured = capture();
		return () -> { RemoteRequestIdentity previous = CURRENT.get(); install(captured); try { task.run(); } finally { install(previous); } };
	}

	public static <T> Callable<T> wrap(Callable<T> task) {
		if (task == null) return null;
		RemoteRequestIdentity captured = capture();
		return () -> { RemoteRequestIdentity previous = CURRENT.get(); install(captured); try { return task.call(); } finally { install(previous); } };
	}

	public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
		if (task == null) return null;
		RemoteRequestIdentity captured = capture();
		return () -> { RemoteRequestIdentity previous = CURRENT.get(); install(captured); try { return task.get(); } finally { install(previous); } };
	}

	public static <T, R> Function<T, R> wrapFunction(Function<T, R> task) {
		if (task == null) return null;
		RemoteRequestIdentity captured = capture();
		return value -> { RemoteRequestIdentity previous = CURRENT.get(); install(captured); try { return task.apply(value); } finally { install(previous); } };
	}

	public static ScheduledFuture<?> schedule(ScheduledExecutorService executor, Runnable task, long delay, TimeUnit unit) { return executor.schedule(wrap(task), delay, unit); }
	public static <T> ScheduledFuture<T> schedule(ScheduledExecutorService executor, Callable<T> task, long delay, TimeUnit unit) { return executor.schedule(wrap(task), delay, unit); }
	public static ScheduledFuture<?> scheduleAtFixedRate(ScheduledExecutorService executor, Runnable task, long initialDelay, long period, TimeUnit unit) { return executor.scheduleAtFixedRate(wrap(task), initialDelay, period, unit); }
	public static ScheduledFuture<?> scheduleWithFixedDelay(ScheduledExecutorService executor, Runnable task, long initialDelay, long delay, TimeUnit unit) { return executor.scheduleWithFixedDelay(wrap(task), initialDelay, delay, unit); }

	private static void install(RemoteRequestIdentity identity) { if (identity == null) CURRENT.remove(); else CURRENT.set(identity); }

	private static RemoteRequestIdentity readHeaders(Object request) {
		if (request == null) return null;
		String suite = readHeader(request, RemoteRequestIdentity.TEST_SUITE_HEADER);
		String test = readHeader(request, RemoteRequestIdentity.TEST_ID_HEADER);
		String requestId = readHeader(request, RemoteRequestIdentity.REQUEST_ID_HEADER);
		if (suite == null && test == null && requestId == null) return null;
		try { return new RemoteRequestIdentity(suite, test, requestId); }
		catch (IllegalArgumentException invalid) {
			System.err.println("[stp-remote-agent] invalid-request-identity: expected all three valid STP identity headers");
			return null;
		}
	}

	static String readHeader(Object request, String header) {
		Object value = invoke(request, "getHeader", new Class<?>[] { String.class }, header);
		return value instanceof String text ? text : null;
	}

	private static Object servletRequest(Object requestOrEvent) {
		if (requestOrEvent == null) return null;
		if (hasMethod(requestOrEvent, "getHeader", String.class)) return requestOrEvent;
		return invoke(requestOrEvent, "getServletRequest", new Class<?>[0]);
	}

	private static Object asyncEventRequest(Object event) {
		if (event == null) return null;
		Object supplied = invoke(event, "getSuppliedRequest", new Class<?>[0]);
		if (readAttribute(supplied) != null) return supplied;
		Object asyncContext = invoke(event, "getAsyncContext", new Class<?>[0]);
		Object request = invoke(asyncContext, "getRequest", new Class<?>[0]);
		return readAttribute(request) != null ? request : supplied;
	}

	private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) {
		if (target == null) return null;
		try { Method method = target.getClass().getMethod(name, parameterTypes); if (!method.canAccess(target)) method.trySetAccessible(); return method.invoke(target, args); }
		catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
	}

	private static boolean hasMethod(Object target, String name, Class<?>... parameters) {
		try { target.getClass().getMethod(name, parameters); return true; } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
	}

	private static RemoteRequestIdentity readAttribute(Object request) {
		Object value = invoke(request, "getAttribute", new Class<?>[] { String.class }, REQUEST_IDENTITY_ATTRIBUTE);
		return value instanceof RemoteRequestIdentity identity ? identity : null;
	}

	private static void writeAttribute(Object request, RemoteRequestIdentity identity) {
		invoke(request, "setAttribute", new Class<?>[] { String.class, Object.class }, REQUEST_IDENTITY_ATTRIBUTE, identity);
	}

	private static void expungeListeners() {
		ListenerReference reference;
		while ((reference = (ListenerReference) LISTENER_QUEUE.poll()) != null) LISTENER_IDENTITIES.remove(reference);
	}

	private static final class ListenerReference extends WeakReference<Object> {
		private final int hash;
		private ListenerReference(Object listener) { super(listener, LISTENER_QUEUE); hash = System.identityHashCode(listener); }
		private ListenerReference(Object listener, boolean lookup) { super(listener); hash = System.identityHashCode(listener); }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) { if (this == other) return true; if (!(other instanceof ListenerReference reference)) return false; Object listener = get(); return listener != null && listener == reference.get(); }
	}

	public static final class Scope implements AutoCloseable {
		private final RemoteRequestIdentity previous;
		private boolean closed;
		private Scope(RemoteRequestIdentity previous) { this.previous = previous; }
		@Override public void close() { if (closed) return; closed = true; install(previous); }
	}
}
