// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

import java.util.Objects;

public record TestIdentity(String platformUniqueId, String displayName, String testClass, String testMethod,
		String engineId, String runId, String jvmId) implements Comparable<TestIdentity> {
	public TestIdentity {
		requireText(platformUniqueId, "platformUniqueId");
		requireText(runId, "runId");
		requireText(jvmId, "jvmId");
	}

	public String canonicalKey() {
		return runId + "\u0000" + jvmId + "\u0000" + platformUniqueId;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof TestIdentity that && platformUniqueId.equals(that.platformUniqueId)
				&& runId.equals(that.runId) && jvmId.equals(that.jvmId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(platformUniqueId, runId, jvmId);
	}

	@Override
	public int compareTo(TestIdentity other) {
		return canonicalKey().compareTo(other.canonicalKey());
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}

