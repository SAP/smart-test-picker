// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Exact method includes for the shared Surefire/Failsafe pattern resolver. */
final class MavenMethodIncludes {
	private MavenMethodIncludes() { }

	static String include(String className, String methodName, boolean sourcePath) {
		if (methodName.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*")) {
			return (sourcePath ? className.replace('.', '/') + ".java" : className) + "#" + methodName;
		}
		// Hex escapes keep commas, '#' and regex markers out of the provider's
		// outer pattern grammar without interpreting any part of the feature name.
		String method = methodName.codePoints().mapToObj(value -> "\\x{" + Integer.toHexString(value) + "}")
				.collect(Collectors.joining());
		return "%regex[" + Pattern.quote(className.replace('.', '/') + ".class") + "#" + method + "]";
	}
}
