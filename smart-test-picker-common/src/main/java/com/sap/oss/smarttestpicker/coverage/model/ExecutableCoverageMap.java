// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable schema-v3 coverage map with authoritative executable ownership. */
public record ExecutableCoverageMap(int schemaVersion, CoverageMapRevision revision, Instant generatedAt,
		GeneratorProvenance generator, Map<ExecutableTestIdentity, TestCoverage> tests,
		List<ExecutableUnmappedTest> unmapped, List<SetupScope> setupScopes,
		ExecutableCompleteness completeness, MapStatistics statistics,
		CoverageMapLifecycleState lifecycleState, MethodCoverageReference methodCoverageReference)
{
	public ExecutableCoverageMap
	{
		tests = Map.copyOf(new TreeMap<>(tests));
		unmapped = unmapped.stream().sorted((a, b) -> a.test().compareTo(b.test())).toList();
		setupScopes = setupScopes.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
	}
}
