// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.util.Locale;

/** Collector selection transported into a test JVM; legacy core consumers default to JaCoCo. */
public enum StpCollectorSelection
{
	ASM, JACOCO;

	public static final String PROPERTY = "smartTestPicker.coverageCollector";

	public static StpCollectorSelection current()
	{
		return parse(System.getProperty(PROPERTY));
	}

	public static StpCollectorSelection parse(String value)
	{
		if (value == null || value.isBlank()) return JACOCO;
		try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException failure)
		{
			throw new IllegalArgumentException("Unsupported " + PROPERTY + "=" + value
					+ "; expected ASM or JACOCO", failure);
		}
	}
}
