// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Authoritative executable test occurrences expected at one revision. */
public record ExecutableTestInventory(CoverageMapRevision revision, Set<ExecutableTestIdentity> expectedTests)
{
	public ExecutableTestInventory
	{
		if (revision == null) throw new IllegalArgumentException("revision is required");
		Objects.requireNonNull(expectedTests, "expectedTests");
		if (expectedTests.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Executable inventory contains a null identity");
		expectedTests = Set.copyOf(new TreeSet<>(expectedTests));
	}

	public static ExecutableTestInventory from(CoverageMapRevision revision,
			Collection<ExecutableTestIdentity> tests)
	{
		Objects.requireNonNull(tests, "tests");
		HashSet<ExecutableTestIdentity> unique = new HashSet<>();
		for (ExecutableTestIdentity test : tests)
		{
			if (test == null) throw new IllegalArgumentException("Executable inventory contains a null identity");
			if (!unique.add(test)) throw new IllegalArgumentException("Duplicate executable test identity: " + test);
		}
		return new ExecutableTestInventory(revision, unique);
	}
}
