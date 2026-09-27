// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.execution;

/** Only causes with a concrete JZC-02A execution path. */
public enum ExecutionFallbackCause
{
	AMBIGUOUS_TEST_IDENTITY,
	UNKNOWN_EXECUTION_SHAPE,
	UNSUPPORTED_RUNNER,
	NESTED_IDENTITY_UNRESOLVED,
	INHERITED_METHOD_UNRESOLVED,
	STALE_METHOD_IDENTITY,
	UNMAPPED_TEST_CLASS,
	FILTER_TOO_LARGE,
	UNREPRESENTABLE_SELECTOR,
	MODULE_SCOPE_UNRESOLVED,
	CLASS_LEVEL_SELECTION_REQUESTED
}
