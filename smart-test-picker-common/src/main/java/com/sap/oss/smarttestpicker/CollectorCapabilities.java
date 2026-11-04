// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

/** Collector semantics within its supported topology, not a universal attribution-soundness guarantee. */
public record CollectorCapabilities(boolean classCoverage, boolean exactMethodCoverage,
		boolean setupScopes, boolean descriptorAwareMethods, boolean directSchemaV2Fragment) {
}
