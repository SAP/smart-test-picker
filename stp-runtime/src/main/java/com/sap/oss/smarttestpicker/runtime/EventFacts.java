// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.RuntimeEvent;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedEvent;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedReason;

final class EventFacts {
	private EventFacts() {
	}

	static UnattributedEvent unattributed(UnattributedReason reason, RuntimeEvent event) {
		return new UnattributedEvent(reason, type(event), identity(event), event.evidence());
	}

	static String type(RuntimeEvent event) {
		if (event instanceof MethodHitEvent) return "METHOD";
		return "UNATTRIBUTED";
	}

	static String identity(RuntimeEvent event) {
		if (event instanceof MethodHitEvent value) return value.method().canonicalKey();
		if (event instanceof UnattributedEvent value) return value.eventIdentity();
		throw new IllegalArgumentException("unsupported runtime event: " + event.getClass().getName());
	}
}
