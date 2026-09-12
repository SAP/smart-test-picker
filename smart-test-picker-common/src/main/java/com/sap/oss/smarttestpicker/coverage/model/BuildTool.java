// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

/** Build tool that owns an execution target. */
public enum BuildTool
{
	MAVEN("maven"),
	GRADLE("gradle");

	private final String canonicalName;

	BuildTool(String canonicalName) { this.canonicalName = canonicalName; }

	public static BuildTool parse(String value)
	{
		for (BuildTool buildTool : values())
			if (buildTool.canonicalName.equals(value)) return buildTool;
		throw new IllegalArgumentException("Unsupported build tool: " + value);
	}

	@Override public String toString() { return canonicalName; }
}
