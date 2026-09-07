// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.util.Locale;

/** The one collector used for an STP mapping execution. */
public enum CoverageCollectorType {
	ASM,
	JACOCO;

	static CoverageCollectorType parse(Object value) {
		if (value instanceof CoverageCollectorType type) return type;
		if (value instanceof CharSequence text) {
			try {
				return valueOf(text.toString().trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
				// Use the stable, user-facing error below.
			}
		}
		throw new IllegalArgumentException("Unknown Smart Test Picker coverage collector '" + value
				+ "'. Supported values are ASM and JACOCO.");
	}
}
