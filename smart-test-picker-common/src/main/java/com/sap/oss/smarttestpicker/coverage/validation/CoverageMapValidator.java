// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

public final class CoverageMapValidator
{
	private CoverageMapValidator() {}

	public static ValidationResult validate(CoverageMap map)
	{
		ArrayList<ValidationError> errors = new ArrayList<>();
		if (map.schemaVersion() != CoverageMapContract.SCHEMA_VERSION)
			errors.add(error(ValidationCategory.INCOMPATIBLE_SCHEMA,
				map.schemaVersion() > CoverageMapContract.SCHEMA_VERSION ? ValidationCode.HIGHER_SCHEMA_VERSION : ValidationCode.SCHEMA_VERSION_MISMATCH,
				"Unsupported coverage-map schema version: " + map.schemaVersion()));
		if (map.revision() == null)
			errors.add(error(ValidationCategory.INVALID_STRUCTURE, ValidationCode.MISSING_REVISION, "revision is required"));

		Set<TestIdentity> unmapped = new HashSet<>();
		map.unmapped().forEach(entry -> unmapped.add(entry.test()));
		Set<TestIdentity> overlap = new HashSet<>(map.tests().keySet()); overlap.retainAll(unmapped);
		if (!overlap.isEmpty())
			errors.add(error(ValidationCategory.INVALID_STRUCTURE, ValidationCode.TEST_MAPPED_AND_UNMAPPED,
				"Tests cannot be both mapped and unmapped: " + overlap));

		Set<String> scopeIds = new HashSet<>();
		map.setupScopes().forEach(scope -> {
			if (!scopeIds.add(scope.id())) errors.add(error(ValidationCategory.INVALID_STRUCTURE,
					ValidationCode.DUPLICATE_SETUP_SCOPE_ID, "Duplicate setup scope: " + scope.id()));
		});

		if (map.completeness() != null)
		{
			if (!map.completeness().missingTests().isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP,
					ValidationCode.MISSING_TEST, "Missing tests: " + map.completeness().missingTests()));
			if (!map.completeness().unexpectedTests().isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP,
					ValidationCode.UNEXPECTED_TEST, "Unexpected tests: " + map.completeness().unexpectedTests()));
			if (!map.completeness().missingShards().isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP,
					ValidationCode.MISSING_SHARD, "Missing shards: " + map.completeness().missingShards()));
			if (!map.completeness().duplicateShards().isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP,
					ValidationCode.DUPLICATE_SHARD, "Duplicate shards: " + map.completeness().duplicateShards()));
			if (!map.completeness().duplicateTests().isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP,
					ValidationCode.DUPLICATE_TEST_IDENTITY, "Duplicate tests: " + map.completeness().duplicateTests()));
			Set<ShardId> unexpectedCompleted = new HashSet<>(map.completeness().completedShards());
			unexpectedCompleted.removeAll(map.completeness().expectedShards());
			if (!unexpectedCompleted.isEmpty()) errors.add(error(ValidationCategory.INCOMPLETE_MAP,
					ValidationCode.UNEXPECTED_COMPLETED_SHARD, "Unexpected completed shards: " + unexpectedCompleted));
		}
		if (map.lifecycleState() != CoverageMapLifecycleState.PUBLISHED)
			errors.add(error(ValidationCategory.INVALID_STRUCTURE, ValidationCode.LIFECYCLE_NOT_PUBLISHED,
				"Only PUBLISHED maps may be selector input"));
		return new ValidationResult(errors);
	}

	public static ValidationError error(ValidationCategory category, ValidationCode code, String message)
	{
		return new ValidationError(category, code, message);
	}
}
