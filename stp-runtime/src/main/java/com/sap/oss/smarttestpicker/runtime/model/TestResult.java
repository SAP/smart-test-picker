// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

import java.util.Objects;

/** Immutable test outcome without retaining or serializing a Throwable. */
public record TestResult(TestExecutionStatus status, String failureType, String failureMessage) {
	public TestResult {
		Objects.requireNonNull(status, "status");
		if (status == TestExecutionStatus.SUCCESSFUL && (failureType != null || failureMessage != null)) {
			throw new IllegalArgumentException("a successful result cannot contain failure details");
		}
	}

	public static TestResult successful() {
		return new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);
	}
}

