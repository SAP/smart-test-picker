// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

public enum UnmappedReason
{
	FAILED, SKIPPED, TIMEOUT, COLLECTION_FAILED,
	/** Assigned to an existing shard fragment but absent from both its mapped and unmapped reports. */
	ASSIGNED_NOT_REPORTED
}
