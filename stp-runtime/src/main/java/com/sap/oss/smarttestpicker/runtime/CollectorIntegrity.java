// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import java.util.List;

/** Known local collector health. Global test/shard completeness is deliberately absent. */
public record CollectorIntegrity(boolean runtimeInitialized, long transformationFailures,
		long methodIdCollisions, List<String> agentErrors) {
	public CollectorIntegrity {
		agentErrors = List.copyOf(agentErrors);
		if (transformationFailures < 0 || methodIdCollisions < 0) throw new IllegalArgumentException("counts must be non-negative");
	}

	public static CollectorIntegrity healthy() {
		return new CollectorIntegrity(true, 0, 0, List.of());
	}

	public boolean criticalFailure() {
		return !runtimeInitialized || transformationFailures > 0 || methodIdCollisions > 0 || !agentErrors.isEmpty();
	}
}
