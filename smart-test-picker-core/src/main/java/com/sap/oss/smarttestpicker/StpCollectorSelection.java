// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.util.Locale;

/** Collector choice for explicitly activated test-JVM collection; selection defaults to JaCoCo. */
public enum StpCollectorSelection
{
	ASM, JACOCO;

	public static final String PROPERTY = "smartTestPicker.coverageCollector";
	public static final String ACTIVATION_PROPERTY = "smartTestPicker.collectionEnabled";

	/** Inactive consumers do not parse selection, even when it contains an invalid value. */
	public static boolean jacocoEnabled()
	{
		String activation = System.getProperty(ACTIVATION_PROPERTY);
		if (activation == null || activation.isBlank() || "false".equalsIgnoreCase(activation.trim())) return false;
		if (!"true".equalsIgnoreCase(activation.trim()))
			throw new IllegalArgumentException("Unsupported " + ACTIVATION_PROPERTY + "=" + activation
					+ "; expected true or false");
		return current() == JACOCO;
	}

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
