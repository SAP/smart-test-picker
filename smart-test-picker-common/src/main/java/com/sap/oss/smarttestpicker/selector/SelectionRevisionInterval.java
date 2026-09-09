// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

/** The only safe committed selection interval after a successful 5e preflight. */
public record SelectionRevisionInterval(String effectiveBaseRevision, String effectiveHeadRevision,
		int commitDistance) {}
