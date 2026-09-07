// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import org.junit.platform.engine.support.descriptor.MethodSource;

/** The single authoritative JUnit MethodSource to schema-v2 identity conversion. */
public final class JUnitMethodSourceIdentity {
	private JUnitMethodSourceIdentity() {}

	public static TestIdentity from(MethodSource source) {
		if (source == null) throw new IllegalArgumentException("JUnit MethodSource is null");
		return fromParts(source.getClassName(), source.getMethodName(), source.getMethodParameterTypes());
	}

	public static TestIdentity fromParts(String className, String methodName, String parameterTypes) {
		if (blank(className) || blank(methodName))
			throw new IllegalArgumentException("JUnit MethodSource has no usable declared test identity");
		return new TestIdentity(className, methodName, parameterTypes);
	}

	private static boolean blank(String value) { return value == null || value.isBlank(); }
}
