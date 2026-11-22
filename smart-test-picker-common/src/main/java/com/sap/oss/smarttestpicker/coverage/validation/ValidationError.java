// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.validation;

/** Structured validation failure containing a category, code and explanatory message. */
public record ValidationError(ValidationCategory category, ValidationCode code, String message) {}
