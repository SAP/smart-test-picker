// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import java.lang.reflect.Method;

final class JvmMethodDescriptors {
	private JvmMethodDescriptors() {
	}

	static String descriptor(Method method) {
		StringBuilder value = new StringBuilder("(");
		for (Class<?> parameter : method.getParameterTypes()) appendType(value, parameter);
		value.append(')');
		appendType(value, method.getReturnType());
		return value.toString();
	}

	private static void appendType(StringBuilder value, Class<?> type) {
		if (type.isArray()) {
			value.append(type.getName().replace('.', '/'));
			return;
		}
		if (!type.isPrimitive()) {
			value.append('L').append(type.getName().replace('.', '/')).append(';');
			return;
		}
		if (type == void.class) value.append('V');
		else if (type == boolean.class) value.append('Z');
		else if (type == byte.class) value.append('B');
		else if (type == char.class) value.append('C');
		else if (type == short.class) value.append('S');
		else if (type == int.class) value.append('I');
		else if (type == long.class) value.append('J');
		else if (type == float.class) value.append('F');
		else if (type == double.class) value.append('D');
	}
}
