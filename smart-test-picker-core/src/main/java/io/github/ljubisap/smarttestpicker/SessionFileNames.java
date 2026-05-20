// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker;

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
	 */
	public static String sanitize(String sessionId)
	{
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
	 * Reverses {@link #sanitize(String)}: {@code ~X} becomes uppercase {@code X}.
	 */
	public static String unsanitize(String name)
	{
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
}
