// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.policy;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, safe-to-report policy result. */
public record PolicyDecision(
		PolicyAction action,
		String reasonCode,
		String safeReason,
		PolicySource source,
		String originalOutcome,
		Retryability retryability,
		boolean userActionRequired,
		boolean fallbackOccurred)
{
	public static final int MAX_REASON_LENGTH = 256;
	private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

	public PolicyDecision
	{
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(retryability, "retryability");
		reasonCode = requireCode(reasonCode, "reasonCode");
		originalOutcome = requireCode(originalOutcome, "originalOutcome");
		safeReason = requireText(safeReason, "safeReason", MAX_REASON_LENGTH);
		if (fallbackOccurred != (action == PolicyAction.RUN_FULL_SUITE))
			throw new IllegalArgumentException("fallbackOccurred must identify RUN_FULL_SUITE only");
	}

	private static String requireCode(String value, String field)
	{
		if (value == null || !CODE.matcher(value).matches())
			throw new IllegalArgumentException(field + " must be a bounded upper-case code");
		return value;
	}

	private static String requireText(String value, String field, int maximum)
	{
		if (value == null || value.isBlank() || value.length() > maximum)
			throw new IllegalArgumentException(field + " must be non-blank and bounded");
		return value;
	}
}
