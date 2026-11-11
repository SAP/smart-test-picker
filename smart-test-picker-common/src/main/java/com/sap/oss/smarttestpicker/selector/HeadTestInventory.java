// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

/** Authoritative complete logical test set for one concrete test target at one concrete revision. */
public record HeadTestInventory(String revision, Set<TestIdentity> runnableTests)
{
	public HeadTestInventory
	{
		Objects.requireNonNull(runnableTests, "runnableTests");
		if (runnableTests.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Head inventory contains a null test identity");
		runnableTests = Set.copyOf(new TreeSet<>(runnableTests));
	}

	/** Compatibility for local/worktree callers whose inventory predates revision provenance. */
	public HeadTestInventory(Set<TestIdentity> runnableTests) { this(null, runnableTests); }

	/** Preserves duplicate detection at inventory-provider boundaries. */
	public static HeadTestInventory from(Collection<TestIdentity> tests)
	{
		return from(null, tests);
	}

	/** Creates an inventory bound to the caller-supplied frozen revision. */
	public static HeadTestInventory atRevision(String revision, Collection<TestIdentity> tests)
	{
		if (revision == null || revision.isBlank())
			throw new IllegalArgumentException("Head inventory revision is missing");
		return from(revision, tests);
	}

	private static HeadTestInventory from(String revision, Collection<TestIdentity> tests)
	{
		Objects.requireNonNull(tests, "tests");
		HashSet<TestIdentity> unique = new HashSet<>();
		for (TestIdentity test : tests)
		{
			if (test == null) throw new IllegalArgumentException("Head inventory contains a null test identity");
			if (!unique.add(test)) throw new IllegalArgumentException("Duplicate head test identity: " + test);
		}
		return new HeadTestInventory(revision, unique);
	}
}
