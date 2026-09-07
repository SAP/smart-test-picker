// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.SetupScopeType;
import com.sap.oss.smarttestpicker.coverage.model.TestContainer;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic projection of descriptor-preserving runtime facts into schema v2. */
public final class AsmCoverageFragmentProjector {
	public FragmentProjectionResult project(RuntimeObservation observation, FragmentProjectionConfig config,
			CollectorIntegrity integrity) {
		if (observation == null || config == null || integrity == null) throw new IllegalArgumentException("projection inputs are required");
		List<String> diagnostics = new ArrayList<>();
		Map<com.sap.oss.smarttestpicker.coverage.model.TestIdentity, Accumulator> groups = new TreeMap<>();
		List<UnmappedTest> unmapped = new ArrayList<>();
		boolean complete = !integrity.criticalFailure();
		for (SetupDiagnostic diagnostic : observation.setupDiagnostics()) {
			diagnostics.add("setup:" + diagnostic.canonicalText());
			if (diagnostic.severity() == SetupDiagnostic.Severity.ERROR) complete = false;
		}

		for (RuntimeObservation.PhysicalTest physical : observation.tests()) {
			var logical = logicalIdentity(physical.identity());
			if (logical == null) {
				diagnostics.add("unsupported-identity:" + physical.identity().platformUniqueId());
				complete = false;
				continue; // the schema cannot encode an unmapped entry without a logical identity
			}
			groups.computeIfAbsent(logical, ignored -> new Accumulator()).add(physical);
		}

		Map<com.sap.oss.smarttestpicker.coverage.model.TestIdentity, TestCoverage> tests = new TreeMap<>();
		for (Map.Entry<com.sap.oss.smarttestpicker.coverage.model.TestIdentity, Accumulator> entry : groups.entrySet()) {
			Accumulator group = entry.getValue();
			if (integrity.criticalFailure() || !group.finished || group.resultMissing
					) {
				unmapped.add(new UnmappedTest(entry.getKey(), UnmappedReason.COLLECTION_FAILED));
				complete = false;
				continue;
			}
			if (group.allAborted) {
				unmapped.add(new UnmappedTest(entry.getKey(), UnmappedReason.SKIPPED));
				continue;
			}
			Set<String> classes = new TreeSet<>();
			Set<com.sap.oss.smarttestpicker.coverage.model.MethodIdentity> methods = new TreeSet<>();
			for (MethodIdentity method : group.methods) {
				classes.add(method.binaryClassName());
				methods.add(projectedMethod(method));
			}
			tests.put(entry.getKey(), new TestCoverage(classes, methods,
					group.failed ? TestOutcome.FAIL : TestOutcome.PASS,
					classes.isEmpty() ? CollectionStatus.COLLECTED_EMPTY : CollectionStatus.COLLECTED_WITH_COVERAGE));
		}

		List<SetupScope> scopes = observation.setup().stream().filter(value -> !value.methods().isEmpty())
				.map(value -> new SetupScope("junit-container:" + value.binaryContainerName(),
						value.nested() ? SetupScopeType.NESTED_CONTAINER : SetupScopeType.CONTAINER,
						value.methods().stream().map(MethodIdentity::binaryClassName).collect(java.util.stream.Collectors.toSet()),
						Set.of(new TestContainer(value.binaryContainerName())))).toList();
		if (integrity.criticalFailure()) diagnostics.addAll(integrity.agentErrors());
		CoverageFragment fragment = new CoverageFragment(CoverageMapContract.SCHEMA_VERSION, config.revision(),
				config.shardId(), tests, unmapped, scopes, complete);
		return new FragmentProjectionResult(fragment, diagnostics.stream().sorted().toList());
	}

	private static com.sap.oss.smarttestpicker.coverage.model.TestIdentity logicalIdentity(
			com.sap.oss.smarttestpicker.runtime.model.TestIdentity runtime) {
		if (runtime.testClass() == null || runtime.testMethod() == null) return null;
		try {
			return new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(runtime.testClass(), runtime.testMethod(),
					runtime.testMethodParameterTypes());
		} catch (IllegalArgumentException invalid) {
			return null;
		}
	}

	private static com.sap.oss.smarttestpicker.coverage.model.MethodIdentity projectedMethod(MethodIdentity method) {
		return new com.sap.oss.smarttestpicker.coverage.model.MethodIdentity(method.binaryClassName(), method.methodName(),
				method.jvmDescriptor());
	}

	private static final class Accumulator {
		private final Set<MethodIdentity> methods = new TreeSet<>();
		private boolean failed;
		private boolean allAborted = true;
		private boolean finished = true;
		private boolean resultMissing;

		private void add(RuntimeObservation.PhysicalTest physical) {
			methods.addAll(physical.methods());
			finished &= physical.finished();
			resultMissing |= physical.result() == null;
			if (physical.result() != null) {
				failed |= physical.result().status() == TestExecutionStatus.FAILED;
				allAborted &= physical.result().status() == TestExecutionStatus.ABORTED;
			}
		}
	}
}
