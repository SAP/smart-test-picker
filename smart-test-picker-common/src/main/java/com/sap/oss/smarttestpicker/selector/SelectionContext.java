// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.Set;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

/** Safe schema-v2 selector input. It deliberately contains no final selection-policy union. */
public record SelectionContext(CoverageMap map, CoverageMapRevision revision, String headRevision,
		Set<String> changedClasses, Set<String> changedPaths, Set<TestIdentity> headTests,
		Set<TestIdentity> newTests, Set<TestIdentity> deletedTests, Set<TestIdentity> changedTests)
{
	public SelectionContext
	{
		changedClasses = Set.copyOf(new TreeSet<>(changedClasses));
		changedPaths = Set.copyOf(new TreeSet<>(changedPaths));
		headTests = Set.copyOf(new TreeSet<>(headTests));
		newTests = Set.copyOf(new TreeSet<>(newTests));
		deletedTests = Set.copyOf(new TreeSet<>(deletedTests));
		changedTests = Set.copyOf(new TreeSet<>(changedTests));
	}
}
