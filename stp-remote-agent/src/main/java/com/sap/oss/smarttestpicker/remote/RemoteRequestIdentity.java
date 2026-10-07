// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;

/** STP-owned logical test/request identity carried in standard OpenTelemetry Baggage. */
public record RemoteRequestIdentity(String testSuiteId, String testId, String requestId) {
	public static final String SUITE_BAGGAGE_KEY = "stp.test.suite.id";
	public static final String TEST_BAGGAGE_KEY = "stp.test.id";
	public static final String REQUEST_BAGGAGE_KEY = "stp.request.id";

	public RemoteRequestIdentity {
		validate(testSuiteId, "TestSuiteID");
		validate(testId, "TestID");
		validate(requestId, "RequestID");
	}

	public static RemoteRequestIdentity fromBaggage(Baggage baggage) {
		String suite = baggage.getEntryValue(SUITE_BAGGAGE_KEY);
		String test = baggage.getEntryValue(TEST_BAGGAGE_KEY);
		String request = baggage.getEntryValue(REQUEST_BAGGAGE_KEY);
		if (suite == null && test == null && request == null) return null;
		return new RemoteRequestIdentity(suite, test, request);
	}

	public Context toBaggage(Context parent) {
		Baggage baggage = Baggage.fromContext(parent).toBuilder()
				.put(SUITE_BAGGAGE_KEY, testSuiteId)
				.put(TEST_BAGGAGE_KEY, testId)
				.put(REQUEST_BAGGAGE_KEY, requestId)
				.build();
		return baggage.storeInContext(parent);
	}

	static boolean valid(String value) {
		if (value == null || value.isBlank() || value.length() > 256) return false;
		for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
		return true;
	}

	private static void validate(String value, String name) {
		if (!valid(value)) throw new IllegalArgumentException(name + " must be nonblank, at most 256 characters, and contain no ISO control characters");
	}
}
