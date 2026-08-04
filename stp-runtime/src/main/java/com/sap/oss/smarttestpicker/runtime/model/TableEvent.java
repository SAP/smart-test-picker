// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

import java.util.Objects;

public record TableEvent(String tableName, TableAccess access, Evidence evidence) implements RuntimeEvent {
	public TableEvent {
		Objects.requireNonNull(tableName, "tableName");
		Objects.requireNonNull(access, "access");
		Objects.requireNonNull(evidence, "evidence");
		if (access == TableAccess.MAPPED && evidence.certainty() != Certainty.INFERRED) {
			throw new IllegalArgumentException("mapped tables must use inferred evidence");
		}
		if (access != TableAccess.MAPPED && evidence.certainty() != Certainty.OBSERVED) {
			throw new IllegalArgumentException("observed tables must use observed evidence");
		}
	}
}

