// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableCollectionExpectation;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCollectionSummary;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCompleteness;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.ExecutablePublishedTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;

/** Exact executable-ownership publication alignment for schema v3. */
public final class ExecutableCoverageMapPublication
{
	private ExecutableCoverageMapPublication() {}

	public static ExecutableCoverageMap alignExpectedInventory(ExecutableCoverageMap map,
			ExecutableTestInventory inventory, Set<ExecutableTestIdentity> intentionallyNonExecutable)
	{
		if (!map.revision().equals(inventory.revision()))
			throw new IllegalArgumentException("inventory revision does not match coverage-map revision");
		Set<ExecutableTestIdentity> reported = ExecutablePublishedTestInventory.executable(map);
		if (!inventory.expectedTests().containsAll(reported))
			throw new IllegalArgumentException("collected executable tests are absent from expected inventory");
		if (!intentionallyNonExecutable.equals(difference(inventory.expectedTests(), reported)))
			throw new IllegalArgumentException("non-execution must exactly account for every unreported executable owner");
		ExecutableCompleteness old = map.completeness();
		if (old == null || !old.isComplete()) throw new IllegalArgumentException("source map must be complete");
		ExecutableCompleteness completeness = ExecutableCompleteness.from(
				new ExecutableCollectionExpectation(inventory, old.expectedShards(), intentionallyNonExecutable),
				new ExecutableCollectionSummary(reported, List.copyOf(old.completedShards()), old.duplicateTests()));
		MapStatistics stats = map.statistics();
		return new ExecutableCoverageMap(map.schemaVersion(), map.revision(), map.generatedAt(), map.generator(),
				map.tests(), map.unmapped(), map.setupScopes(), completeness,
				new MapStatistics(inventory.expectedTests().size(), stats.mappedTests(), stats.unmappedTests(),
						stats.setupScopes(), stats.classEdges(), stats.methodEdges()),
				map.lifecycleState(), map.methodCoverageReference());
	}

	private static Set<ExecutableTestIdentity> difference(Set<ExecutableTestIdentity> left,
			Set<ExecutableTestIdentity> right)
	{
		TreeSet<ExecutableTestIdentity> result = new TreeSet<>(left); result.removeAll(right); return Set.copyOf(result);
	}
}
