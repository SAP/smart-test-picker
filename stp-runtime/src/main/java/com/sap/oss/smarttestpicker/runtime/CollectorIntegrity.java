// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.util.List;

/** Collector health with explicit shard-fatal errors and producer-proven affected identities.
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

	public boolean shardFatal() {
		return !runtimeInitialized || transformationFailures > 0 || methodIdCollisions > 0
				|| agentErrors.stream().anyMatch(CollectorError::shardFatal);
	}

	public java.util.Set<com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity> affectedIdentities() {
		return agentErrors.stream().filter(error -> !error.shardFatal())
				.flatMap(error -> error.affectedIdentities().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}
}
