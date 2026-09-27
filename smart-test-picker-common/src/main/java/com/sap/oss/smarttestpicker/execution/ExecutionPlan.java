// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.execution;

import java.util.List;

public record ExecutionPlan(List<ExecutionPlanEntry> entries)
{
	public ExecutionPlan { entries = List.copyOf(entries); }
}
