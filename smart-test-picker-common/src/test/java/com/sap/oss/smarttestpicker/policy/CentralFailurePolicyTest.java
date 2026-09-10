// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.sap.oss.smarttestpicker.policy.PolicyAction.*;
import static com.sap.oss.smarttestpicker.policy.PolicyContext.*;
import static com.sap.oss.smarttestpicker.policy.PolicySource.*;
import static com.sap.oss.smarttestpicker.policy.Retryability.*;
import static org.junit.jupiter.api.Assertions.*;

class CentralFailurePolicyTest
{
	private final CentralFailurePolicy policy = new CentralFailurePolicy();

	@Test void knownDomainResultsContinue()
	{
		assertAll(
				() -> assertAction(CONTINUE, input(SCM, "RESOLVED", PR_SELECTION, PolicyOperation.SCM_RESOLUTION, true)),
				() -> assertAction(CONTINUE, input(SELECTOR, "SELECTED", TEST_SELECTION, PolicyOperation.SELECTOR, false)),
				() -> assertAction(CONTINUE, input(SELECTOR, "NONE", TEST_SELECTION, PolicyOperation.SELECTOR, false)),
				() -> assertAction(CONTINUE, input(STORAGE, "FOUND", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, false)),
				() -> assertAction(CONTINUE, input(STORAGE, "STORED", MAP_PUBLICATION, PolicyOperation.MAP_PUBLICATION, false)),
				() -> assertAction(CONTINUE, input(STORAGE, "ALREADY_EXISTS_IDENTICAL", MAP_PUBLICATION, PolicyOperation.MAP_PUBLICATION, false)),
				() -> assertAction(CONTINUE, input(STORAGE, "POINTER_ALREADY_CURRENT", MAP_PUBLICATION, PolicyOperation.MAP_PUBLICATION, false)),
				() -> assertAction(CONTINUE, input(STORAGE, "STALE_POINTER_UPDATE", MAP_PUBLICATION, PolicyOperation.MAP_PUBLICATION, false)));
	}

	@Test void selectorFullSuiteIsAnAcceptedDomainFallback()
	{
		PolicyDecision decision = policy.evaluate(input(SELECTOR, "FULL_SUITE", TEST_SELECTION, PolicyOperation.SELECTOR, true));
		assertEquals(RUN_FULL_SUITE, decision.action());
		assertEquals("SELECTOR_REQUIRES_FULL_SUITE", decision.reasonCode());
		assertTrue(decision.fallbackOccurred());
	}

	@Test void baseOutOfDateCannotBeWeakenedByFallbackAvailability()
	{
		PolicyDecision decision = policy.evaluate(input(REVISION, "BASE_OUT_OF_DATE", PR_SELECTION, PolicyOperation.REVISION_PREFLIGHT, true));
		assertEquals(FAIL_BUILD, decision.action());
		assertEquals(AFTER_USER_FIX, decision.retryability());
		assertTrue(decision.userActionRequired());
		assertFalse(decision.fallbackOccurred());
	}

	@Test void latestLookupProvenanceHasDifferentPolicyFacts()
	{
		PolicyDecision absent = policy.evaluate(input(STORAGE, "NO_POINTER", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true));
		PolicyDecision broken = policy.evaluate(input(STORAGE, "TARGET_MISSING", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true));
		assertEquals(RUN_FULL_SUITE, absent.action());
		assertEquals("NO_COVERAGE_MAP", absent.reasonCode());
		assertEquals(FAIL_BUILD, broken.action());
		assertEquals("BROKEN_LATEST_POINTER", broken.reasonCode());
	}

	@Test void transientStorageIsContextSensitive()
	{
		for (String outcome : List.of("REMOTE_UNAVAILABLE", "TIMEOUT"))
		{
			PolicyDecision lookup = policy.evaluate(input(STORAGE, outcome, MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true));
			assertEquals(RUN_FULL_SUITE, lookup.action());
			assertEquals(TRANSIENT, lookup.retryability());
			assertAction(FAIL_BUILD, input(STORAGE, outcome, MAP_PUBLICATION, PolicyOperation.MAP_PUBLICATION, true));
		}
	}

	@Test void integrityConfigurationAndCredentialsAlwaysFail()
	{
		for (String outcome : List.of("INVALID_RESPONSE", "INTEGRITY_FAILURE", "IMMUTABILITY_CONFLICT", "POINTER_CONFLICT"))
			assertAction(FAIL_BUILD, input(STORAGE, outcome, MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true));
		assertAction(FAIL_BUILD, input(STORAGE, "CONFIGURATION_ERROR", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true));
		for (String outcome : List.of("CREDENTIAL_REQUIRED", "CREDENTIAL_NOT_FOUND", "CREDENTIAL_TYPE_UNSUPPORTED",
				"AUTHENTICATION_FAILED", "AUTHORIZATION_FAILED"))
		{
			PolicyDecision decision = policy.evaluate(input(CREDENTIALS, outcome, MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true));
			assertEquals(FAIL_BUILD, decision.action());
			assertEquals(AFTER_USER_FIX, decision.retryability());
			assertTrue(decision.userActionRequired());
		}
	}

