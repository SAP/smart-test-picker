// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

/** Logical test whose coverage is unavailable, with an explicit execution or collection reason. */
public record UnmappedTest(TestIdentity test, UnmappedReason reason)
{
	public UnmappedTest
	{
		if (test == null || reason == null) throw new IllegalArgumentException("unmapped test and reason are required");
	}
}
