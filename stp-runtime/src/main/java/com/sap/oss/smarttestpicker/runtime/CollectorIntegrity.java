// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.util.List;

/** Diagnostic collector health and producer-proven affected identities.
 * Runtime state, counters and diagnostic classifications never invalidate test observations.
 * Only explicitly owned attribution failures affect the corresponding identities.
 * Assignment completeness remains the orchestrator's responsibility. */
public record CollectorIntegrity(boolean runtimeInitialized, long transformationFailures,
		long methodIdCollisions, List<CollectorError> agentErrors) {
	public CollectorIntegrity {
		agentErrors = List.copyOf(agentErrors);
		if (transformationFailures < 0 || methodIdCollisions < 0) throw new IllegalArgumentException("counts must be non-negative");
	}

	public static CollectorIntegrity healthy() {
		return new CollectorIntegrity(true, 0, 0, List.of());
	}

	public java.util.Set<com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity> affectedIdentities() {
		return agentErrors.stream()
				.flatMap(error -> error.affectedIdentities().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}
}
