# TASK76 — item-8 central failure-policy closure audit

## Goal

This is the final independent closure audit of backlog item 8. It reconstructed TASK72's taxonomy,
TASK73's pure engine, TASK74's Jenkins integration and TASK75's public evidence, then inspected current
production source rather than accepting their PASS labels. Two unsafe unknown-to-known conversions were
found, captured, minimally corrected and regression tested. No new action, retry behavior, backend or
architecture was introduced.

## Baseline

Both repositories started clean. Main STP was `28a35a1`; the Jenkins plugin was `2fe4903`. TASK72–75
commits and their JSON summaries were present in the expected order. TASK75 evidence is represented
honestly: its seven named Docker scenarios are fresh TASK75 proof, SELECTED/NONE use the rerun automated
suite plus TASK74 Docker, and the remaining matrix is inherited TASK70/71/74 evidence plus rerun tests.
TASK76 used no Docker.

## Architecture reconstruction

Source confirms this chain:

```text
producer -> typed fact/domain result -> JenkinsPolicyAdapter -> PolicyInput
         -> CentralFailurePolicy -> PolicyDecision -> JenkinsPolicyDecisionHandler
         -> concrete Jenkins behavior
```

`CentralFailurePolicy` is the only action table. `JenkinsPolicyAdapter` is the only Jenkins fact
normalizer, and `JenkinsPolicyDecisionHandler` is the only policy action realizer. Searches for action
names and `AbortException` found no second Jenkins policy table. Selector `FULL_SUITE` and execution-plan
`RUN_ALL` are domain/execution semantics, not competing CI policy.

## Action ownership audit

The handler alone evaluates and realizes `CONTINUE`, `RUN_FULL_SUITE`, and `FAIL_BUILD`. The selector
continues to own `SELECTED`, `NONE`, and `FULL_SUITE`; the execution adapter continues to interpret
`RUN_ALL`. Producer-side parameter validation and Jenkins context/bootstrap failures may still fail a
step normally because no safe policy fact exists. They are outside item 8 and cannot create fallback.

## StpPrSelect callback failure audit

`Completed.onFailure` is the callback of the user-supplied Pipeline body and can receive any
`Throwable`: user code failure, Gradle/Maven failure, interruption, plugin failure, or JVM error.
`Completed.onSuccess` can fail while resolving HEAD, reading/parsing/validating inventory, detecting a
moved workspace, invoking selection, accessing Jenkins contexts, or cleaning temporary files.

Only explicit `InventoryOperationException` values prove a bounded inventory condition:

| Condition | Fact |
| --- | --- |
| missing/empty artifact | `INVENTORY_UNAVAILABLE` |
| malformed, blank or duplicate identities | `INVENTORY_INVALID` |
| schema/revision binding mismatch | `INVENTORY_REVISION_MISMATCH` |
| HEAD moved | `WORKSPACE_REVISION_CHANGED` |

Before TASK76, every other failure was relabelled `INVENTORY_UNAVAILABLE` with
`fullSuitePossible=true`. The pre-fix source is preserved in
`verification/task76/pre-fix-stp-pr-select-callback-defect.txt`. This was not safe: the body might have
failed before producing a trustworthy execution environment. The fix preserves typed inventory facts
but sends every untyped failure as `INVENTORY/UNKNOWN/PR_SELECTION` with
`fullSuitePossible=false`. Central policy therefore fails it. A focused test proves known absence still
runs the full suite and an unexpected callback failure remains UNKNOWN and fails.

The same audit found an analogous RAW_HTTP bridge issue: an unclassified reflected exception became
`REMOTE_UNAVAILABLE`. It now becomes bounded `StorageOutcome.UNKNOWN`; lookup supplies no fallback
proof for that fact. A focused regression proves `FAIL_BUILD`.

## Mapping publication bypass audit

The TASK75 fix remains present. Publication identity mismatch is `CONFIGURATION_INVALID`; workspace
revision mismatch is `REVISION_MISMATCH`; fragment and evidence set checks produce
`FRAGMENT_MISSING`/`FRAGMENT_INVALID` and `EVIDENCE_MISSING`/`EVIDENCE_INVALID`. Evidence validation,
join, and FILE subprocess failures cross the marker boundary as typed mapping/storage exceptions. RAW
publication uses typed storage exceptions. All reach the adapter, engine and handler. No known
publication-policy bypass remains.

TASK75 contains pre-fix Docker evidence, post-fix focused Docker evidence, an automated strict-outcome
regression, and a full 70-test Jenkins run after the fix. Current source matches that post-fix behavior.

