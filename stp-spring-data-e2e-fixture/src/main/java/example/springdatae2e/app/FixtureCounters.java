// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springdatae2e.app;

import java.util.concurrent.atomic.AtomicInteger;

public final class FixtureCounters {
	public static final AtomicInteger CACHED_EXECUTIONS = new AtomicInteger();
	public static final AtomicInteger CACHED_ASSERTED_EXECUTIONS = new AtomicInteger();
	public static final RuntimeException EXPECTED_FAILURE = new RuntimeException("fixture-repository-failure");
	private FixtureCounters() { }
}
