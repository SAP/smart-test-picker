// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

/**
 * Utilities for sanitizing test session IDs into filesystem-safe filenames.
 * Handles case-insensitive filesystems (macOS HFS+/APFS) where filenames
 * differing only in case collide.
 */
public final class SessionFileNames
{

	private SessionFileNames()
	{
	}

	/**
	 * Escapes uppercase letters in the method portion of a session ID so that
	 * names differing only in case produce distinct filenames on case-insensitive filesystems.
	 * Each uppercase letter {@code X} in the method name becomes {@code ~X}.
	 *
	 * <p>Example: {@code "MyTest#testBoolean"} becomes {@code "MyTest#test~Boolean"}</p>
	 * <p>Sessions containing path separators, reserved filename characters or controls use
	 * the {@code ~stp1~} namespace and four-digit UTF-16 escapes. Legacy session IDs
	 * start with a class name or generated identifier, never this namespace. Safe
	 * legacy names keep their spelling; new-format literal tildes are escaped too.</p>
	 */
	public static String sanitize(String sessionId)
	{
		if (sessionId.chars().anyMatch(value -> unsafe((char) value)))
		{
			// A versioned namespace keeps new escapes distinct from legacy literal tildes.
			StringBuilder encoded = new StringBuilder("~stp1~");
			for (char value : sessionId.toCharArray())
			{
				if (unsafe(value) || value == '~' || Character.isUpperCase(value))
				{
					encoded.append('~').append(String.format(java.util.Locale.ROOT, "%04x", (int) value));
				}
				else
				{
					encoded.append(value);
				}
			}
			return encoded.toString();
		}
		int hashIdx = sessionId.indexOf('#');
		if (hashIdx < 0)
		{
			return sessionId;
		}
		String className = sessionId.substring(0, hashIdx);
		String methodName = sessionId.substring(hashIdx + 1);

		StringBuilder sb = new StringBuilder(methodName.length() + 16);
		for (int i = 0; i < methodName.length(); i++)
		{
			char c = methodName.charAt(i);
			if (Character.isUpperCase(c))
			{
				sb.append('~').append(c);
			}
			else
			{
				sb.append(c);
			}
		}
		return className + "#" + sb.toString();
	}

	/**
	 * Reverses versioned path-safe encoding or legacy uppercase escaping ({@code ~X}).
	 * @throws IllegalArgumentException if a versioned escape is truncated or not four ASCII hex digits
	 */
	public static String unsanitize(String name)
	{
		if (name.startsWith("~stp1~"))
		{
			StringBuilder decoded = new StringBuilder();
			for (int i = "~stp1~".length(); i < name.length(); i++)
			{
				if (name.charAt(i) == '~')
				{
					decoded.append(decodeEscape(name, i));
					i += 4;
				}
				else
				{
					decoded.append(name.charAt(i));
				}
			}
			return decoded.toString();
		}
		int hashIdx = name.indexOf('#');
		if (hashIdx < 0)
		{
			return name;
		}
		String className = name.substring(0, hashIdx);
		String methodPart = name.substring(hashIdx + 1);

		StringBuilder sb = new StringBuilder(methodPart.length());
		for (int i = 0; i < methodPart.length(); i++)
		{
			char c = methodPart.charAt(i);
			if (c == '~' && i + 1 < methodPart.length())
			{
				sb.append(methodPart.charAt(i + 1));
				i++;
			}
			else
			{
				sb.append(c);
			}
		}
		return className + "#" + sb.toString();
	}

	private static char decodeEscape(String name, int offset)
	{
		if (name.length() - offset < 5)
		{
			throw new IllegalArgumentException("Malformed encoded session filename: " + name
					+ "; truncated escape at offset " + offset);
		}
		int value = 0;
		for (int index = offset + 1; index < offset + 5; index++)
		{
			char character = name.charAt(index);
			int digit = Character.digit(character, 16);
			if (character > 127 || digit < 0)
			{
				throw new IllegalArgumentException("Malformed encoded session filename: " + name
						+ "; expected four ASCII hex digits at offset " + offset);
			}
			value = (value << 4) | digit;
		}
		return (char) value;
	}

	private static boolean unsafe(char value)
	{
		return Character.isISOControl(value) || "/\\:<>\"|?*".indexOf(value) >= 0;
	}
}
