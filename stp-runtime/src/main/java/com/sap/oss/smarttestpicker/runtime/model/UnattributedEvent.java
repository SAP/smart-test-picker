// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

import java.util.Objects;

public record UnattributedEvent(UnattributedReason reason, String eventType, String eventIdentity,
		Evidence evidence) implements RuntimeEvent {
	public UnattributedEvent {
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(eventType, "eventType");
		Objects.requireNonNull(eventIdentity, "eventIdentity");
		Objects.requireNonNull(evidence, "evidence");
	}
}

