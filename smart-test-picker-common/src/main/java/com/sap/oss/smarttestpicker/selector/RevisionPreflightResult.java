// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.Optional;

/** Typed 5e preflight result, kept separate from selector output policy. */
public record RevisionPreflightResult(RevisionPreflightStatus status, String reason,
		SelectionRevisionInterval interval)
{
	public RevisionPreflightResult
	{
		if (status == RevisionPreflightStatus.ELIGIBLE && interval == null)
			throw new IllegalArgumentException("Eligible preflight requires an effective interval");
		if (status != RevisionPreflightStatus.ELIGIBLE && interval != null)
			throw new IllegalArgumentException("Ineligible preflight cannot expose an effective interval");
	}

	public Optional<SelectionRevisionInterval> effectiveInterval() { return Optional.ofNullable(interval); }
}
