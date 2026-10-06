// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.lang.reflect.Method;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

/** Request-scoped remote test identity; deliberately independent of JUnit runtime lifecycle. */
public final class RemoteTestContext {
	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
	private static final int MAX_ID_LENGTH = 256;
	private static final String REQUEST_ID_ATTRIBUTE = "com.sap.oss.smarttestpicker.remote.testExecutionId";
	private static final ReferenceQueue<Object> LISTENER_QUEUE = new ReferenceQueue<>();
	private static final Map<ListenerReference, String> LISTENER_IDS = new HashMap<>();

	private RemoteTestContext() { }

	public static Scope enter(Object request, String header) {
		String previous = CURRENT.get();
		Object servletRequest = servletRequest(request);
		String id = readAttribute(servletRequest);
		if (!valid(id)) {
			id = readHeader(servletRequest, header);
			if (valid(id)) writeAttribute(servletRequest, id);
		}
		// A nested request without a valid STP ID suppresses its parent's identity.
		CURRENT.remove();
		if (valid(id)) CURRENT.set(id);
		return new Scope(previous);
	}

	/** Enters an AsyncListener callback using only the accepted ID on its associated request. */
	public static Scope enterAsyncListener(Object event) {
		String previous = CURRENT.get();
		Object request = asyncEventRequest(event);
		String id = readAttribute(request);
		install(valid(id) ? id : null);
		return new Scope(previous);
	}

	public static String currentId() { return CURRENT.get(); }

	/** Associates an unchanged Servlet I/O listener instance with the active request identity. */
	public static void associateListener(Object listener, String id) {
		if (listener == null) return;
		synchronized (LISTENER_IDS) {
			expungeListeners();
			ListenerReference lookup = new ListenerReference(listener, true);
			if (valid(id)) {
				if (LISTENER_IDS.containsKey(lookup)) LISTENER_IDS.put(lookup, id);
				else LISTENER_IDS.put(new ListenerReference(listener), id);
			} else LISTENER_IDS.remove(lookup);
		}
	}

	/** Temporarily installs the identity captured when a Servlet I/O listener was registered. */
	public static Scope enterListenerCallback(Object listener) {
		String previous = CURRENT.get();
		String id;
		synchronized (LISTENER_IDS) {
			expungeListeners();
			id = LISTENER_IDS.get(new ListenerReference(listener, true));
		}
		install(valid(id) ? id : null);
		return new Scope(previous);
	}

	/** Captures the opaque ID active at submission time for Servlet-managed async work. */
	public static String capture() { return CURRENT.get(); }

	/** Attaches the submission-time ID to a task and restores the worker's prior context afterward. */
	public static Runnable wrap(Runnable task) {
		if (task == null) return null;
		String captured = capture();
		return () -> {
			String previous = CURRENT.get();
			install(captured);
			try { task.run(); }
			finally { install(previous); }
		};
	}

	/** Attaches the submission-time remote identity to a Callable and restores the worker afterward. */
	public static <T> Callable<T> wrap(Callable<T> task) {
		if (task == null) return null;
		String captured = capture();
		return () -> {
			String previous = CURRENT.get();
			install(captured);
			try { return task.call(); }
			finally { install(previous); }
		};
	}

	/** Captures the current remote identity for a CompletableFuture supplier stage. */
	public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
		if (task == null) return null;
		String captured = capture();
		return () -> {
			String previous = CURRENT.get();
			install(captured);
			try { return task.get(); }
			finally { install(previous); }
		};
	}

	/** Captures the current remote identity for a CompletableFuture function stage. */
	public static <T, R> Function<T, R> wrapFunction(Function<T, R> task) {
		if (task == null) return null;
		String captured = capture();
		return value -> {
			String previous = CURRENT.get();
			install(captured);
			try { return task.apply(value); }
			finally { install(previous); }
		};
	}

	private static void install(String id) {
		if (id == null) CURRENT.remove();
		else CURRENT.set(id);
	}

	static String readHeader(Object request, String header) {
		if (request == null) return null;
		try {
			Method method = request.getClass().getMethod("getHeader", String.class);
			if (!method.canAccess(request)) method.trySetAccessible();
			Object value = method.invoke(request, header);
			return value instanceof String text ? text : null;
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	private static Object servletRequest(Object requestOrEvent) {
		if (requestOrEvent == null) return null;
		if (hasMethod(requestOrEvent, "getHeader", String.class)) return requestOrEvent;
		try {
			Method method = requestOrEvent.getClass().getMethod("getServletRequest");
			if (!method.canAccess(requestOrEvent)) method.trySetAccessible();
			return method.invoke(requestOrEvent);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	private static Object asyncEventRequest(Object event) {
		if (event == null) return null;
		Object supplied = invokeNoArgs(event, "getSuppliedRequest");
		if (valid(readAttribute(supplied))) return supplied;
		Object asyncContext = invokeNoArgs(event, "getAsyncContext");
		Object request = invokeNoArgs(asyncContext, "getRequest");
		return valid(readAttribute(request)) ? request : supplied;
	}

	private static Object invokeNoArgs(Object target, String name) {
		if (target == null) return null;
		try {
			Method method = target.getClass().getMethod(name);
			if (!method.canAccess(target)) method.trySetAccessible();
			return method.invoke(target);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	private static boolean hasMethod(Object target, String name, Class<?>... parameters) {
		try { target.getClass().getMethod(name, parameters); return true; }
		catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
	}

	private static String readAttribute(Object request) {
		try {
			Method method = request.getClass().getMethod("getAttribute", String.class);
			if (!method.canAccess(request)) method.trySetAccessible();
			Object value = method.invoke(request, REQUEST_ID_ATTRIBUTE);
			return value instanceof String text ? text : null;
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	private static void writeAttribute(Object request, String id) {
		try {
			Method method = request.getClass().getMethod("setAttribute", String.class, Object.class);
			if (!method.canAccess(request)) method.trySetAccessible();
			method.invoke(request, REQUEST_ID_ATTRIBUTE, id);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// Some request wrappers hide mutation; the header remains a valid fallback.
		}
	}

	private static boolean valid(String value) {
		return value != null && !value.isBlank() && value.length() <= MAX_ID_LENGTH && printable(value);
	}

	private static boolean printable(String value) {
		for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
		return true;
	}

	private static void expungeListeners() {
		ListenerReference reference;
		while ((reference = (ListenerReference) LISTENER_QUEUE.poll()) != null) LISTENER_IDS.remove(reference);
	}

	private static final class ListenerReference extends WeakReference<Object> {
		private final int hash;
		private ListenerReference(Object listener) { super(listener, LISTENER_QUEUE); hash = System.identityHashCode(listener); }
		private ListenerReference(Object listener, boolean lookup) { super(listener); hash = System.identityHashCode(listener); }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof ListenerReference reference)) return false;
			Object listener = get();
			return listener != null && listener == reference.get();
		}
	}

	public static final class Scope implements AutoCloseable {
		private final String previous;
		private boolean closed;
		private Scope(String previous) { this.previous = previous; }
		@Override public void close() {
			if (closed) return;
			closed = true;
			CURRENT.remove();
			if (previous != null) CURRENT.set(previous);
		}
	}
}
