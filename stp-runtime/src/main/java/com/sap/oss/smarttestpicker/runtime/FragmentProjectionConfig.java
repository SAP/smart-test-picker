// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;

/** Externally supplied fragment binding; the collector never discovers either value. */
public record FragmentProjectionConfig(int schemaVersion, CoverageMapRevision revision, ShardId shardId,
		ExecutionTarget executionTarget) {
	public FragmentProjectionConfig {
		if (schemaVersion != CoverageMapContract.SCHEMA_VERSION && schemaVersion != CoverageMapContract.SCHEMA_V3)
			throw new IllegalArgumentException("Unsupported runtime schema version: " + schemaVersion);
		if (revision == null) throw new IllegalArgumentException("revision is required");
		if (shardId == null) throw new IllegalArgumentException("shardId is required");
		if (schemaVersion == CoverageMapContract.SCHEMA_V3 && executionTarget == null)
			throw new IllegalArgumentException("Schema-v3 mapping requires an execution target");
		if (schemaVersion == CoverageMapContract.SCHEMA_VERSION && executionTarget != null)
			throw new IllegalArgumentException("Schema-v2 mapping does not accept an execution target");
	}

	public static FragmentProjectionConfig of(String revision, String shardId) {
		return v2(revision, shardId);
	}

	public static FragmentProjectionConfig v2(String revision, String shardId) {
		return new FragmentProjectionConfig(CoverageMapContract.SCHEMA_VERSION,
				new CoverageMapRevision(revision), new ShardId(shardId), null);
	}

	public static FragmentProjectionConfig v3(String revision, String shardId, String executionTarget) {
		if (executionTarget == null || executionTarget.isBlank())
			throw new IllegalArgumentException("Schema-v3 mapping requires an execution target");
		try {
			return new FragmentProjectionConfig(CoverageMapContract.SCHEMA_V3,
					new CoverageMapRevision(revision), new ShardId(shardId), ExecutionTarget.parse(executionTarget));
		} catch (IllegalArgumentException failure) {
			throw new IllegalArgumentException("Malformed execution target: " + executionTarget, failure);
		}
	}
}
