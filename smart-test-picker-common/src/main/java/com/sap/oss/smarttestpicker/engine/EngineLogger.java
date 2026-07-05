// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.engine;


/**
 * Build-tool-agnostic logging interface for engine classes.
 *
 * <p>Both Gradle ({@code getLogger()}) and Maven ({@code getLog()}) provide their own
 * logging APIs. This interface allows engine classes to log without depending on either.</p>
 */
public interface EngineLogger
{

	/**
	 * Logs a debug-level message. Used for detailed decision tracing that helps
	 * understand why the tool made a specific choice.
	 *
	 * @param msg  message with SLF4J-style {@code {}} placeholders
	 * @param args values to substitute for each {@code {}} in order
	 */
	default void debug(String msg, Object... args)
	{
		// Default no-op — consumers opt in by overriding
	}

	/**
	 * @param msg  message with SLF4J-style {@code {}} placeholders
	 * @param args values to substitute for each {@code {}} in order
	 */
	void info(String msg, Object... args);

	/**
	 * @param msg  message with SLF4J-style {@code {}} placeholders
	 * @param args values to substitute for each {@code {}} in order
	 */
	void warn(String msg, Object... args);
}
