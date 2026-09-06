// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.validation;

public class CoverageMapValidationException extends IllegalArgumentException
{
	private final ValidationError error;

	public CoverageMapValidationException(ValidationError error)
	{
		super(error.message());
		this.error = error;
	}

	public ValidationError getError() { return error; }
}
