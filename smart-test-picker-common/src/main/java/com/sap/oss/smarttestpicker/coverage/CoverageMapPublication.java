// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.util.List;
import java.util.Set;

import com.sap.oss.smarttestpicker.coverage.model.CollectionExpectation;
import com.sap.oss.smarttestpicker.coverage.model.CollectionSummary;
import com.sap.oss.smarttestpicker.coverage.model.Completeness;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.PublishedTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestInventory;

/** Minimal orchestration boundary for publishing an independently discovered logical inventory. */
public final class CoverageMapPublication
{
	private CoverageMapPublication() {}

	public static CoverageMap alignExpectedInventory(CoverageMap collectedMap, TestInventory inventory,
			Set<TestIdentity> intentionallyNonExecutable)
	{
		if (!collectedMap.revision().equals(inventory.revision()))
			throw new IllegalArgumentException("inventory revision does not match coverage-map revision");
		Set<TestIdentity> executable = PublishedTestInventory.executable(collectedMap);
		if (!inventory.expectedTests().containsAll(executable))
			throw new IllegalArgumentException("collected executable tests are absent from expected inventory");
		if (!intentionallyNonExecutable.equals(difference(inventory.expectedTests(), executable)))
			throw new IllegalArgumentException("intentional non-execution signal must exactly account for the unreported inventory");
		Completeness old = collectedMap.completeness();
		if (old == null || !old.isComplete()) throw new IllegalArgumentException("source map must be complete");
		CollectionExpectation expectation = new CollectionExpectation(inventory, old.expectedShards(),
				intentionallyNonExecutable);
		Completeness completeness = Completeness.from(expectation,
				new CollectionSummary(executable, List.copyOf(old.completedShards()), old.duplicateTests()));
		MapStatistics statistics = collectedMap.statistics();
		MapStatistics aligned = new MapStatistics(inventory.expectedTests().size(), statistics.mappedTests(),
				statistics.unmappedTests(), statistics.setupScopes(), statistics.classEdges(), statistics.methodEdges());
		return new CoverageMap(collectedMap.schemaVersion(), collectedMap.revision(), collectedMap.generatedAt(),
				collectedMap.generator(), collectedMap.tests(), collectedMap.unmapped(), collectedMap.setupScopes(),
				completeness, aligned, collectedMap.lifecycleState(), collectedMap.methodCoverageReference());
	}

	private static Set<TestIdentity> difference(Set<TestIdentity> left, Set<TestIdentity> right)
	{
		java.util.TreeSet<TestIdentity> result = new java.util.TreeSet<>(left);
		result.removeAll(right);
		return Set.copyOf(result);
	}
}
