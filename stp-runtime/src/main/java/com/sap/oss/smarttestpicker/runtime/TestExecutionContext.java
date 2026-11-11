// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;

import java.util.Objects;

/** Logical test identity and shared owner state captured at an asynchronous submission boundary. */
public record TestExecutionContext(TestIdentity testIdentity, long logicalContextId, MethodHitOwner methodHits) {
	public TestExecutionContext {
		Objects.requireNonNull(testIdentity, "testIdentity");
		Objects.requireNonNull(methodHits, "methodHits");
	}

	public TestExecutionContext(TestIdentity testIdentity, long logicalContextId) {
		this(testIdentity, logicalContextId, new MethodHitOwner());
	}

	public TestExecutionContext(TestIdentity testIdentity) {
		this(testIdentity, 0L);
	}
}
