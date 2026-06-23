// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import org.apache.maven.plugin.logging.Log;

import com.sap.oss.smarttestpicker.engine.EngineLogger;


/**
 * Adapts Maven's {@link Log} to the build-tool-agnostic {@link EngineLogger} interface.
 */
class MavenEngineLogger implements EngineLogger
{

	private final Log log;

	MavenEngineLogger(Log log)
	{
		this.log = log;
	}

	@Override
	public void info(String msg, Object... args)
	{
		log.info(format(msg, args));
	}

	@Override
	public void warn(String msg, Object... args)
	{
		log.warn(format(msg, args));
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
			if (idx >= 0)
			{
				result = result.substring(0, idx) + arg + result.substring(idx + 2);
			}
			else
			{
				break;
			}
		}
		return result;
	}
}
