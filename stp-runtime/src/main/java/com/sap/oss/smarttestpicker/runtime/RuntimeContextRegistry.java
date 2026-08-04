// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local ownership bridge for integrations that must discover the one
 * explicitly installed runtime context. This registry never creates a fallback
 * service.
 */
public final class RuntimeContextRegistry {
	private static final AtomicReference<RuntimeContextService> SERVICE = new AtomicReference<>();

	private RuntimeContextRegistry() {
	}

	public static Optional<RuntimeContextService> current() {
		return Optional.ofNullable(SERVICE.get());
	}

	public static Registration install(RuntimeContextService service) {
		Objects.requireNonNull(service, "service");
		if (!SERVICE.compareAndSet(null, service)) {
			throw new IllegalStateException("a runtime context service is already installed");
		}
		return new Registration(service);
	}

	public static final class Registration implements AutoCloseable {
		private final RuntimeContextService installed;
		private final AtomicBoolean closed = new AtomicBoolean();

		private Registration(RuntimeContextService installed) {
			this.installed = installed;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				SERVICE.compareAndSet(installed, null);
			}
		}
	}
}
