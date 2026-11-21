// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.validation;

/** Broad category of schema, integrity, structural or completeness failure. */
public enum ValidationCategory
{
	INCOMPATIBLE_SCHEMA,
	CORRUPT_MAP,
	INVALID_STRUCTURE,
	INCOMPLETE_MAP
}
