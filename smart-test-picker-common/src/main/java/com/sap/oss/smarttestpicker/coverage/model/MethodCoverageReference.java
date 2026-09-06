// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

/** Reserved schema-v1 affordance; resolving this artifact is not implemented in Task 5a. */
public record MethodCoverageReference(String artifact, String sha256)
{
	public MethodCoverageReference
	{
		if (artifact == null || artifact.isBlank() || sha256 == null || !sha256.matches("sha256:[0-9a-f]{64}"))
			throw new IllegalArgumentException("valid method artifact and SHA-256 are required");
	}
}
