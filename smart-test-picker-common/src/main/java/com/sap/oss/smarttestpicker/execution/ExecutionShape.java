// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.execution;

/** Surefire-addressable shape captured when the test is observed. */
public enum ExecutionShape
{
	ORDINARY,
	JUNIT4_PARAMETERIZED_INDEXED,
	JUNIT4_PARAMETERIZED_NAMED,
	JUPITER_PARAMETERIZED,
	UNKNOWN
}
