// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;

import java.util.Objects;

/** Immutable logical test context captured at an asynchronous submission boundary. */
public record TestExecutionContext(TestIdentity testIdentity, long logicalContextId) {
	public TestExecutionContext {
		Objects.requireNonNull(testIdentity, "testIdentity");
	}

	public TestExecutionContext(TestIdentity testIdentity) {
		this(testIdentity, 0L);
	}
}
