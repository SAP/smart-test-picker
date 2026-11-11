// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.Objects;
import java.util.Optional;

/** Keeps integration eligibility separate from the existing selector-output contract. */
public record ExplicitPrSelectionResult(ExplicitPrSelectionStatus status, SelectionOutput selectionOutput,
		SelectionContext selectionContext, String reason)
{
	public ExplicitPrSelectionResult
	{
		Objects.requireNonNull(status, "status");
		if ((status == ExplicitPrSelectionStatus.SELECTION_RESULT) != (selectionOutput != null))
			throw new IllegalArgumentException("Only SELECTION_RESULT may contain selector output");
		if (status != ExplicitPrSelectionStatus.SELECTION_RESULT) Objects.requireNonNull(reason, "reason");
	}

	public static ExplicitPrSelectionResult selection(SelectionOutput output, SelectionContext context)
	{
		return new ExplicitPrSelectionResult(ExplicitPrSelectionStatus.SELECTION_RESULT,
				Objects.requireNonNull(output), context, null);
	}

	public static ExplicitPrSelectionResult failure(ExplicitPrSelectionStatus status, String reason)
	{
		return new ExplicitPrSelectionResult(status, null, null, reason);
	}

	public Optional<SelectionOutput> output() { return Optional.ofNullable(selectionOutput); }
	public Optional<SelectionContext> context() { return Optional.ofNullable(selectionContext); }
}
