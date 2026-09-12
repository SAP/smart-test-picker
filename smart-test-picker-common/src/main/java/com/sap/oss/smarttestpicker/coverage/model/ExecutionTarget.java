// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Objects;

/** Deterministic build execution location within one exact revision. */
public record ExecutionTarget(BuildTool buildTool, String targetId) implements Comparable<ExecutionTarget>
{
	public ExecutionTarget
	{
		if (buildTool == null) throw new IllegalArgumentException("Build tool must not be null");
		if (targetId == null || targetId.isBlank() || !targetId.equals(targetId.trim())
				|| targetId.codePoints().anyMatch(Character::isISOControl))
			throw new IllegalArgumentException("Malformed execution target ID: " + targetId);

		switch (buildTool)
		{
			case MAVEN -> validateMavenTarget(targetId);
			case GRADLE -> validateGradleTarget(targetId);
		}
	}

	public static ExecutionTarget parse(String value)
	{
		Objects.requireNonNull(value, "value");
		int separator = value.indexOf(':');
		if (separator <= 0 || separator == value.length() - 1)
			throw new IllegalArgumentException("Malformed execution target: " + value);
		return new ExecutionTarget(BuildTool.parse(value.substring(0, separator)), value.substring(separator + 1));
	}

	private static void validateMavenTarget(String targetId)
	{
		if (targetId.equals(".")) return;
		if (targetId.startsWith("/") || targetId.endsWith("/") || targetId.contains("\\")
				|| targetId.contains(":") || targetId.startsWith("./"))
			throw new IllegalArgumentException("Malformed Maven target ID: " + targetId);
		for (String segment : targetId.split("/", -1))
			if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
				throw new IllegalArgumentException("Malformed Maven target ID: " + targetId);
	}

	private static void validateGradleTarget(String targetId)
	{
		if (!targetId.startsWith(":") || targetId.endsWith(":") || targetId.contains("::")
				|| targetId.contains("/") || targetId.contains("\\"))
			throw new IllegalArgumentException("Malformed Gradle target ID: " + targetId);
		for (String segment : targetId.substring(1).split(":", -1))
			if (segment.isBlank() || !segment.equals(segment.trim()))
				throw new IllegalArgumentException("Malformed Gradle target ID: " + targetId);
	}

	@Override public String toString() { return buildTool + ":" + targetId; }
	@Override public int compareTo(ExecutionTarget other)
	{
		int byBuildTool = buildTool.compareTo(other.buildTool);
		return byBuildTool != 0 ? byBuildTool : targetId.compareTo(other.targetId);
	}
}
