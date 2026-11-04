// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

/**
 * Output capabilities available through this backend's supported mapping paths, not guarantees for
 * every legacy format or of temporal attribution soundness. In particular, method-entry fidelity
 * and descriptor encoding are independent: a probe-based collector can preserve descriptors for
 * reported methods while omitting an entered method that exits before reaching a probe.
 *
 * @param classCoverage a fragment records the production class exercised by a passing leaf test
 * @param exactMethodCoverage a leaf fragment records an entered production method even when an implicit exception prevents its exit probe
 * @param setupScopes a fragment retains setup-only production coverage in an explicitly owned setup scope, not merely a raw setup session
 * @param descriptorAwareMethods fragment method identities retain JVM parameter and return descriptors, distinguishing exercised and unexercised overloads
 * @param directSchemaV2Fragment a supported collection-only run emits a schema-v2 fragment before any report conversion or fragment-assembly task
 */
public record CollectorCapabilities(boolean classCoverage, boolean exactMethodCoverage,
		boolean setupScopes, boolean descriptorAwareMethods, boolean directSchemaV2Fragment) {
}
