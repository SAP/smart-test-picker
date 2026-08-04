// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

enum AdvisorInstallationState {
	NOT_ATTEMPTED,
	INSERTED_DISABLED,
	AUDIT_PASSED,
	AUDIT_FAILED_RESTORED,
	AUDIT_FAILED_RESTORE_FAILED
}
