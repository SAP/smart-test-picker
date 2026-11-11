// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Exact JVM method identity used by schema v2. */
public record MethodIdentity(String binaryClassName, String methodName, String jvmDescriptor)
		implements Comparable<MethodIdentity>
{
	private static final Pattern CLASS_NAME = Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(?:[.$][\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)+");

	public MethodIdentity
	{
		if (binaryClassName == null || !CLASS_NAME.matcher(binaryClassName).matches())
			throw new IllegalArgumentException("Malformed binary class name: " + binaryClassName);
		if (methodName == null || methodName.isEmpty() || methodName.codePoints().anyMatch(MethodIdentity::invalidMethodCharacter))
			throw new IllegalArgumentException("Malformed JVM method name: " + methodName);
		validateMethodDescriptor(jvmDescriptor);
	}

	public static MethodIdentity parse(String value)
	{
		Objects.requireNonNull(value, "value");
		int separator = value.indexOf('#');
		int descriptor = value.indexOf('(', separator + 1);
		if (separator <= 0 || descriptor <= separator + 1)
			throw new IllegalArgumentException("Malformed method identity: " + value);
		return new MethodIdentity(value.substring(0, separator), value.substring(separator + 1, descriptor),
				value.substring(descriptor));
	}

	@Override public String toString() { return binaryClassName + "#" + methodName + jvmDescriptor; }
	@Override public int compareTo(MethodIdentity other) { return toString().compareTo(other.toString()); }

	private static boolean invalidMethodCharacter(int character)
	{
		return Character.isISOControl(character) || character == '.' || character == ';' || character == '['
				|| character == '/' || character == '(' || character == ')' || character == '#';
	}

	private static void validateMethodDescriptor(String descriptor)
	{
		if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(')
			throw new IllegalArgumentException("Malformed JVM method descriptor: " + descriptor);
		int index = 1;
		while (index < descriptor.length() && descriptor.charAt(index) != ')') index = parseType(descriptor, index, false);
		if (index >= descriptor.length() || descriptor.charAt(index) != ')')
			throw new IllegalArgumentException("Malformed JVM method descriptor: " + descriptor);
		index++;
		if (index < descriptor.length() && descriptor.charAt(index) == 'V') index++;
		else index = parseType(descriptor, index, false);
		if (index != descriptor.length()) throw new IllegalArgumentException("Malformed JVM method descriptor: " + descriptor);
	}

	private static int parseType(String descriptor, int index, boolean arrayComponent)
	{
		if (index >= descriptor.length()) throw new IllegalArgumentException("Malformed JVM method descriptor: " + descriptor);
		char value = descriptor.charAt(index);
		if ("BCDFIJSZ".indexOf(value) >= 0) return index + 1;
		if (value == '[') return parseType(descriptor, index + 1, true);
		if (value == 'L') {
			int end = descriptor.indexOf(';', index + 1);
			if (end <= index + 1 || descriptor.substring(index + 1, end).indexOf('.') >= 0
					|| descriptor.substring(index + 1, end).indexOf('[') >= 0)
				throw new IllegalArgumentException("Malformed JVM method descriptor: " + descriptor);
			return end + 1;
		}
		throw new IllegalArgumentException("Malformed JVM method descriptor: " + descriptor);
	}
}
