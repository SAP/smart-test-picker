// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

enum MetadataDiagnosticReason {
	INVALID_REPOSITORY_INTERFACE,
	MISSING_DOMAIN_TYPE,
	CONFLICTING_METADATA,
	ALIAS_CONFLICT,
	DUPLICATE_EQUAL_METADATA,
	FACTORY_CUSTOMIZER_FAILURE
}
