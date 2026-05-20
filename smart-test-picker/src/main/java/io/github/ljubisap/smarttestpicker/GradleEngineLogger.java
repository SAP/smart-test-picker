// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker;

import org.gradle.api.logging.Logger;

import io.github.ljubisap.smarttestpicker.engine.EngineLogger;


/**
 * Adapts the Gradle {@link Logger} to the build-tool-agnostic {@link EngineLogger} interface.
 */
class GradleEngineLogger implements EngineLogger
{

	private final Logger logger;

	GradleEngineLogger(Logger logger)
	{
		this.logger = logger;
	}

	@Override
	public void info(String msg, Object... args)
	{
		logger.lifecycle(msg, args);
	}

	@Override
	public void warn(String msg, Object... args)
	{
		logger.warn(msg, args);
	}
}
