// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

import java.util.Locale;
import java.util.Objects;

public record EndpointEvent(String httpMethod, String routePattern, MethodIdentity handler, Evidence evidence)
		implements RuntimeEvent {
	public EndpointEvent {
		httpMethod = Objects.requireNonNull(httpMethod, "httpMethod").toUpperCase(Locale.ROOT);
		Objects.requireNonNull(routePattern, "routePattern");
		Objects.requireNonNull(handler, "handler");
		Objects.requireNonNull(evidence, "evidence");
	}
}

