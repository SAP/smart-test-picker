// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Set;
import java.util.TreeSet;

/** Logical inventory partitions encoded by a published map without changing schema v2. */
public final class PublishedTestInventory
{
	private PublishedTestInventory() {}

	public static Set<TestIdentity> executable(CoverageMap map)
	{
		TreeSet<TestIdentity> result = new TreeSet<>(map.tests().keySet());
		map.unmapped().forEach(value -> result.add(value.test()));
		return Set.copyOf(result);
	}

	public static Set<TestIdentity> logical(CoverageMap map)
	{
		return map.completeness() == null ? executable(map) : map.completeness().expectedTests();
	}

	public static Set<TestIdentity> intentionallyNonExecutable(CoverageMap map)
	{
		TreeSet<TestIdentity> result = new TreeSet<>(logical(map));
		result.removeAll(executable(map));
		return Set.copyOf(result);
	}
}
