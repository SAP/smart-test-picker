// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Revision- and shard-bound schema-v3 collector contract. */
public record ExecutableCoverageFragment(int schemaVersion, CoverageMapRevision revision, ShardId shardId,
		Map<ExecutableTestIdentity, TestCoverage> tests, List<ExecutableUnmappedTest> unmapped,
		List<SetupScope> setupScopes, boolean collectionCompleted)
{
	public ExecutableCoverageFragment
	{
		tests = Map.copyOf(new TreeMap<>(tests));
		unmapped = unmapped.stream().sorted((a, b) -> a.test().compareTo(b.test())).toList();
		setupScopes = setupScopes.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
	}

	public boolean hasSameRevision(ExecutableCoverageFragment other) { return revision.equals(other.revision); }
}
