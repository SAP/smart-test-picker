// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

public record TestContainer(String binaryName) implements Comparable<TestContainer>
{
	public TestContainer
	{
		if (binaryName == null || binaryName.isBlank()) throw new IllegalArgumentException("container name must not be blank");
	}
	@Override public int compareTo(TestContainer other) { return binaryName.compareTo(other.binaryName); }
}
