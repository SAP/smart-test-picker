// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCompleteness;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;

/** Validation boundary dedicated to schema-v3 executable ownership. */
public final class ExecutableCoverageMapValidator
{
	private ExecutableCoverageMapValidator() {}

	public static ValidationResult validate(ExecutableCoverageMap map)
	{
		ArrayList<ValidationError> errors = common(map.schemaVersion(), map.revision(), map.tests().keySet(),
				map.unmapped().stream().map(value -> value.test()).toList(), map.setupScopes());
		if (map.completeness() != null)
		{
			completeness(map.completeness(), errors);
			Set<ExecutableTestIdentity> observed = new TreeSet<>(map.tests().keySet());
			map.unmapped().forEach(value -> observed.add(value.test()));
			if (!map.completeness().reportedTests().containsAll(observed))
				errors.add(error(ValidationCategory.INCOMPLETE_MAP, ValidationCode.UNEXPECTED_TEST,
						"Mapped and unmapped executable occurrences must be reported by completeness"));
		}
		if (map.lifecycleState() != CoverageMapLifecycleState.PUBLISHED)
			errors.add(error(ValidationCategory.INVALID_STRUCTURE, ValidationCode.LIFECYCLE_NOT_PUBLISHED,
					"Only PUBLISHED executable maps may be selector input"));
		return new ValidationResult(errors);
	}

	public static ValidationResult validate(ExecutableCoverageFragment fragment)
	{
		ArrayList<ValidationError> errors = common(fragment.schemaVersion(), fragment.revision(), fragment.tests().keySet(),
				fragment.unmapped().stream().map(value -> value.test()).toList(), fragment.setupScopes());
		if (fragment.shardId() == null) errors.add(error(ValidationCategory.INVALID_STRUCTURE,
				ValidationCode.MISSING_SHARD_ID, "shardId is required"));
		return new ValidationResult(errors);
	}

	private static ArrayList<ValidationError> common(int version, Object revision,
			Set<ExecutableTestIdentity> mapped, Iterable<ExecutableTestIdentity> unmapped,
			Iterable<com.sap.oss.smarttestpicker.coverage.model.SetupScope> scopes)
	{
		ArrayList<ValidationError> errors = new ArrayList<>();
		if (version != CoverageMapContract.SCHEMA_V3) errors.add(error(ValidationCategory.INCOMPATIBLE_SCHEMA,
				version > CoverageMapContract.SCHEMA_V3 ? ValidationCode.HIGHER_SCHEMA_VERSION : ValidationCode.SCHEMA_VERSION_MISMATCH,
				"Unsupported executable artifact schema version: " + version));
		if (revision == null) errors.add(error(ValidationCategory.INVALID_STRUCTURE, ValidationCode.MISSING_REVISION, "revision is required"));
		if (mapped.stream().anyMatch(java.util.Objects::isNull)) errors.add(error(ValidationCategory.INVALID_STRUCTURE,
				ValidationCode.MALFORMED_TEST_IDENTITY, "Executable keys must not be null"));
		Set<ExecutableTestIdentity> unmappedSet = new HashSet<>();
		for (ExecutableTestIdentity identity : unmapped)
			if (!unmappedSet.add(identity)) errors.add(error(ValidationCategory.INVALID_STRUCTURE,
					ValidationCode.DUPLICATE_TEST_IDENTITY, "Duplicate unmapped executable test: " + identity));
		Set<ExecutableTestIdentity> overlap = new HashSet<>(mapped); overlap.retainAll(unmappedSet);
		if (!overlap.isEmpty()) errors.add(error(ValidationCategory.INVALID_STRUCTURE,
				ValidationCode.TEST_MAPPED_AND_UNMAPPED, "Executable tests cannot be mapped and unmapped: " + overlap));
		Set<String> scopeIds = new HashSet<>();
		for (var scope : scopes) {
			if (scope.owner() == null) errors.add(error(ValidationCategory.INVALID_STRUCTURE,
					ValidationCode.MISSING_SETUP_SCOPE_OWNER, "Executable setup scope is missing owner: " + scope.id()));
			else if (!scopeIds.add(scope.owner() + "::" + scope.id())) errors.add(error(ValidationCategory.INVALID_STRUCTURE,
					ValidationCode.DUPLICATE_SETUP_SCOPE_ID, "Duplicate setup scope: " + scope.owner() + "::" + scope.id()));
		}
		return errors;
	}

	private static void completeness(ExecutableCompleteness value, ArrayList<ValidationError> errors)
	{
		checkExact(value.missingTests(), difference(value.expectedTests(), value.reportedTests()),
				ValidationCode.MISSING_TEST, "missing executable tests", errors);
		checkExact(value.unexpectedTests(), difference(value.reportedTests(), value.expectedTests()),
				ValidationCode.UNEXPECTED_TEST, "unexpected executable tests", errors);
		checkExact(value.missingShards(), difference(value.expectedShards(), value.completedShards()),
				ValidationCode.MISSING_SHARD, "missing shards", errors);
		Set<ShardId> unexpectedCompleted = difference(value.completedShards(), value.expectedShards());
		if (!unexpectedCompleted.isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP,
				ValidationCode.UNEXPECTED_COMPLETED_SHARD, "Unexpected completed shards: " + unexpectedCompleted));
		if (!value.duplicateShards().isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP,
				ValidationCode.DUPLICATE_SHARD, "Duplicate shards: " + value.duplicateShards()));
		if (!value.duplicateTests().isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP,
				ValidationCode.DUPLICATE_TEST_IDENTITY, "Duplicate executable tests: " + value.duplicateTests()));
	}

	private static <T extends Comparable<? super T>> void checkExact(Set<T> actual, Set<T> expected,
			ValidationCode code, String label, ArrayList<ValidationError> errors)
	{
		if (!actual.equals(expected)) errors.add(error(ValidationCategory.INCOMPLETE_MAP, code,
				"Completeness " + label + " are not exact: expected " + expected + " but got " + actual));
		else if (!actual.isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP, code, label + ": " + actual));
	}
	private static <T extends Comparable<? super T>> Set<T> difference(Set<T> left, Set<T> right)
	{
		TreeSet<T> result = new TreeSet<>(left); result.removeAll(right); return result;
	}
	public static ValidationError error(ValidationCategory category, ValidationCode code, String message)
	{
		return new ValidationError(category, code, message);
	}
}
