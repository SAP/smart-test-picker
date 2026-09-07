// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Revision- and shard-bound schema-v2 collector output. */
public record CoverageFragment(int schemaVersion, CoverageMapRevision revision, ShardId shardId,
		Map<TestIdentity, TestCoverage> tests, List<UnmappedTest> unmapped,
		List<SetupScope> setupScopes, boolean collectionCompleted)
{
	public CoverageFragment
	{
		tests = Map.copyOf(new TreeMap<>(tests));
		unmapped = unmapped.stream().sorted((a, b) -> a.test().compareTo(b.test())).toList();
		setupScopes = setupScopes.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
	}

	public boolean hasSameRevision(CoverageFragment other) { return revision.equals(other.revision); }
}
