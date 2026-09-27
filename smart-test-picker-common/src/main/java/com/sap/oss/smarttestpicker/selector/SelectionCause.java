// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.Comparator;

/** Structured, deterministic explanation for one selection decision. */
public record SelectionCause(SelectionCauseType type, String symbol)
		implements Comparable<SelectionCause>
{
	private static final Comparator<SelectionCause> ORDER = Comparator
			.comparing(SelectionCause::type)
			.thenComparing(cause -> cause.symbol() != null ? cause.symbol() : "");

	@Override
	public int compareTo(SelectionCause other)
	{
		return ORDER.compare(this, other);
	}
}
