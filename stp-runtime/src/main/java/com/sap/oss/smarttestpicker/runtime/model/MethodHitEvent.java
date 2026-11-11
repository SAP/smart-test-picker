// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

import java.util.Objects;

public record MethodHitEvent(Long methodId, MethodIdentity method, Evidence evidence) implements RuntimeEvent {
	public MethodHitEvent(MethodIdentity method, Evidence evidence) {
		this(null, method, evidence);
	}

	public MethodHitEvent {
		Objects.requireNonNull(method, "method");
		Objects.requireNonNull(evidence, "evidence");
	}
}