	@Test void automaticScmAndInventoryFactsFallbackOnlyWhenSafe()
	{
		for (String outcome : List.of("NOT_PR_BUILD", "UNSUPPORTED_SCM_CONTEXT", "PR_METADATA_MISSING", "TARGET_BRANCH_UNKNOWN",
				"SOURCE_BRANCH_UNKNOWN", "PR_HEAD_UNRESOLVED", "INTEGRATION_REVISION_UNRESOLVED", "PR_BASE_UNRESOLVED",
				"WORKSPACE_REVISION_UNRESOLVED", "AMBIGUOUS_SCM_CONTEXT", "REQUIRED_COMMIT_MISSING", "SHALLOW_HISTORY",
				"SYNTHETIC_MERGE_UNCLASSIFIED"))
		{
			assertAction(RUN_FULL_SUITE, input(SCM, outcome, PR_SELECTION, PolicyOperation.SCM_RESOLUTION, true));
			assertAction(FAIL_BUILD, input(SCM, outcome, PR_SELECTION, PolicyOperation.SCM_RESOLUTION, false));
		}
		assertAction(RUN_FULL_SUITE, input(INVENTORY, "PR_HEAD_INVENTORY_UNAVAILABLE", PR_SELECTION, PolicyOperation.INVENTORY_DISCOVERY, true));
		assertAction(FAIL_BUILD, input(INVENTORY, "PR_HEAD_INVENTORY_UNAVAILABLE", PR_SELECTION, PolicyOperation.INVENTORY_DISCOVERY, false));
	}

	@Test void normalizedMappingFailuresFailWithoutReinterpretingPerTestFacts()
	{
		for (String outcome : List.of("CONFIGURATION_INVALID", "REVISION_MISMATCH", "INVENTORY_REQUIRED", "FRAGMENT_MISSING",
				"FRAGMENT_INVALID", "FRAGMENT_INCOMPLETE", "EVIDENCE_MISSING", "EVIDENCE_INVALID", "ACCOUNTING_INCOMPLETE",
				"DUPLICATE_IDENTITY", "DUPLICATE_SHARD", "JOIN_INVALID", "PUBLICATION_REVISION_MISMATCH", "RUNTIME_INTEGRITY_FAILURE"))
			assertAction(FAIL_BUILD, input(MAPPING, outcome, MAPPING_BUILD, PolicyOperation.MAPPING_JOIN, true));
		for (String perTest : List.of("FAILED", "SKIPPED", "TIMEOUT", "COLLECTION_FAILED"))
			assertEquals("UNKNOWN_STRICT_FAILURE", policy.evaluate(input(MAPPING, perTest, MAPPING_BUILD, PolicyOperation.MAPPING_JOIN, true)).reasonCode());
	}

	@Test void unknownOutcomesAreConservative()
	{
		assertAction(RUN_FULL_SUITE, input(STORAGE, "NEW_FAILURE", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true));
		assertAction(FAIL_BUILD, input(STORAGE, "NEW_FAILURE", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, false));
		assertAction(FAIL_BUILD, input(MAPPING, "NEW_FAILURE", MAPPING_BUILD, PolicyOperation.MAPPING_JOIN, true));
		assertAction(FAIL_BUILD, input(REVISION, "ERROR", PR_SELECTION, PolicyOperation.REVISION_PREFLIGHT, true));
	}

	@Test void aggregationUsesStrongestDecisionAndIsOrderIndependent()
	{
		PolicyInput ok = input(STORAGE, "FOUND", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true);
		PolicyInput fallback = input(STORAGE, "NO_POINTER", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true);
		PolicyInput failure = input(STORAGE, "INTEGRITY_FAILURE", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true);
		assertEquals(RUN_FULL_SUITE, policy.evaluate(List.of(ok, fallback)).action());
		assertEquals(FAIL_BUILD, policy.evaluate(List.of(fallback, failure)).action());
		assertEquals(policy.evaluate(List.of(ok, fallback, failure)), policy.evaluate(List.of(failure, fallback, ok)));
		assertThrows(IllegalArgumentException.class, () -> policy.evaluate(List.of()));
	}

	@Test void inputRejectsUnboundedOrUnsafeShapes()
	{
		assertThrows(NullPointerException.class, () -> new PolicyInput(null, "FOUND", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, null, false, true, UNKNOWN, false, Map.of()));
		assertThrows(IllegalArgumentException.class, () -> input(STORAGE, " ", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, true));
		assertThrows(IllegalArgumentException.class, () -> new PolicyInput(STORAGE, "FOUND", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, null, false, true, UNKNOWN, false, Map.of("password", "secret")));
		assertThrows(IllegalArgumentException.class, () -> new PolicyInput(STORAGE, "FOUND", MAP_LOOKUP, PolicyOperation.LATEST_MAP_LOOKUP, null, false, true, UNKNOWN, false, Map.of("backend", new Object())));
	}

	private PolicyInput input(PolicySource source, String outcome, PolicyContext context, PolicyOperation operation, boolean fullSuite)
	{
		return new PolicyInput(source, outcome, context, operation, null, true, fullSuite, UNKNOWN, false, Map.of());
	}

	private void assertAction(PolicyAction expected, PolicyInput input) { assertEquals(expected, policy.evaluate(input).action()); }
}
