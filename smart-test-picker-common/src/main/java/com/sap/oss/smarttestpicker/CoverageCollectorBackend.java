// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

/** Build-tool-independent description of exactly one STP collection backend. */
public interface CoverageCollectorBackend {
	CoverageCollectorType type();

	CollectorCapabilities capabilities();
}
