# TASK73 — central failure-policy engine

## Goal

TASK73 implements the deterministic central policy layer established by TASK72 and closes its one
blocking producer ambiguity. It does not realize decisions in Jenkins, schedule retries, add configurable
outcome remapping, or normalize the remaining Jenkins exception producers.

## TASK72 contract carried forward

The policy actions remain exactly `CONTINUE`, `RUN_FULL_SUITE`, and `FAIL_BUILD`. Warning is
observability, PR blocking is the later Jenkins realization of `FAIL_BUILD`, and retry is metadata only.
Retryability remains `NOT_RETRYABLE`, `TRANSIENT`, `AFTER_USER_FIX`, or `UNKNOWN`. The contexts remain
`PR_SELECTION`, `TEST_SELECTION`, `TEST_EXECUTION`, `MAPPING_BUILD`, `MAP_PUBLICATION`, and `MAP_LOOKUP`.
`BASE_OUT_OF_DATE` fails, selector `FULL_SUITE` runs the full suite, and `STALE_POINTER_UPDATE` continues.

## Lookup absence blocker

The mapping-runtime storage boundary previously returned exact-object `NOT_FOUND` for both absence of
the latest pointer and absence of the immutable object named by an existing pointer. Policy cannot safely
treat those cases alike, and message parsing or storage inspection in policy would violate the boundary.

## Latest lookup result model

`CoverageMapStore.LatestLookupResult` now has a bounded `LatestLookupStatus`: `FOUND`, `NO_POINTER`, or
`TARGET_MISSING`. A found result carries its pointer and map, ordinary absence carries neither, and a
dangling reference carries the pointer only. Pointer corruption and pointer/map checksum mismatch still
raise the existing `INVALID_RESPONSE` or `INTEGRITY_FAILURE`. Exact `getMap` retains `NOT_FOUND` for an
absent exact object. Layout, paths, credentials, RAW HTTP concurrency, and remote CAS scope are unchanged.

The existing `getLatestMap` compatibility method still presents the old map-level result, while internal
latest lookup orchestration uses the typed result. Therefore public Jenkins Pipeline behavior is unchanged.

## Policy package

The model lives in `smart-test-picker-common` package `com.sap.oss.smarttestpicker.policy`. It has no
Jenkins, Git, environment, storage implementation, or I/O dependency.

## Policy input

`PolicyInput` is an immutable record containing source, stable outcome code, context, bounded
`PolicyOperation`, optional diagnostic code, selective/full-suite safety facts, retryability hint,
user-action fact, and metadata. Outcome and diagnostic codes use a 64-character upper-case code grammar.
Metadata is limited to eight entries, uses a structural key allowlist, accepts scalar values only, and
limits their rendered length to 128 characters. Exceptions, stack traces, response bodies, credentials,
authorization data, and arbitrary payloads are not accepted.

## Policy actions

`PolicyAction` contains only `CONTINUE`, `RUN_FULL_SUITE`, and `FAIL_BUILD`. No severity model exists.

## Retryability

`Retryability` is metadata and never invokes execution: `NOT_RETRYABLE`, `TRANSIENT`, `AFTER_USER_FIX`,
or `UNKNOWN`.

## Contexts

The six TASK72 contexts are unchanged. `PolicyOperation` adds bounded detail for SCM resolution,
preflight, selection, inventory, preparation, fragments, join, publication, exact/latest lookup, and
execution without expanding the context taxonomy.

## Decision rules

Known safe domain outcomes continue. Selector `FULL_SUITE` is accepted as `RUN_FULL_SUITE`, not treated
as error recovery. Genuine `NO_POINTER` runs the full suite only when that fallback is explicitly safe.
Optional automatic SCM and unavailable PR-head inventory facts follow the same guarded fallback rule.

## Hard safety rules

`BASE_OUT_OF_DATE`, `TARGET_MISSING`, integrity and publication conflicts, invalid configuration,
credential/authentication/authorization failures, and normalized mapping incompleteness fail the build.
Caller fallback hints cannot weaken those rules. Per-test unmapped reasons remain map-domain evidence and
are not registered as central mapping failures.

## Context-sensitive rules

`REMOTE_UNAVAILABLE` and `TIMEOUT` in `MAP_LOOKUP` run the trusted full suite when possible and otherwise
fail. The same facts in publication or mapping contexts always fail. No retry is scheduled.

## Unknown behavior

An unregistered outcome in `PR_SELECTION`, `TEST_SELECTION`, or `MAP_LOOKUP` can only run the full suite
when `fullSuitePossible` is explicit. It otherwise fails. Unknown outcomes in `TEST_EXECUTION`,
`MAPPING_BUILD`, and `MAP_PUBLICATION` always fail and can never enable selection. Generic preflight or
explicit `ERROR` conservatively fails with `UNKNOWN` retryability.

## Precedence

Hard correctness, security, and configuration failures outrank revision eligibility failures; failures
outrank full-suite fallback, which outranks continue. This is policy precedence, not a second severity
taxonomy.

## Aggregation

`evaluate(Collection<PolicyInput>)` rejects null or empty input, evaluates one operation's fact set, and
selects the strongest decision. Equal-precedence representatives are chosen by stable source, outcome,
and operation ordering, so input order cannot affect the result.

## Reason codes

Machine-stable codes include `SAFE_DOMAIN_RESULT`, `SELECTOR_REQUIRES_FULL_SUITE`, `BASE_OUT_OF_DATE`,
`NO_COVERAGE_MAP`, `BROKEN_LATEST_POINTER`, `REMOTE_OPTIMIZATION_UNAVAILABLE`,
`STORAGE_CONFIGURATION_INVALID`, credential-specific storage codes, `MAPPING_INCOMPLETE`,
`UNKNOWN_OPTIMIZATION_FAILURE`, and `UNKNOWN_STRICT_FAILURE`. Human-safe reasons are limited to 256
characters and never incorporate producer messages or metadata.

## Tests

Common unit tests cover the required decision matrix, context sensitivity, hard-rule overrides, input
validation, precedence, and order independence. Mapping-runtime tests prove FILE and local fake-HTTP
parity for `NO_POINTER`, `FOUND`, `TARGET_MISSING`, exact `NOT_FOUND`, and existing checksum integrity
failure behavior. No Docker or Nexus is used.

## Jenkins neutrality

The common policy package imports none of `hudson.*`, `jenkins.*`, `org.jenkinsci.*`, `FilePath`,
`Launcher`, `Run`, or `TaskListener`, and imports no companion-plugin storage implementation.

## Remaining TASK74 gaps

Inventory failure typing, mapping failure typing at Jenkins producer boundaries, FILE/RAW public error
parity, fact adapters, observability, and concrete public Pipeline realization are TASK74 pending.

## Item-8 status

Item 8 remains **IN PROGRESS**. TASK73 is complete; TASK74 Jenkins integration is next.