## Storage parity audit

FILE uses `STP_RESULT_V1`; RAW_HTTP exposes the equivalent typed bridge status. Backend is only bounded
metadata. Both normalize `NO_POINTER`, `TARGET_MISSING`, and `INTEGRITY_FAILURE` identically. Latest-map
semantics remain distinct: no pointer is optimization absence and may run all; dangling target,
malformed response and integrity mismatch fail.

`MappingRuntime` reads only the first exact, line-prefixed stderr marker. Missing/unknown codes become
`MappingOutcome.UNKNOWN`; human stderr is neither parsed nor exposed as policy input. Known mapping and
storage enum codes survive. Runtime stderr cannot select an action.

## Selector and revision semantics

`BASE_OUT_OF_DATE` is normalized by the revision adapter and the hard engine rule always returns
`FAIL_BUILD`; hints cannot weaken it. `SELECTED -> CONTINUE`, `NONE -> CONTINUE`, and
`FULL_SUITE -> RUN_FULL_SUITE`. The handler adds `selectorStatus=FULL_SUITE` and `planMode=RUN_ALL`,
removing restrictive selection. SELECTED and NONE are not widened.

## fullSuitePossible audit

| Call site | Outcome family | Context | Value | Safety basis |
| --- | --- | --- | --- | --- |
| `StpPrSelectStep.Execution` | terminal optional SCM statuses | PR_SELECTION | true | resolution failed before filtering; trusted checkout and ordinary target remain |
| synthetic-merge branch | `PR_HEAD_INVENTORY_UNAVAILABLE` | PR_SELECTION | true | only source-head optimization inventory is unavailable; checkout is untouched |
| typed `Completed` failure | inventory facts | PR_SELECTION | true | detection follows a trusted checkout and no restrictive plan has been applied; hard invalid/revision rules still dominate |
| untyped `Completed` failure | `UNKNOWN` | PR_SELECTION | false | no execution-safety fact is proven |
| latest lookup success/typed storage failure | no pointer/transient/strict storage facts | MAP_LOOKUP | true except UNKNOWN/configuration | lookup occurs before selection and only optimization data failed; hard rules dominate |
| lookup configuration or unknown bridge failure | configuration/UNKNOWN | MAP_LOOKUP | false | safety is not asserted |
| selector adapter | `FULL_SUITE` | TEST_SELECTION | true | authoritative selector domain result explicitly removes filtering |

There are no unsafe true sites. `safeSelectivePossible=true` is constructed only for validated storage
`FOUND` and selector `SELECTED`/`NONE`; it is not a caller override. The engine's hard integrity,
security, configuration, revision, and mapping rules precede fallback hints.

## Unknown behavior

Unknown optimization plus explicit safe full-suite proof runs all; without proof it fails. Unknown in
a strict context fails. After the two TASK76 fixes, neither arbitrary callback failure nor an
unclassified RAW bridge exception silently becomes a safe known fact.

## Inventory and mapping typing

Public automatic inventory preserves unavailable, invalid, revision-mismatch, moved-workspace and
synthetic-head-unavailable distinctions. Required `stpHeadInventory` and mapping-preparation failures
that cannot establish a fallback continue to fail normally; they cannot weaken verification or choose
full suite. Mapping subprocess operations use the bounded vocabulary. Production emits configuration,
revision, inventory-required, fragment/evidence invalid, join-invalid, runtime-integrity and UNKNOWN
families; `FRAGMENT_INCOMPLETE`, `ACCOUNTING_INCOMPLETE`, `DUPLICATE_IDENTITY`, `DUPLICATE_SHARD`, and
`PUBLICATION_REVISION_MISMATCH` are engine-supported refinements, while current runtime join validation
coalesces several of them to `JOIN_INVALID`. That coalescing is not unsafe because every value has the
same strict action and no known failure becomes fallback.

## SCM behavior

Every terminal `ScmPrResolutionStatus` in automatic PR selection goes through the adapter and central
policy. Optional unresolved SCM with a trusted full target runs all. No fetch, unshallow, checkout
mutation, synthetic-merge reinterpretation, or source-head reconstruction exists.
`PR_HEAD_INVENTORY_UNAVAILABLE` remains a typed optimization limitation and centrally runs all when safe.

## Credential, security and storage behavior

