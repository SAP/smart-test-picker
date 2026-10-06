// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

/** Immutable identity of one concrete HTTP request belonging to one test execution. */
public record RemoteRequestIdentity(String testSuiteId, String testId, String requestId) {
	public static final String TEST_SUITE_HEADER = "X-STP-Test-Suite-Id";
	public static final String TEST_ID_HEADER = "X-STP-Test-Id";
	public static final String REQUEST_ID_HEADER = "X-STP-Request-Id";

	public RemoteRequestIdentity {
		validate(testSuiteId, "TestSuiteID");
		validate(testId, "TestID");
		validate(requestId, "RequestID");
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
