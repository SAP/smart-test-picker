// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

import java.lang.reflect.Method;

final class Descriptors {
	private Descriptors() {
	}

	static String of(Method method) {
		StringBuilder result = new StringBuilder("(");
		for (Class<?> parameter : method.getParameterTypes()) result.append(of(parameter));
		return result.append(')').append(of(method.getReturnType())).toString();
	}

	private static String of(Class<?> type) {
		if (type.isArray()) return type.getName().replace('.', '/');
		if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
		if (type == void.class) return "V";
		if (type == boolean.class) return "Z";
		if (type == byte.class) return "B";
		if (type == char.class) return "C";
		if (type == short.class) return "S";
		if (type == int.class) return "I";
		if (type == long.class) return "J";
		if (type == float.class) return "F";
		if (type == double.class) return "D";
		throw new IllegalArgumentException(type.getName());
	}
}
