// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Collector-side facts; it deliberately contains no expected state. */
public record CollectionSummary(Set<TestIdentity> reportedTests, List<ShardId> completedShardReports,
		Set<TestIdentity> duplicateTests)
{
	public CollectionSummary
	{
		reportedTests = Set.copyOf(new TreeSet<>(reportedTests));
		completedShardReports = List.copyOf(completedShardReports);
		duplicateTests = Set.copyOf(new TreeSet<>(duplicateTests));
	}
}