Credential-required/not-found/type-unsupported, authentication failure, and authorization failure all
produce `FAIL_BUILD`, `AFTER_USER_FIX`, and `userActionRequired=true`. Configuration errors always fail.
Lookup `REMOTE_UNAVAILABLE`/`TIMEOUT` may run all with positive safety proof; publication and mapping
contexts fail with `TRANSIENT` metadata only. No scheduler exists. `STALE_POINTER_UPDATE` continues;
immutability and pointer conflicts fail without choosing a winner.

## Public API contract

Selection maps expose the seven bounded policy fields. Full-suite selection additionally exposes
`selectorStatus=FULL_SUITE` and `planMode=RUN_ALL`. Lookup always exposes `coverageMap` (nullable) plus
the policy envelope, replacing the old ambiguous bare-null contract. TASK74 documents these intentional
compatibility breaks. Publication/preparation/mapping/head-inventory preserve their established scalar
success contracts and visible strict failure contracts.

## Observability and secret safety

The canonical log is a single bounded line containing source, outcome, context, action, reasonCode,
retryability, fallbackOccurred and userActionRequired. Policy data/logs contain no raw Throwable,
response body, Authorization header, credential, URL userinfo, or producer message. Password clones are
scoped to the RAW bridge and cleared by the credential holder. Policy metadata admits only allowlisted
bounded scalar fields; current Jenkins storage metadata contains only backend.

## Policy purity, precedence and aggregation

The common policy package has no Jenkins, Git, storage, environment or I/O dependency and no mutable
external state. Evaluation is deterministic. Tests establish `FAIL_BUILD > RUN_FULL_SUITE > CONTINUE`,
hard-rule precedence, order-independent deterministic representative selection, and aggregation only
over the collection supplied for one operation. There is no global poisoning.

## Exception and bypass audit

| Public path | Covered classification | Deliberate exclusion |
| --- | --- | --- |
| `stpPrSelect` | SCM, inventory, selector, revision, bounded UNKNOWN | missing Jenkins contexts/bootstrap before a fact exists |
| `stpExplicitPrSelect` | revision/selector results and bounded returned ERROR | launcher/artifact/Jenkins infrastructure failure fails normally; never fallback |
| `stpLookupCoverageMap` | storage/configuration/credentials and RAW UNKNOWN | Jenkins context or local file-copy infrastructure failure fails normally |
| `stpPublishCoverageMap` | known mapping checks, runtime marker, storage/security | unstash/Jenkins infrastructure failure fails normally |
| `stpPrepareCoverageMapping` | runtime mapping codes | invocation/bootstrap/body failure fails normally and cannot fallback |
| `stpCoverageMap` | runtime fragment/evidence validation codes | invocation/body/stash infrastructure failure fails normally |
| `stpHeadInventory` | automatic consumer validates typed artifact facts | standalone invocation/body infrastructure failure fails normally |

The boundary is intentional: central policy covers typed SCM/PR optimization resolution, revision
preflight, selector domain results, inventory correctness/availability when consumed for automatic
selection, mapping correctness/completeness at publication/runtime boundaries, storage
lookup/publication, and storage credentials/security. Generic Jenkins context, CPS, launcher, body,
stash, filesystem or plugin-programming failure remains an ordinary Jenkins failure when no safe fact
can be established. Such failure must never be converted to fallback; the TASK76 fixes enforce that.
No known item-8 fact that can alter verification scope bypasses policy.

## Supported topology

Closure applies to one Jenkins controller; the supported public STP steps; FILE and RAW_HTTP storage;
current GitHub Branch Source PR resolver semantics; and current Gradle/Maven mapping and selector
adapters. It does not claim multi-controller CAS, other SCM providers/backends, or organization rollout.

## Correctness blockers

`correctnessBlockers = []`. The callback and RAW bridge defects are closed by Jenkins commit `2c56af0`.

## Deferred nonblocking work

Retry scheduling, policy UI, diagnostic verbosity, multi-controller storage CAS, additional storage
backends and SCM providers, rollout/adoption, telemetry/dashboarding, and finer strict mapping diagnostic
codes remain nonblocking future work.

## Tests

- Common: 279 tests, 7 skipped, PASS.
- Focused Jenkins policy regression: 9 tests, PASS.
- Full Jenkins: 72 tests, PASS (70 baseline plus two TASK76 regressions).
- Mapping runtime: 32 tests, 3 opt-in Nexus tests skipped, PASS.
- Packaging/integrity: PASS; common jar bundled and deterministic adapter verification PASS.
- Docker: not used; static call-path proof and focused tests resolved both defects.

## Closure decision

TASK76 passes. Item 8 Central failure policy is DONE for the supported topology. Items 1–7 remain DONE.
