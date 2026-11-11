// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Objects;

/** One logical test at one deterministic build execution target. */
public record ExecutableTestIdentity(ExecutionTarget target, TestIdentity test)
		implements Comparable<ExecutableTestIdentity>
{
	public ExecutableTestIdentity
	{
		if (target == null) throw new IllegalArgumentException("Execution target must not be null");
		if (test == null) throw new IllegalArgumentException("Logical test identity must not be null");
	}

	public static ExecutableTestIdentity parse(String value)
	{
		Objects.requireNonNull(value, "value");
		int targetStart = value.startsWith("gradle::") ? "gradle::".length() : 0;
		int separator = value.indexOf("::", targetStart);
		if (separator <= 0 || separator == value.length() - 2)
			throw new IllegalArgumentException("Malformed executable test identity: " + value);
		return new ExecutableTestIdentity(ExecutionTarget.parse(value.substring(0, separator)),
				TestIdentity.parse(value.substring(separator + 2)));
	}

	@Override public String toString() { return target + "::" + test; }
	@Override public int compareTo(ExecutableTestIdentity other)
	{
		int byTarget = target.compareTo(other.target);
		return byTarget != 0 ? byTarget : test.compareTo(other.test);
	}
}
