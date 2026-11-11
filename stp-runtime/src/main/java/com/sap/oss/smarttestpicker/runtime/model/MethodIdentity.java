// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

public record MethodIdentity(String binaryClassName, String methodName, String jvmDescriptor)
		implements Comparable<MethodIdentity> {
	public MethodIdentity {
		requireText(binaryClassName, "binaryClassName");
		requireText(methodName, "methodName");
		requireText(jvmDescriptor, "jvmDescriptor");
		if (!jvmDescriptor.startsWith("(")) {
			throw new IllegalArgumentException("jvmDescriptor must be a JVM method descriptor");
		}
	}

	public String canonicalKey() {
		return binaryClassName + "#" + methodName + jvmDescriptor;
	}

	@Override
	public int compareTo(MethodIdentity other) {
		return canonicalKey().compareTo(other.canonicalKey());
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}

