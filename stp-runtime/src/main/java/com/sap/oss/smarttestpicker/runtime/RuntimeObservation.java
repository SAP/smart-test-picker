// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedEvent;

import java.util.List;
import java.util.Set;

/** Immutable, framework-neutral input to schema-v2 projection. */
public record RuntimeObservation(String runId, String jvmId, List<PhysicalTest> tests,
		List<SetupObservation> setup, Set<UnattributedEvent> unattributedEvents, Set<SetupDiagnostic> setupDiagnostics) {
	public RuntimeObservation {
		tests = List.copyOf(tests);
		setup = List.copyOf(setup);
		unattributedEvents = Set.copyOf(unattributedEvents);
		setupDiagnostics = Set.copyOf(setupDiagnostics);
	}

	public record PhysicalTest(TestIdentity identity, TestResult result, Set<MethodIdentity> methods,
			boolean finished, Set<UnattributedEvent> unattributedEvents) {
		public PhysicalTest {
			methods = Set.copyOf(methods);
			unattributedEvents = Set.copyOf(unattributedEvents);
		}
	}

	public record SetupObservation(String binaryContainerName, boolean nested, Set<MethodIdentity> methods) {
		public SetupObservation {
			methods = Set.copyOf(methods);
		}
	}
}
