// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.lang.reflect.Method;

/** Request-scoped remote test identity; deliberately independent of JUnit runtime lifecycle. */
public final class RemoteTestContext {
	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
	private static final int MAX_ID_LENGTH = 256;

	private RemoteTestContext() { }

	public static Scope enter(Object request, String header) {
		String previous = CURRENT.get();
		String id = readHeader(request, header);
		// A nested request without a valid STP ID suppresses its parent's identity.
		CURRENT.remove();
		if (id != null && !id.isBlank() && id.length() <= MAX_ID_LENGTH && printable(id)) CURRENT.set(id);
		return new Scope(previous);
	}

	public static String currentId() { return CURRENT.get(); }

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

	private static boolean printable(String value) {
		for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
		return true;
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
