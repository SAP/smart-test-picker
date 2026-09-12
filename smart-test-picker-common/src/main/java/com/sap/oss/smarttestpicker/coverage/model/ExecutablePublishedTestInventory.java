// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Set;
import java.util.TreeSet;

/** Explicit inventory views for schema v3. The logical view is reporting-only. */
public final class ExecutablePublishedTestInventory
{
	private ExecutablePublishedTestInventory() {}

	public static Set<ExecutableTestIdentity> executable(ExecutableCoverageMap map)
	{
		TreeSet<ExecutableTestIdentity> result = new TreeSet<>(map.tests().keySet());
		map.unmapped().forEach(value -> result.add(value.test()));
		return Set.copyOf(result);
	}

	public static Set<TestIdentity> logicalView(Set<ExecutableTestIdentity> executableTests)
	{
		TreeSet<TestIdentity> result = new TreeSet<>();
		executableTests.forEach(value -> result.add(value.test()));
		return Set.copyOf(result);
	}
}
