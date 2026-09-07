// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

/** Authoritative logical tests runnable by the local execution target at the fixed selection head. */
public record HeadTestInventory(Set<TestIdentity> runnableTests)
{
	public HeadTestInventory
	{
		Objects.requireNonNull(runnableTests, "runnableTests");
		if (runnableTests.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Head inventory contains a null test identity");
		runnableTests = Set.copyOf(new TreeSet<>(runnableTests));
	}

	/** Preserves duplicate detection at inventory-provider boundaries. */
	public static HeadTestInventory from(Collection<TestIdentity> tests)
	{
		Objects.requireNonNull(tests, "tests");
		HashSet<TestIdentity> unique = new HashSet<>();
		for (TestIdentity test : tests)
		{
			if (test == null) throw new IllegalArgumentException("Head inventory contains a null test identity");
			if (!unique.add(test)) throw new IllegalArgumentException("Duplicate head test identity: " + test);
		}
		return new HeadTestInventory(unique);
	}
}
