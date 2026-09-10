// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.policy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, bounded fact presented to the central policy. */
public record PolicyInput(
		PolicySource source,
		String outcome,
		PolicyContext context,
		PolicyOperation operation,
		String diagnosticCode,
		boolean safeSelectivePossible,
		boolean fullSuitePossible,
		Retryability retryabilityHint,
		boolean userActionRequired,
		Map<String, Object> metadata)
{
	public static final int MAX_CODE_LENGTH = 64;
	public static final int MAX_METADATA_ENTRIES = 8;
	public static final int MAX_METADATA_VALUE_LENGTH = 128;
	private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
	private static final Set<String> METADATA_KEYS = Set.of(
			"backend", "mode", "provider", "correlationId", "attempt", "httpStatus");

	public PolicyInput
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(retryabilityHint, "retryabilityHint");
		outcome = requireCode(outcome, "outcome");
		if (diagnosticCode != null) diagnosticCode = requireCode(diagnosticCode, "diagnosticCode");
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
		if (metadata.size() > MAX_METADATA_ENTRIES)
			throw new IllegalArgumentException("metadata has too many entries");
		for (Map.Entry<String, Object> entry : metadata.entrySet())
		{
			if (!METADATA_KEYS.contains(entry.getKey()))
				throw new IllegalArgumentException("metadata key is not allowlisted: " + entry.getKey());
			Object value = Objects.requireNonNull(entry.getValue(), "metadata value");
			if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
				throw new IllegalArgumentException("metadata values must be scalar");
			if (String.valueOf(value).length() > MAX_METADATA_VALUE_LENGTH)
				throw new IllegalArgumentException("metadata value is too long");
		}
	}

	private static String requireCode(String value, String field)
	{
		if (value == null || !CODE.matcher(value).matches())
			throw new IllegalArgumentException(field + " must be a bounded upper-case code");
		return value;
	}
}
