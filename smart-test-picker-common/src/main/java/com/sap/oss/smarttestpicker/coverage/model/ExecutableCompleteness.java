// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Completeness over executable occurrences, never a logical-test projection. */
public record ExecutableCompleteness(Set<ExecutableTestIdentity> expectedTests,
		Set<ExecutableTestIdentity> reportedTests, Set<ShardId> expectedShards, Set<ShardId> completedShards,
		Set<ExecutableTestIdentity> missingTests, Set<ExecutableTestIdentity> unexpectedTests,
		Set<ShardId> missingShards, Set<ShardId> duplicateShards, Set<ExecutableTestIdentity> duplicateTests)
{
	public static ExecutableCompleteness from(ExecutableCollectionExpectation expectation,
			ExecutableCollectionSummary collection)
	{
		Set<ExecutableTestIdentity> accounted = new TreeSet<>(collection.reportedTests());
		accounted.addAll(expectation.intentionallyNonExecutableTests());
		Set<ShardId> completed = new TreeSet<>(collection.completedShardReports());
		return new ExecutableCompleteness(expectation.testInventory().expectedTests(), accounted,
				expectation.expectedShards(), completed,
				difference(expectation.testInventory().expectedTests(), accounted),
				difference(accounted, expectation.testInventory().expectedTests()),
				difference(expectation.expectedShards(), completed), duplicates(collection.completedShardReports()),
				collection.duplicateTests());
	}

	public ExecutableCompleteness
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
	private static <T extends Comparable<? super T>> Set<T> immutable(Set<T> values)
	{
		return Set.copyOf(new TreeSet<>(values));
	}
	private static <T extends Comparable<? super T>> Set<T> duplicates(Iterable<T> values)
	{
		Set<T> seen = new HashSet<>(); TreeSet<T> result = new TreeSet<>();
		for (T value : values) if (!seen.add(value)) result.add(value);
		return result;
	}
}
