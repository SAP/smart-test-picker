// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.policy;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.sap.oss.smarttestpicker.policy.PolicyAction.*;
import static com.sap.oss.smarttestpicker.policy.PolicyContext.*;
import static com.sap.oss.smarttestpicker.policy.PolicySource.*;
import static com.sap.oss.smarttestpicker.policy.Retryability.*;

/** Pure deterministic TASK72 policy: normalized fact in, bounded decision out. */
public final class CentralFailurePolicy
{
	private static final Map<PolicySource, Set<String>> SUCCESSES = Map.of(
			SCM, Set.of("RESOLVED"),
			REVISION, Set.of("ELIGIBLE", "SELECTION_RESULT"),
			SELECTOR, Set.of("SELECTION_COMPLETE", "SELECTED", "NONE"),
			MAPPING, Set.of("STORED", "ALREADY_EXISTS_IDENTICAL", "POINTER_UPDATED", "POINTER_ALREADY_CURRENT", "STALE_POINTER_UPDATE"),
			STORAGE, Set.of("FOUND", "STORED", "ALREADY_EXISTS_IDENTICAL", "POINTER_UPDATED", "POINTER_ALREADY_CURRENT", "STALE_POINTER_UPDATE"));
	private static final Set<String> SCM_FALLBACKS = Set.of("NOT_PR_BUILD", "UNSUPPORTED_SCM_CONTEXT", "PR_METADATA_MISSING",
			"TARGET_BRANCH_UNKNOWN", "SOURCE_BRANCH_UNKNOWN", "PR_HEAD_UNRESOLVED", "INTEGRATION_REVISION_UNRESOLVED",
			"PR_BASE_UNRESOLVED", "WORKSPACE_REVISION_UNRESOLVED", "AMBIGUOUS_SCM_CONTEXT", "REQUIRED_COMMIT_MISSING",
			"SHALLOW_HISTORY", "SYNTHETIC_MERGE_UNCLASSIFIED");
	private static final Set<String> INTEGRITY = Set.of("TARGET_MISSING", "INVALID_RESPONSE", "INTEGRITY_FAILURE",
			"IMMUTABILITY_CONFLICT", "POINTER_CONFLICT", "RUNTIME_INTEGRITY_FAILURE");
	private static final Set<String> CREDENTIAL_FAILURES = Set.of("CREDENTIAL_REQUIRED", "CREDENTIAL_NOT_FOUND",
			"CREDENTIAL_TYPE_UNSUPPORTED", "AUTHENTICATION_FAILED", "AUTHORIZATION_FAILED");
	private static final Set<String> MAPPING_FAILURES = Set.of("CONFIGURATION_INVALID", "REVISION_MISMATCH", "INVENTORY_REQUIRED",
			"FRAGMENT_MISSING", "FRAGMENT_INVALID", "FRAGMENT_INCOMPLETE", "EVIDENCE_MISSING", "EVIDENCE_INVALID",
			"ACCOUNTING_INCOMPLETE", "DUPLICATE_IDENTITY", "DUPLICATE_SHARD", "JOIN_INVALID",
			"PUBLICATION_REVISION_MISMATCH", "RUNTIME_INTEGRITY_FAILURE");

	public PolicyDecision evaluate(PolicyInput input) { return evaluateOne(Objects.requireNonNull(input, "input")).decision(); }

	public PolicyDecision evaluate(Collection<PolicyInput> inputs)
	{
		if (inputs == null || inputs.isEmpty()) throw new IllegalArgumentException("policy inputs must not be empty");
		return inputs.stream().map(input -> evaluateOne(Objects.requireNonNull(input, "input")))
				.max(Comparator.comparingInt(Evaluated::precedence).thenComparing(e -> e.decision().source().name())
						.thenComparing(e -> e.decision().originalOutcome()).thenComparing(e -> e.operation().name()))
				.orElseThrow().decision();
	}

