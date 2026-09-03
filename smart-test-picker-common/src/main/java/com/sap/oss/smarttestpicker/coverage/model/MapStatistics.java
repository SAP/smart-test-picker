// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

public record MapStatistics(int expectedTests, int mappedTests, int unmappedTests, int setupScopes,
		long classEdges, long methodEdges) {}
