# TASK75 — central failure-policy E2E validation

## Goal

TASK75 validates the TASK72–TASK74 chain through public Jenkins execution without redesigning the
engine: producer fact -> `JenkinsPolicyAdapter` -> `PolicyInput` -> `CentralFailurePolicy` ->
`PolicyDecision` -> `JenkinsPolicyDecisionHandler` -> CI behavior. Item 8 remains in progress for
TASK76.

## Baseline

The clean baselines were main STP `203d998` and Jenkins plugin `e54d0fb`. Docker used Jenkins
2.516.2, one inbound agent, and Nexus 3.96.0. The deterministic fixture was the existing tiny Git
fixture in `verification/task74/docker-strict-selection.groovy`: two revisions, one covered class,
one test identity, and a two-entry unrestricted execution sentinel. No Spring Core or PetClinic
checkout was used.

## Architecture under test

Production inspection found one `CentralFailurePolicy` instance, one Jenkins fact-normalization
class (`JenkinsPolicyAdapter`), and one action-realization/logging class
(`JenkinsPolicyDecisionHandler`). Producers translate facts but do not select actions. Searches for
the representative hard and fallback outcomes found no second Jenkins action table.

## Public API contract

Selection retains its prior fields and adds the seven-field policy envelope. A fallback contains
`selectorStatus=FULL_SUITE`, `planMode=RUN_ALL`, action, reason, source, outcome, retryability,
fallback and user-action metadata. Lookup returns `coverageMap` plus the same policy envelope.
`coverageMap=null` is therefore unambiguous for `NO_POINTER`. Strict lookup throws after logging its
bounded policy decision. The migration implication remains that callers of `stpLookupCoverageMap`
must read the structured result rather than a bare path/null.

## Docker validation

Fresh TASK75 public runs proved:

| Scenario | Producer fact | Context | Expected | Actual | Jenkins | Full tests | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| selector full suite | FULL_SUITE | TEST_SELECTION | RUN_FULL_SUITE | RUN_FULL_SUITE | SUCCESS | 2/2 | PASS |
| stale base | BASE_OUT_OF_DATE | PR_SELECTION | FAIL_BUILD | FAIL_BUILD | FAILURE | no | PASS |
| no FILE pointer | NO_POINTER | MAP_LOOKUP | RUN_FULL_SUITE | RUN_FULL_SUITE | SUCCESS | 2/2 | PASS |
| broken FILE pointer | TARGET_MISSING | MAP_LOOKUP | FAIL_BUILD | FAIL_BUILD | FAILURE | no | PASS |
| remote unavailable lookup | REMOTE_UNAVAILABLE | MAP_LOOKUP | RUN_FULL_SUITE | RUN_FULL_SUITE | SUCCESS | unrestricted sentinel | PASS |
| authentication failed | AUTHENTICATION_FAILED | MAP_LOOKUP | FAIL_BUILD | FAIL_BUILD | FAILURE | no | PASS |
| missing mapping fragment, before fix | raw IOException | MAP_PUBLICATION | FAIL_BUILD | bypassed policy | FAILURE | no | FAIL (defect captured) |
| missing mapping fragment, after fix | FRAGMENT_MISSING | MAP_PUBLICATION | FAIL_BUILD | FAIL_BUILD | FAILURE | no | PASS |

The exact control logs use the bounded form `[STP] policy source=... outcome=... context=... action=...
reasonCode=... retryability=... fallbackOccurred=... userActionRequired=...`. The representative
fallback invocations contained no selective test filter and completed the entire two-test sentinel.
The `NONE` non-widening and `SELECTED` preservation paths are covered by the current public-step
fixture tests and TASK74 Docker evidence; they were not falsely labelled as fresh TASK75 Docker runs.

## Remaining matrix evidence

The following focused TASK73/TASK74 automated and Docker evidence was revalidated by the 279-test
common suite, 70-test Jenkins suite and 32-test runtime suite: SELECTED, NONE, inventory unavailable,
inventory invalid, inventory revision mismatch, SCM optional fallbacks, timeout lookup, transient
publication failure, credential-not-found, configuration error, mapping accounting, stale pointer,
immutable conflict, pointer conflict, FILE/RAW parity, and unknown optimization/strict defaults.
Synthetic merge remains covered by TASK74. Workspace-moved, unsupported credential type,
authorization failure, restart and broad storage concurrency are bounded by TASK74/TASK71/TASK70 and
were not rerun as TASK75 Docker claims. No retry, fetch, unshallow, checkout repair, or persistent
policy state was introduced.

## Defect found and fix

Before production changes, `verification/task75/pre-fix-mapping-typing.groovy` captured a public
missing-fragment build that failed with a raw `IOException` and emitted no policy decision. The defect
was producer typing at the publication boundary. The smallest fix maps revision mismatch,
missing/extra fragment set, and missing/extra evidence set to existing `MappingOutcome` values. It
adds no action or rule. The post-fix run emitted exactly one `FRAGMENT_MISSING -> FAIL_BUILD` terminal
decision with reason `MAPPING_INCOMPLETE`; no incomplete map was published.

## Safety, observability, and secrets

All `RUN_FULL_SUITE` cases concern optional optimization data while the trusted checkout and ordinary
target remain available. Realization sets `RUN_ALL`; no stale selected list, `--tests`, or `-Dtest`
restriction remains. Integrity, revision, security, configuration and mapping facts remain strict
regardless of `fullSuitePossible`. A runtime-only marker was used to provision Nexus and was not
written to committed evidence; repository, Pipeline and container-log scans found no credential or
Authorization-header disclosure.

## Automated tests

`./gradlew :smart-test-picker-common:test` passed (279 tests, 7 skipped; up-to-date execution).
The Jenkins suite passed 70 tests after the focused regression. Mapping runtime passed 32 tests with
the three opt-in Nexus tests skipped. `mvn ... -DskipTests package` and deterministic adapter manifest
verification passed.

## Remaining TASK76 scope

TASK76 owns the final item-8 closure decision. TASK75 answers that the implemented policy behaves
correctly through the exercised public Jenkins paths after the one bounded producer-typing defect was
fixed. Item 8 remains **IN PROGRESS**.