	private Evaluated evaluateOne(PolicyInput input)
	{
		String outcome = input.outcome();
		if (CREDENTIAL_FAILURES.contains(outcome) && (input.source() == PolicySource.CREDENTIALS || input.source() == STORAGE))
			return decision(input, FAIL_BUILD, "STORAGE_" + outcome, "Configured storage security could not be satisfied.", AFTER_USER_FIX, true, 500);
		if ((outcome.equals("CONFIGURATION_ERROR") || outcome.equals("CONFIGURATION_INVALID")))
			return decision(input, FAIL_BUILD, "STORAGE_CONFIGURATION_INVALID", "Required configuration is invalid.", AFTER_USER_FIX, true, 500);
		if (INTEGRITY.contains(outcome) && (input.source() == STORAGE || input.source() == MAPPING))
			return decision(input, FAIL_BUILD, outcome.equals("TARGET_MISSING") ? "BROKEN_LATEST_POINTER" : "STORAGE_INTEGRITY_VIOLATION",
					"Published or produced data failed an integrity requirement.", outcome.equals("POINTER_CONFLICT") ? AFTER_USER_FIX : NOT_RETRYABLE, outcome.equals("POINTER_CONFLICT"), 500);
		if (input.source() == MAPPING && MAPPING_FAILURES.contains(outcome))
			return decision(input, FAIL_BUILD, "MAPPING_INCOMPLETE", "Mapping output is incomplete, inconsistent, or invalid.", NOT_RETRYABLE, input.userActionRequired(), 500);
		if (input.source() == REVISION && outcome.equals("BASE_OUT_OF_DATE") && input.context() == PR_SELECTION)
			return decision(input, FAIL_BUILD, "BASE_OUT_OF_DATE", "The pull request base must be updated before selective testing.", AFTER_USER_FIX, true, 400);
		if (outcome.equals("ERROR"))
			return decision(input, FAIL_BUILD, "UNCLASSIFIED_PREFLIGHT_ERROR", "A required operation failed for an unclassified reason.", UNKNOWN, input.userActionRequired(), 500);
		if (input.source() == SELECTOR && outcome.equals("FULL_SUITE"))
			return decision(input, RUN_FULL_SUITE, "SELECTOR_REQUIRES_FULL_SUITE", "The selector produced a safe full-suite domain result.", input.retryabilityHint(), input.userActionRequired(), 300);
		if (input.source() == STORAGE && outcome.equals("NO_POINTER") && input.context() == MAP_LOOKUP)
			return fallbackOrFail(input, "NO_COVERAGE_MAP", "Coverage optimization data is not available; full verification remains possible.", NOT_RETRYABLE);
		if (input.source() == STORAGE && (outcome.equals("REMOTE_UNAVAILABLE") || outcome.equals("TIMEOUT")))
		{
			if (input.context() == MAP_LOOKUP)
				return fallbackOrFail(input, "REMOTE_OPTIMIZATION_UNAVAILABLE", "Remote optimization data is temporarily unavailable.", TRANSIENT);
			return decision(input, FAIL_BUILD, "REMOTE_STORAGE_UNAVAILABLE", "Remote storage is required for this operation.", TRANSIENT, input.userActionRequired(), 500);
		}
		if (input.source() == SCM && SCM_FALLBACKS.contains(outcome) && input.context() == PR_SELECTION)
			return fallbackOrFail(input, "SCM_OPTIMIZATION_UNAVAILABLE", "Automatic pull-request optimization could not be established.", input.retryabilityHint());
		if (input.source() == INVENTORY && outcome.equals("PR_HEAD_INVENTORY_UNAVAILABLE") && input.context() == PR_SELECTION)
			return fallbackOrFail(input, "PR_HEAD_INVENTORY_UNAVAILABLE", "Authoritative pull-request head inventory is unavailable.", input.retryabilityHint());
		if (SUCCESSES.getOrDefault(input.source(), Set.of()).contains(outcome))
			return decision(input, CONTINUE, "SAFE_DOMAIN_RESULT", "The producer returned a known safe domain result.", input.retryabilityHint(), input.userActionRequired(), 100);
		if (isOptimization(input.context()) && input.fullSuitePossible())
			return decision(input, RUN_FULL_SUITE, "UNKNOWN_OPTIMIZATION_FAILURE", "Unknown optimization outcome; selective execution is disabled.", input.retryabilityHint(), input.userActionRequired(), 300);
		return decision(input, FAIL_BUILD, isOptimization(input.context()) ? "UNKNOWN_OPTIMIZATION_FAILURE" : "UNKNOWN_STRICT_FAILURE",
				"Unknown outcome cannot be accepted safely in this context.", input.retryabilityHint(), input.userActionRequired(), 500);
	}

	private Evaluated fallbackOrFail(PolicyInput input, String code, String reason, Retryability retryability)
	{
		return input.fullSuitePossible()
				? decision(input, RUN_FULL_SUITE, code, reason, retryability, input.userActionRequired(), 300)
				: decision(input, FAIL_BUILD, code, "Full-suite fallback is not safely available.", retryability, input.userActionRequired(), 500);
	}

	private static boolean isOptimization(PolicyContext context)
	{
		return context == PR_SELECTION || context == TEST_SELECTION || context == MAP_LOOKUP;
	}

	private Evaluated decision(PolicyInput input, PolicyAction action, String code, String reason,
			Retryability retryability, boolean userActionRequired, int precedence)
	{
		return new Evaluated(new PolicyDecision(action, code, reason, input.source(), input.outcome(), retryability,
				userActionRequired, action == RUN_FULL_SUITE), input.operation(), precedence);
	}

	private record Evaluated(PolicyDecision decision, PolicyOperation operation, int precedence) {}
}
