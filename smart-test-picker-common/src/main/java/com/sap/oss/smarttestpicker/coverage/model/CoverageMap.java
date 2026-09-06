// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable semantic coverage-map model, independent of its JSON representation. */
public record CoverageMap(int schemaVersion, CoverageMapRevision revision, Instant generatedAt,
		GeneratorProvenance generator, Map<TestIdentity, TestCoverage> tests, List<UnmappedTest> unmapped,
		List<SetupScope> setupScopes, Completeness completeness, MapStatistics statistics,
		CoverageMapLifecycleState lifecycleState, MethodCoverageReference methodCoverageReference)
{
	public CoverageMap
	{
		tests = Map.copyOf(new TreeMap<>(tests));
		unmapped = unmapped.stream().sorted((a, b) -> a.test().compareTo(b.test())).toList();
		setupScopes = setupScopes.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
	}
}
