# TASK74 — Jenkins central failure-policy integration

## Goal

TASK74 connects the public Jenkins steps to the TASK73 `PolicyInput -> CentralFailurePolicy -> PolicyDecision`
contract. Jenkins normalizes facts and realizes actions; it does not own policy rules. Item 8 remains in
progress because TASK75 owns the broad cross-subsystem E2E matrix.

## Baseline

Both repositories were clean at `b6f1451` (main STP) and `ba4af6e` (Jenkins). The pre-change boundary
returned unresolved automatic selection to callers, left `BASE_OUT_OF_DATE` for callers, returned `null`
for a missing latest map, and threw producer exceptions for strict failures.

The required reconstruction before implementation was:

| Public step | Current producer fact / behavior | Normalized input | Central decision | Pipeline behavior |
| --- | --- | --- | --- | --- |
| `stpPrSelect` | non-`RESOLVED` SCM returned `RESOLUTION_FAILED` | `SCM/<typed status>/PR_SELECTION` | normally `RUN_FULL_SUITE` | explicit `FULL_SUITE` / `RUN_ALL` envelope |
| `stpPrSelect` | synthetic merge returned `PR_HEAD_INVENTORY_UNAVAILABLE` | `INVENTORY/PR_HEAD_INVENTORY_UNAVAILABLE/PR_SELECTION` | `RUN_FULL_SUITE` | explicit unrestricted plan; no fetch/mutation |
| `stpExplicitPrSelect` / automatic runner | outer `BASE_OUT_OF_DATE` or `ERROR` was returned | `REVISION/<status>/PR_SELECTION` | `FAIL_BUILD` | bounded `AbortException` |
| explicit/automatic selector | `SELECTED`, `NONE`, `FULL_SUITE` returned existing result/plan | `SELECTOR/<status>/TEST_SELECTION` | `CONTINUE`, `CONTINUE`, `RUN_FULL_SUITE` | preserve first two; explicit unrestricted plan for full suite |
| `stpLookupCoverageMap` | path or `null`; FILE subprocess flattened errors | `STORAGE/FOUND|NO_POINTER|TARGET_MISSING|.../MAP_LOOKUP` | continue, fallback, or fail | structured map plus policy envelope, or bounded failure |
| `stpPublishCoverageMap` | root string or producer exception | mapping/storage fact in `MAP_PUBLICATION` | success/stale continue; all strict/transient publication failures fail | preserve root on success; fail visibly otherwise |
| `stpPrepareCoverageMapping` | configuration/revision/inventory/split exceptions | bounded inventory/mapping fact in `MAPPING_BUILD` | `FAIL_BUILD` | visible bounded failure |
| `stpCoverageMap` | revision, fragment, evidence, or runtime exception | bounded mapping fact in `MAPPING_BUILD` | `FAIL_BUILD` | visible bounded failure |
| `stpHeadInventory` | plain revision/output/JSON exceptions | bounded inventory fact | `FAIL_BUILD` in required inventory operation | visible bounded failure |

## Common policy dependency

The Jenkins HPI declares version-aligned `smart-test-picker-common:0.2.0`, excluding unrelated transitive
dependencies. It directly instantiates the common `CentralFailurePolicy`; no reflected, JSON, or copied
policy engine exists. A narrow common correction registers stable invalid/revision inventory facts as strict
failures and general inventory unavailability as the same optional fallback family.

## Jenkins fact adapter

`JenkinsPolicyAdapter` is the sole conversion boundary for SCM, revision, selector, inventory, mapping,
storage, credentials, and unknown facts. `InventoryOutcome`, `MappingOutcome`, and their typed exceptions
capture bounded codes at detection boundaries. Throwable messages, response bodies, credentials, URLs,
and stack traces are never copied to `PolicyInput`.

## Jenkins realization boundary

`JenkinsPolicyDecisionHandler` is authoritative. `CONTINUE` preserves the value, `RUN_FULL_SUITE` adds
`selectorStatus=FULL_SUITE` and `planMode=RUN_ALL`, and `FAIL_BUILD` throws a bounded `AbortException`.
Every path logs source, outcome, context, action, reason code, retryability, fallback, and user-action state.

## SCM integration

All terminal `ScmPrResolutionStatus` values pass to common policy. Automatic PR selection sets
`fullSuitePossible=true` only before inventory/selector filtering, with a present trusted checkout and an
ordinary target still available. No fetch, unshallow, or provider reinterpretation was added.

## Revision/preflight integration

The existing 5e runner still owns ancestry, distance, and eligibility. `BASE_OUT_OF_DATE` and generic
`ERROR` are merely normalized; policy fails them. Jenkins performs no revision reasoning.

## Selector integration

The existing selector remains authoritative for `SELECTED`, `NONE`, and domain `FULL_SUITE`. Policy
observes those outcomes without rerunning or widening selection.

## Inventory normalization

The bounded vocabulary is `INVENTORY_UNAVAILABLE`, `INVENTORY_INVALID`,
`INVENTORY_REVISION_MISMATCH`, and `WORKSPACE_REVISION_CHANGED`, plus the synthetic-merge-specific fact.
Absence during optional automatic discovery can fall back; malformed, duplicate, or wrong-revision data
fails even when a normal test target exists.

## Mapping normalization

The Jenkins vocabulary covers configuration, revision, required inventory, fragment/evidence absence or
invalidity, accounting, duplicate identity/shard, invalid join, publication revision mismatch, and runtime
integrity. The mapping runtime emits only a bounded `STP_RESULT_V1:<CODE>` marker at its subprocess boundary.
Human-readable stderr is not parsed and is not forwarded by default.

## Storage normalization and FILE/RAW parity

Both backends preserve `NO_POINTER`, `TARGET_MISSING`, and `INTEGRITY_FAILURE`. RAW exposes a public bridge
exception/status method; FILE uses the same versioned subprocess marker. `NO_POINTER` is fallback,
`TARGET_MISSING` and integrity are strict failures.

## Lookup and publication behavior

Lookup `FOUND` continues, `NO_POINTER` and transient remote unavailability explicitly run all when the
unchanged checkout/target is trusted, while broken pointer, credentials, configuration, authorization, and
integrity fail. Publication never claims full-suite availability: transient remote errors fail with
`TRANSIENT`; stale-pointer protection continues and is logged.

## Public result contract

Map results add `policyAction`, `policyReasonCode`, `policySource`, `policyOutcome`,
`policyRetryability`, `policyFallback`, and `policyUserActionRequired`. Selection fallback also carries
`selectorStatus=FULL_SUITE` and `planMode=RUN_ALL`. Existing selection fields are retained. Lookup now
returns a structured result whose `coverageMap` field is the former path-or-null value, closing the unsafe
silent-null contract. `BASE_OUT_OF_DATE` is the intentional breaking change: it now fails the step.

## Observability and secret safety

Logs contain bounded enum/code values and common safe reasons only. Credentials and authorization values
remain scoped to the storage bridge, whose cloned password is cleared. No producer exception message is a
decision input or public failure diagnostic.

## Tests and focused Docker validation

Focused adapter/realization, common-policy, Jenkins public-flow, mapping-runtime protocol/parity, packaging,
and small Docker Pipeline results are recorded in `verification/task74/task74-summary.json`.

## Remaining TASK75 scope

TASK75 owns the exhaustive item-8 E2E failure matrix across all producers and contexts. It does not need to
rebuild the integration or typing boundaries established here.

## Item-8 status

`IN PROGRESS`; next is TASK75 — E2E failure-policy validation.
