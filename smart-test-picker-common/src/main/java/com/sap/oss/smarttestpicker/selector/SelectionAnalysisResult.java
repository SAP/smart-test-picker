// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.Objects;
import java.util.Optional;

/** Either safe semantic input for TASK 37 or a fail-open RUN_ALL result. */
public final class SelectionAnalysisResult
{
	private final SelectionContext context;
	private final String reason;

	private SelectionAnalysisResult(SelectionContext context, String reason)
	{
		this.context = context;
		this.reason = reason;
	}

	public static SelectionAnalysisResult ready(SelectionContext context)
	{
		return new SelectionAnalysisResult(Objects.requireNonNull(context), null);
	}

	public static SelectionAnalysisResult runAll(String reason)
	{
		return new SelectionAnalysisResult(null, Objects.requireNonNull(reason));
	}

	public boolean isRunAll() { return context == null; }
	public Optional<SelectionContext> context() { return Optional.ofNullable(context); }
	public String reason() { return reason; }
}
