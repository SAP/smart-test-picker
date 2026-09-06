// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime.model;

public enum UnattributedReason {
	NO_ACTIVE_TEST,
	TEST_ALREADY_FINISHED,
	UNKNOWN_CONTEXT,
	STARTUP,
	SHUTDOWN,
	LATE_EVENT,
	TRANSFORMATION_ERROR
}
