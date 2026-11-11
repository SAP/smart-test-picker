// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Collector facts about executable occurrences; it deliberately contains no expected state. */
public record ExecutableCollectionSummary(Set<ExecutableTestIdentity> reportedTests,
		List<ShardId> completedShardReports, Set<ExecutableTestIdentity> duplicateTests)
{
	public ExecutableCollectionSummary
	{
		reportedTests = Set.copyOf(new TreeSet<>(reportedTests));
		completedShardReports = List.copyOf(completedShardReports);
		duplicateTests = Set.copyOf(new TreeSet<>(duplicateTests));
	}
}
