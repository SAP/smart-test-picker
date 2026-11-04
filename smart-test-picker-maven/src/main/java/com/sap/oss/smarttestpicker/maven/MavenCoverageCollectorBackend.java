// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import com.sap.oss.smarttestpicker.CollectorCapabilities;
import com.sap.oss.smarttestpicker.CoverageCollectorBackend;
import com.sap.oss.smarttestpicker.CoverageCollectorType;
import com.sap.oss.smarttestpicker.StpCollectorSelection;

/** Describes Maven mapping; lifecycle preparation and finalization remain in their mojos. */
enum MavenCoverageCollectorBackend implements CoverageCollectorBackend {
	// The prepared Maven ASM path accepts schema 3 only, despite the agent supporting schema 2.
	ASM(CoverageCollectorType.ASM, new CollectorCapabilities(true, true, true, true, false)),
	JACOCO(CoverageCollectorType.JACOCO, new CollectorCapabilities(true, false, true, true, false));

	private final CoverageCollectorType type;
	private final CollectorCapabilities capabilities;

	MavenCoverageCollectorBackend(CoverageCollectorType type, CollectorCapabilities capabilities) {
		this.type = type;
		this.capabilities = capabilities;
	}

	@Override public CoverageCollectorType type() { return type; }
	@Override public CollectorCapabilities capabilities() { return capabilities; }

	static MavenCoverageCollectorBackend selected(StpCollectorSelection selection) {
		return switch (selection) {
			case ASM -> ASM;
			case JACOCO -> JACOCO;
		};
	}
}
