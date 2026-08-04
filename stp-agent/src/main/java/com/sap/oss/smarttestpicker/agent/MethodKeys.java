// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

final class MethodKeys {
	private MethodKeys() {
	}

	public static String canonical(String binaryClassName, String methodName, String jvmDescriptor) {
		if (binaryClassName == null || binaryClassName.isBlank()) throw new IllegalArgumentException("binaryClassName");
		if (methodName == null || methodName.isBlank()) throw new IllegalArgumentException("methodName");
		if (jvmDescriptor == null || !jvmDescriptor.startsWith("(")) throw new IllegalArgumentException("jvmDescriptor");
		return binaryClassName + "#" + methodName + jvmDescriptor;
	}
}
