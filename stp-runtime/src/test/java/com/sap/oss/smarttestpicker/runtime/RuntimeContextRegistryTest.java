// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContextRegistryTest {
	@Test
	void currentIsEmptyWithoutInstallation() {
		assertTrue(RuntimeContextRegistry.current().isEmpty());
	}

	@Test
	void installsAndClosesOneService() {
		RuntimeContextService service = service("one");
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(service)) {
			assertSame(service, RuntimeContextRegistry.current().orElseThrow());
		}
		assertTrue(RuntimeContextRegistry.current().isEmpty());
	}

	@Test
	void rejectsDuplicateInstallationClearly() {
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(service("one"))) {
			IllegalStateException failure = assertThrows(IllegalStateException.class,
					() -> RuntimeContextRegistry.install(service("two")));
			assertTrue(failure.getMessage().contains("already installed"));
		}
	}

	@Test
	void staleCloseCannotRemoveNewerService() {
		RuntimeContextRegistry.Registration stale = RuntimeContextRegistry.install(service("old"));
		stale.close();
		RuntimeContextService current = service("new");
		try (RuntimeContextRegistry.Registration active = RuntimeContextRegistry.install(current)) {
			stale.close();
			assertSame(current, RuntimeContextRegistry.current().orElseThrow());
		}
		assertTrue(RuntimeContextRegistry.current().isEmpty());
	}

	@Test
	void testScopedInstallationIsReplaceableAfterCleanup() {
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(service("first"))) {
			assertTrue(RuntimeContextRegistry.current().isPresent());
		}
		RuntimeContextService second = service("second");
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(second)) {
			assertSame(second, RuntimeContextRegistry.current().orElseThrow());
		}
		assertTrue(RuntimeContextRegistry.current().isEmpty());
	}

	private static RuntimeContextService service(String id) {
		return new RuntimeContextService(new RuntimeEventAggregator("run-" + id, "jvm-" + id));
	}
}
