// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import com.sap.oss.smarttestpicker.engine.EngineLogger;


/**
 * {@link EngineLogger} implementation for CLI output.
 *
 * <p>Writes info messages to stdout and warnings to stderr. Uses SLF4J-style
 * {@code {}} placeholders for argument substitution.</p>
 *
 * <p>Debug messages are printed to stdout when verbose mode is enabled.</p>
 */
public class ConsoleLogger implements EngineLogger
{

	private final boolean verbose;

	public ConsoleLogger()
	{
		this(false);
	}

	public ConsoleLogger(boolean verbose)
	{
		this.verbose = verbose;
	}

	@Override
	public void debug(String msg, Object... args)
	{
		if (verbose)
		{
			System.out.println(format(msg, args));
		}
	}

	@Override
	public void info(String msg, Object... args)
	{
		System.out.println(format(msg, args));
	}

	@Override
	public void warn(String msg, Object... args)
	{
		System.err.println("[WARN] " + format(msg, args));
	}

	private String format(String msg, Object... args)
	{
		if (args == null || args.length == 0)
		{
			return msg;
		}
		String result = msg;
		for (Object arg : args)
		{
			int idx = result.indexOf("{}");
			if (idx < 0)
			{
				break;
			}
			result = result.substring(0, idx) + arg + result.substring(idx + 2);
		}
		return result;
	}
}
