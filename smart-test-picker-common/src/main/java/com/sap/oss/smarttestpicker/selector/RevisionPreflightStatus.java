// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

/** Outcome of revision preflight: eligible selection, outdated base, full suite, or error. */
public enum RevisionPreflightStatus
{
	ELIGIBLE,
	BASE_OUT_OF_DATE,
	FULL_SUITE,
	ERROR
}
