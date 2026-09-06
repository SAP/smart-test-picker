// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.validation;

import java.util.List;

public record ValidationResult(List<ValidationError> errors)
{
	public ValidationResult { errors = List.copyOf(errors); }
	public boolean isValid() { return errors.isEmpty(); }
	public boolean has(ValidationCode code) { return errors.stream().anyMatch(error -> error.code() == code); }
	public static ValidationResult valid() { return new ValidationResult(List.of()); }
}
