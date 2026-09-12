// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;

/** Central trusted boundary from logical runtime facts to schema-v3 executable facts. */
public final class ExecutableCoverageFragmentQualifier {
	public ExecutableCoverageFragment qualify(CoverageFragment logical, ExecutionTarget target) {
		if (logical == null) throw new IllegalArgumentException("Logical coverage fragment is required");
		if (target == null) throw new IllegalArgumentException("Schema-v3 mapping requires an execution target");
		Map<ExecutableTestIdentity, TestCoverage> mapped = new TreeMap<>();
		logical.tests().forEach((test, coverage) -> mapped.put(new ExecutableTestIdentity(target, test), coverage));
		List<ExecutableUnmappedTest> unmapped = logical.unmapped().stream()
				.map(value -> new ExecutableUnmappedTest(new ExecutableTestIdentity(target, value.test()), value.reason()))
				.toList();
		return new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3, logical.revision(), logical.shardId(),
				mapped, unmapped, logical.setupScopes(), logical.collectionCompleted());
	}

	public void requireTarget(ExecutableCoverageFragment fragment, ExecutionTarget expected) {
		if (fragment == null || expected == null) throw new IllegalArgumentException("Fragment and execution target are required");
		fragment.tests().keySet().forEach(identity -> require(identity, expected));
		fragment.unmapped().forEach(value -> require(value.test(), expected));
	}

	private static void require(ExecutableTestIdentity identity, ExecutionTarget expected) {
		if (!expected.equals(identity.target()))
			throw new IllegalArgumentException("Execution target mismatch: configured " + expected
					+ " but identity belongs to " + identity.target());
	}
}
