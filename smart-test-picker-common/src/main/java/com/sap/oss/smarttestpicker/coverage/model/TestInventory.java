// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Set;
import java.util.TreeSet;

public record TestInventory(CoverageMapRevision revision, Set<TestIdentity> expectedTests)
{
	public TestInventory { expectedTests = Set.copyOf(new TreeSet<>(expectedTests)); }
}
