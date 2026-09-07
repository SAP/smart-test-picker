// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public record Completeness(Set<TestIdentity> expectedTests, Set<TestIdentity> reportedTests,
		Set<ShardId> expectedShards, Set<ShardId> completedShards, Set<TestIdentity> missingTests,
		Set<TestIdentity> unexpectedTests, Set<ShardId> missingShards, Set<ShardId> duplicateShards,
		Set<TestIdentity> duplicateTests)
{
	public static Completeness from(CollectionExpectation expectation, CollectionSummary collection)
	{
		TestInventory inventory = expectation.testInventory();
		Set<TestIdentity> accountedTests = new TreeSet<>(collection.reportedTests());
		accountedTests.addAll(expectation.intentionallyNonExecutableTests());
		Set<TestIdentity> missingTests = difference(inventory.expectedTests(), accountedTests);
		Set<TestIdentity> unexpectedTests = difference(accountedTests, inventory.expectedTests());
		Set<ShardId> completed = new TreeSet<>(collection.completedShardReports());
		Set<ShardId> duplicateShards = duplicates(collection.completedShardReports());
		return new Completeness(inventory.expectedTests(), accountedTests, expectation.expectedShards(),
				completed, missingTests, unexpectedTests, difference(expectation.expectedShards(), completed),
				duplicateShards, collection.duplicateTests());
	}

	public Completeness
	{
		expectedTests = immutable(expectedTests); reportedTests = immutable(reportedTests);
		expectedShards = immutable(expectedShards); completedShards = immutable(completedShards);
		missingTests = immutable(missingTests); unexpectedTests = immutable(unexpectedTests);
		missingShards = immutable(missingShards); duplicateShards = immutable(duplicateShards);
		duplicateTests = immutable(duplicateTests);
	}

	public boolean isComplete()
	{
		return missingTests.isEmpty() && unexpectedTests.isEmpty() && missingShards.isEmpty()
				&& duplicateShards.isEmpty() && duplicateTests.isEmpty();
	}

	private static <T extends Comparable<? super T>> Set<T> difference(Set<T> left, Set<T> right)
	{
		TreeSet<T> result = new TreeSet<>(left); result.removeAll(right); return result;
	}
	private static <T extends Comparable<? super T>> Set<T> immutable(Set<T> values) { return Set.copyOf(new TreeSet<>(values)); }
	private static <T extends Comparable<? super T>> Set<T> duplicates(Iterable<T> values)
	{
		Set<T> seen = new HashSet<>(); TreeSet<T> duplicate = new TreeSet<>();
		for (T value : values) if (!seen.add(value)) duplicate.add(value);
		return duplicate;
	}
}
