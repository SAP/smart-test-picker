// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Logical test identity: fully-qualified JVM binary class name plus declared method name. */
public record TestIdentity(String className, String methodName) implements Comparable<TestIdentity>
{
	private static final Pattern CLASS_NAME = Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(?:[.$][\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)+");

	public TestIdentity
	{
		if (className == null || !CLASS_NAME.matcher(className).matches())
			throw new IllegalArgumentException("Malformed test class name: " + className);
		if (methodName == null || methodName.isEmpty() || methodName.codePoints().anyMatch(TestIdentity::invalidMethodCharacter))
			throw new IllegalArgumentException("Malformed test method name: " + methodName);
	}

	public static TestIdentity parse(String value)
	{
		Objects.requireNonNull(value, "value");
		int separator = value.indexOf('#');
		if (separator <= 0 || separator == value.length() - 1)
			throw new IllegalArgumentException("Malformed test identity: " + value);
		return new TestIdentity(value.substring(0, separator), value.substring(separator + 1));
	}

	private static boolean invalidMethodCharacter(int character)
	{
		return Character.isISOControl(character) || character == '.' || character == ';' || character == '[' || character == '/';
	}

	@Override public String toString() { return className + "#" + methodName; }
	@Override public int compareTo(TestIdentity other)
	{
		int byClass = className.compareTo(other.className);
		return byClass != 0 ? byClass : methodName.compareTo(other.methodName);
	}
}
