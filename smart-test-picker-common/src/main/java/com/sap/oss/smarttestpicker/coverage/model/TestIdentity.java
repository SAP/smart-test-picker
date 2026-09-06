// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Logical test identity: fully-qualified JVM binary class name plus declared method name. */
public record TestIdentity(String className, String methodName, String methodParameterTypes) implements Comparable<TestIdentity>
{
	private static final Pattern CLASS_NAME = Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(?:[.$][\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)+");

	public TestIdentity
	{
		if (className == null || !CLASS_NAME.matcher(className).matches())
			throw new IllegalArgumentException("Malformed test class name: " + className);
		if (methodName == null || methodName.isEmpty() || methodName.codePoints().anyMatch(TestIdentity::invalidMethodCharacter))
			throw new IllegalArgumentException("Malformed test method name: " + methodName);
		methodParameterTypes = methodParameterTypes == null ? "" : methodParameterTypes;
		if (methodParameterTypes.codePoints().anyMatch(character -> Character.isISOControl(character) || character == '#'))
			throw new IllegalArgumentException("Malformed test method parameter types: " + methodParameterTypes);
	}

	public TestIdentity(String className, String methodName) { this(className, methodName, ""); }

	public static TestIdentity parse(String value)
	{
		Objects.requireNonNull(value, "value");
		int separator = value.indexOf('#');
		if (separator <= 0 || separator == value.length() - 1)
			throw new IllegalArgumentException("Malformed test identity: " + value);
		String suffix = value.substring(separator + 1);
		int parameters = suffix.lastIndexOf('(');
		if (parameters > 0 && suffix.endsWith(")"))
			return new TestIdentity(value.substring(0, separator), suffix.substring(0, parameters),
					suffix.substring(parameters + 1, suffix.length() - 1));
		return new TestIdentity(value.substring(0, separator), suffix, "");
	}

	private static boolean invalidMethodCharacter(int character)
	{
		return Character.isISOControl(character) || character == '.' || character == ';' || character == '[' || character == '/';
	}

	@Override public String toString() { return className + "#" + methodName + (methodParameterTypes.isEmpty() ? "" : "(" + methodParameterTypes + ")"); }
	@Override public int compareTo(TestIdentity other)
	{
		int byClass = className.compareTo(other.className);
		if (byClass != 0) return byClass;
		int byMethod = methodName.compareTo(other.methodName);
		return byMethod != 0 ? byMethod : methodParameterTypes.compareTo(other.methodParameterTypes);
	}
}
