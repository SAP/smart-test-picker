// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.execution;

import java.util.List;

public record ExecutionPlanEntry(String selectedTestIdentity, ExecutionMode executionMode,
		ExecutionFallbackCause executionFallbackCause, String module, String generatedSurefireSelector,
		List<String> predictedExpansion)
{
	public ExecutionPlanEntry
	{
		predictedExpansion = predictedExpansion == null ? List.of() : List.copyOf(predictedExpansion);
		if (executionMode == ExecutionMode.METHOD_EXACT && executionFallbackCause != null)
			throw new IllegalArgumentException("METHOD_EXACT cannot have a fallback cause");
		if (executionMode != ExecutionMode.METHOD_EXACT && executionFallbackCause == null
				&& executionMode != ExecutionMode.NOT_EXECUTABLE_ERROR)
			throw new IllegalArgumentException("Fallback execution requires a cause");
	}
}
