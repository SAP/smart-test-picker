// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

/** Outcome of the explicit-commit PR selector flow. */
public enum ExplicitPrSelectionStatus
{
	SELECTION_RESULT,
	BASE_OUT_OF_DATE,
	ERROR
}
