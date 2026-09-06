// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Set;
import java.util.TreeSet;

public record TestCoverage(Set<String> coveredClasses, Set<String> coveredMethods, TestOutcome outcome,
		CollectionStatus collectionStatus)
{
	public TestCoverage
	{
		coveredClasses = Set.copyOf(new TreeSet<>(coveredClasses));
		coveredMethods = Set.copyOf(new TreeSet<>(coveredMethods));
		if (outcome == null || collectionStatus == null)
			throw new IllegalArgumentException("test outcome and collection status are required");
		if (collectionStatus == CollectionStatus.COLLECTION_FAILED)
			throw new IllegalArgumentException("COLLECTION_FAILED belongs in unmapped, not tests");
		if (collectionStatus == CollectionStatus.COLLECTED_EMPTY && (!coveredClasses.isEmpty() || !coveredMethods.isEmpty()))
			throw new IllegalArgumentException("COLLECTED_EMPTY cannot contain coverage");
		if (collectionStatus == CollectionStatus.COLLECTED_WITH_COVERAGE && coveredClasses.isEmpty())
			throw new IllegalArgumentException("COLLECTED_WITH_COVERAGE requires class coverage");
	}
}
