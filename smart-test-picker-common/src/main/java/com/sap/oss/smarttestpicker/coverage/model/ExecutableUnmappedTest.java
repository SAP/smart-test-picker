// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

/** Target-qualified test whose coverage is unavailable, with its explicit collection or execution reason. */
public record ExecutableUnmappedTest(ExecutableTestIdentity test, UnmappedReason reason)
{
	public ExecutableUnmappedTest
	{
		if (test == null || reason == null) throw new IllegalArgumentException("unmapped test and reason are required");
	}
}
