// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

enum AdvisorAuditReason {
	NONE,
	BEAN_IDENTITY_CHANGED,
	STP_ADVISOR_COUNT,
	STP_ADVISOR_POSITION,
	EXISTING_ADVISORS_CHANGED,
	CACHE_ORDER_CHANGED,
	PROXY_STRUCTURE_CHANGED,
	RESTORATION_FAILED
}
