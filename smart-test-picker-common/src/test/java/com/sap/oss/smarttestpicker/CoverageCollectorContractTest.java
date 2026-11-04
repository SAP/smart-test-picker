// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CoverageCollectorContractTest {
	@Test void collectorParsingRetainsTheGradleContractWithoutBuildToolDependencies() {
		assertEquals(CoverageCollectorType.ASM, CoverageCollectorType.parse(" asm "));
		assertEquals(CoverageCollectorType.JACOCO, CoverageCollectorType.parse(CoverageCollectorType.JACOCO));
		assertThrows(IllegalArgumentException.class, () -> CoverageCollectorType.parse(null));
		assertThrows(IllegalArgumentException.class, () -> CoverageCollectorType.parse(""));
		assertThrows(IllegalArgumentException.class, () -> CoverageCollectorType.parse("unknown"));
	}
}
