# TASK72 — item 8 failure-policy taxonomy and decision contract

## Goal

TASK72 starts canonical backlog item **8 Central failure policy** and defines its authoritative,
build-tool-neutral decision contract. It does not implement a policy engine, change Pipeline behavior,
add producer fallbacks, schedule retries, or reopen items 1–7.

The governing question is: given a typed fact or domain result produced by an existing STP subsystem,
plus bounded execution context, what policy category and CI action should the future central layer choose?

## Existing failure producers

This inventory is based on current main/common/runtime code and the companion Jenkins plugin, not only
on prior task documentation.

| Subsystem | Type/class and public/wire values | Producer | Meaning and current caller behavior | Semantic action already implied? |
| --- | --- | --- | --- | --- |
| SCM/PR resolution | `ScmPrResolutionResult` / `ScmPrResolutionStatus`: `RESOLVED`, `NOT_PR_BUILD`, `PR_METADATA_MISSING`, `TARGET_BRANCH_UNKNOWN`, `SOURCE_BRANCH_UNKNOWN`, `PR_HEAD_UNRESOLVED`, `INTEGRATION_REVISION_UNRESOLVED`, `PR_BASE_UNRESOLVED`, `WORKSPACE_REVISION_UNRESOLVED`, `AMBIGUOUS_SCM_CONTEXT`, `UNSUPPORTED_SCM_CONTEXT`, `REQUIRED_COMMIT_MISSING`, `SHALLOW_HISTORY`, `SYNTHETIC_MERGE_UNCLASSIFIED` | `ScmPrContextResolver` | Total typed resolution result. `stpPrSelect` currently returns `RESOLUTION_FAILED` without failing for every non-`RESOLVED` value. | `RESOLVED` only; other values are facts. |
| checkout provenance | `CheckoutMode`: `BRANCH`, `SOURCE_HEAD`, `SYNTHETIC_MERGE`, `UNKNOWN` | provider adapter/resolver | Proven workspace meaning, used to constrain inventory generation. | Domain metadata, not an action. |
| automatic PR selection | `AutomaticPrSelectionResult.Status`: `RESOLUTION_FAILED`, `PR_HEAD_INVENTORY_UNAVAILABLE`, `SELECTION_COMPLETE` | `StpPrSelectStep` | Pipeline-safe envelope. Synthetic merge returns inventory unavailable; other inventory/body failures currently throw. | `SELECTION_COMPLETE` is a domain result; the other two are failure facts/envelopes. |
| explicit revision preflight | `RevisionPreflightResult` / `RevisionPreflightStatus`: `ELIGIBLE`, `BASE_OUT_OF_DATE`, `FULL_SUITE`, `ERROR` | `RevisionPreflight` | Checks frozen IDs, map validity, ancestry, distance. `FULL_SUITE` is converted into selector output; `BASE_OUT_OF_DATE` and `ERROR` remain outer results. | Yes for `ELIGIBLE`, `BASE_OUT_OF_DATE`, and `FULL_SUITE`; `ERROR` is too coarse. |
| explicit selection | `ExplicitPrSelectionResult` / `ExplicitPrSelectionStatus`: `SELECTION_RESULT`, `BASE_OUT_OF_DATE`, `ERROR` | `ExplicitPrSelectorFlow` | Keeps selector output separate from eligibility/error. Jenkins returns these values when the CLI succeeds; launcher/artifact failures throw. | First two are domain results; `ERROR` is a failure fact. |
| schema-v2 selector | `SelectionOutput.status`: `SELECTED`, `NONE`, `FULL_SUITE` (semantic `RUN_ALL`) | `SchemaV2TestSelector` and adapters | `SELECTED` restricts safely, `NONE` executes no tests after all mandatory unions, `FULL_SUITE` removes restrictive filtering. | Yes: all three are domain results. |
| selector analysis | `SelectionAnalysisResult`: ready or `runAll(reason)` | `SchemaV2SelectionAnalyzer` | Invalid/incomplete map, unsafe diff, inventory mismatch/absence, structural trigger and other uncertainty become safe run-all. | Domain result. |
| inventory | `HeadTestInventory` or revision-bound JSON; failures are currently `IllegalArgumentException`, `IllegalStateException`, `IOException` | inventory generators and `stpHeadInventory`/prepare/automatic steps | Missing/empty/invalid output, duplicate/null identity, discovery failure, revision mismatch, moved HEAD. Automatic synthetic merge uniquely becomes `PR_HEAD_INVENTORY_UNAVAILABLE`; most others throw. | Valid inventory is data; failures are structured only by bounded reason text or wrapper status. |
| mapping preparation | manifest path/result string; failures are exceptions | `StpPrepareCoverageMappingStep` | Invalid configuration, inability to freeze revision, configured/actual mismatch, moved HEAD, missing inventory, split/runtime failure. | No; facts are largely untyped. |
| mapping execution/fragment runtime | `CoverageFragment`; `CollectionStatus`: `COLLECTED_WITH_COVERAGE`, `COLLECTED_EMPTY`, `COLLECTION_FAILED`; `UnmappedReason`: `FAILED`, `SKIPPED`, `TIMEOUT`, `COLLECTION_FAILED` | `stpCoverageMap`, runtime projector, adapters | A valid fragment is a domain artifact. Missing output/evidence, binding mismatch, incomplete collection and runtime integrity failures currently throw. A per-test unmapped reason preserves test evidence and is not by itself a job action. | No central action. |
| execution accounting/join | `Completeness` fields: missing/unexpected tests, missing/duplicate shards, duplicate tests; plus structured execution evidence | `MappingTool.join`, `StpPublishCoverageMapStep` | Missing/unexpected fragment or evidence, revision/shard/target/tool mismatch, missing/unexpected/duplicate identity, collector/evidence disagreement and incomplete collection fail the public publication step. | Completeness is a domain result; rejection is a failure fact. |
| publication/storage | `CoverageMapStore.StorageStatus`: `FOUND`, `STORED`, `ALREADY_EXISTS_IDENTICAL`, `POINTER_UPDATED`, `POINTER_ALREADY_CURRENT`, `NOT_FOUND`, `INVALID_RESPONSE`, `INTEGRITY_FAILURE`, `IMMUTABILITY_CONFLICT`, `POINTER_CONFLICT`, `STALE_POINTER_UPDATE`, `REMOTE_UNAVAILABLE`, `TIMEOUT`, `CONFIGURATION_ERROR`, credential outcomes below | FILE/RAW stores and `MappingTool` | Success/value statuses return; failures throw `StorageException`. Public Jenkins RAW operations preserve applicable names in `StorageOutcome`; FILE subprocess failures become generic `IOException`. | Success/idempotency/stale protection are domain results; errors are facts. |
| credentials/authentication | `CREDENTIAL_REQUIRED`, `CREDENTIAL_NOT_FOUND`, `CREDENTIAL_TYPE_UNSUPPORTED`, `AUTHENTICATION_FAILED`, `AUTHORIZATION_FAILED` | storage configuration, Jenkins credential resolver, RAW HTTP | Typed storage/security facts. Current public steps fail when thrown. | No action implied. |
| lookup | `MapLookupResult`: `FOUND` or `NOT_FOUND`, or typed storage exception | `CoverageMapStore`, `CoverageMapStorageResolver`, `stpLookupCoverageMap` | No pointer returns null/no output. Pointer-target absence also becomes `NOT_FOUND` but throws in the orchestrated latest path. Invalid/corrupt results throw. | `FOUND` and genuine no-pointer are domain facts; broken target is an integrity fact currently collapsed at the store boundary. |

## Fact vs domain result vs policy action

`DOMAIN_RESULT` means the subsystem has already answered its own semantic question safely. It may be
accepted by policy but must not be relabelled as a failure. `FAILURE_FACT` describes what happened but
does not choose CI behavior. `POLICY_ACTION` is owned only by item 8. `AMBIGUOUS` means current typing
combines facts that need different decisions.

| Classification | Current values |
| --- | --- |
| `DOMAIN_RESULT` | SCM `RESOLVED`; checkout modes; preflight `ELIGIBLE`, `FULL_SUITE`, `BASE_OUT_OF_DATE`; explicit `SELECTION_RESULT`, `BASE_OUT_OF_DATE`; selector `SELECTED`, `NONE`, `FULL_SUITE`; analysis ready/run-all; valid inventory/fragment/map; collection statuses and unmapped reasons when embedded in complete validated output; storage `FOUND`, `STORED`, `ALREADY_EXISTS_IDENTICAL`, `POINTER_UPDATED`, `POINTER_ALREADY_CURRENT`, `STALE_POINTER_UPDATE`; genuine no-pointer `NOT_FOUND`; automatic `SELECTION_COMPLETE`. |
| `FAILURE_FACT` | All non-resolved SCM statuses; automatic `RESOLUTION_FAILED`, `PR_HEAD_INVENTORY_UNAVAILABLE`; explicit/preflight `ERROR`; inventory/configuration/runtime exceptions; mapping incompleteness/integrity facts; storage `INVALID_RESPONSE`, `INTEGRITY_FAILURE`, `IMMUTABILITY_CONFLICT`, `POINTER_CONFLICT`, `REMOTE_UNAVAILABLE`, `TIMEOUT`, `CONFIGURATION_ERROR`; all credential/authentication outcomes. |
| `POLICY_ACTION` | None exists in production. TASK72 defines `CONTINUE`, `RUN_FULL_SUITE`, and `FAIL_BUILD`. |
| `AMBIGUOUS` | storage `NOT_FOUND` (no pointer versus dangling pointer target); preflight/explicit `ERROR` (invalid configuration, missing artifact, revision/ancestry/Git/internal causes); untyped mapping/inventory exceptions; public `RESOLUTION_FAILED` without inspecting its nested SCM status. |

## Item-8 responsibility boundary

The authoritative flow is:

```text
producer: typed fact or domain result
central policy: fact/result + execution context + bounded configuration -> PolicyDecision
Jenkins integration: PolicyDecision -> concrete Pipeline behavior
```

Producers continue to resolve Git, discover inventory, select tests, collect/join maps and access
storage. They do not choose CI fallback. The policy layer performs none of those operations, does not
read map contents, discover credentials, fetch SCM, rerun tests or schedule retries. Jenkins integration
does not reinterpret facts; it realizes the decision.

One engine can cover SCM/PR, revision preflight, selector, inventory, mapping, storage and credentials.
Subsystem-specific fact adapters may normalize existing types into the common input, but they are not
separate policy engines.

## Policy input

Conceptual `PolicyInput` is Jenkins- and build-tool-neutral:

```text
source                 enum: SCM, REVISION, SELECTOR, INVENTORY, MAPPING, STORAGE, CREDENTIALS
outcome                stable typed name
context                PolicyContext
operation              bounded operation code
diagnosticCode         bounded producer code, optional
safeSelectivePossible  boolean
fullSuitePossible      boolean (trusted ordinary target and environment remain available)
retryabilityHint       NOT_RETRYABLE | TRANSIENT | AFTER_USER_FIX | UNKNOWN
userActionRequired     boolean
metadata               allowlisted bounded scalars only (no secrets)
```

An arbitrary `Throwable`, response body, stack trace, credential, authorization header, URL userinfo,
or unbounded message is not policy input. An adapter may translate a known exception into a typed fact;
unknown exceptions use the conservative rule below.

## Policy actions

The minimal authoritative set is:

* `CONTINUE`: accept the safe domain result or non-fatal safety outcome.
* `RUN_FULL_SUITE`: prohibit selective filtering and run the ordinary trusted complete test target.
* `FAIL_BUILD`: stop the current CI operation visibly.

`WARN` is not a separate control action: it is observability attached to `CONTINUE` or
`RUN_FULL_SUITE`. `BLOCK_PR` is not separate: Jenkins blocks a required PR check by realizing
`FAIL_BUILD`. Keeping it would duplicate semantic and execution concepts and behave identically in the
current integration.

Conceptual `PolicyDecision` is minimal:

```text
action                 CONTINUE | RUN_FULL_SUITE | FAIL_BUILD
reasonCode             stable bounded code
safeReason             short operator-safe text
source                 copied source
originalOutcome        copied typed name
retryability           NOT_RETRYABLE | TRANSIENT | AFTER_USER_FIX | UNKNOWN
userActionRequired     boolean
fallbackOccurred       boolean
```

Severity is omitted. It would not change precedence or behavior beyond the action and retryability;
adding `INFO/WARNING/ERROR/FATAL` would create a second, potentially inconsistent ranking.

## Retryability

`RETRY` is **not** an action. TASK72 and TASK73 do not own scheduling. Retryability is metadata:

* `NOT_RETRYABLE`: repetition without changed inputs should not help.
* `TRANSIENT`: a later attempt could succeed (`REMOTE_UNAVAILABLE`, `TIMEOUT`).
* `AFTER_USER_FIX`: configuration, credentials, authorization or required SCM state must change.
* `UNKNOWN`: producer typing is insufficient.

Without a scheduler, the final action is still selected now: transient optimization lookup can run the
full suite; transient publication fails the mapping build so loss is visible.

## Execution contexts

Six contexts are sufficient: `PR_SELECTION`, `TEST_SELECTION`, `TEST_EXECUTION`,
`MAPPING_BUILD`, `MAP_PUBLICATION`, and `MAP_LOOKUP`. The operation field distinguishes preparation,
inventory, fragment, join and exact/latest variants without multiplying contexts. In particular,
lookup failure before PR selection can fall back when the complete target remains trustworthy, while
publication failure after a mapping build fails that mapping build.

## Safety principles

The primary invariant is: **never reduce verification confidence because an STP optimization is
uncertain or failed**. Therefore:

* selective execution requires positively validated revision identity, complete/integrity-checked map,
  authoritative head inventory and safe selector result;
* when only optimization data is unavailable and trusted full execution remains possible, choose
  `RUN_FULL_SUITE`;
* never turn an empty, corrupt, incomplete, wrong-revision or unknown map into an empty selection;
* never hide configuration/security/integrity/publication contract failures behind an unrelated
  successful mapping job;
* `BASE_OUT_OF_DATE` remains a strict eligibility block: choose `FAIL_BUILD`, requiring PR update/rebase;
* if full execution itself, revision identity, execution accounting, or environment trust is compromised,
  choose `FAIL_BUILD`.

## Fail-open vs fail-closed

Fail open to `RUN_FULL_SUITE` only when the failed capability is purely an optimization input and the
ordinary complete test target, checkout and environment remain trustworthy. Examples are no published
map, transient lookup unavailability, unsupported automatic PR optimization and unavailable head
inventory.

Fail closed to `FAIL_BUILD` for stale PR eligibility, invalid required configuration, credentials or
authorization, integrity/protocol violations, revision ambiguity/mismatch in a required operation,
mapping incompleteness, publication conflicts and unknown failures that may affect identity or execution
correctness. A mapping/publication failure does not invalidate an older independently validated map for
a later PR lookup; context, not global poisoning, determines the decision.

## Outcome taxonomy

Every current typed failure maps to one category:

* `OPTIMIZATION_UNAVAILABLE`: automatic resolution/inventory/map absence where full verification remains safe.
* `INPUT_INVALID`: malformed or missing invocation/artifact input not specifically configuration or revision.
* `CONFIGURATION_INVALID`: invalid STP/build/storage configuration.
* `AUTHENTICATION_OR_AUTHORIZATION`: credential discovery/type/authentication/authorization failures.
* `INTEGRITY_VIOLATION`: malformed/corrupt response, immutable conflict, evidence/map binding mismatch.
* `REVISION_INCOMPATIBLE`: stale PR, unavailable/mismatched/frozen revision, shallow/absent history.
* `SCM_CONTEXT_INVALID`: ambiguous, unsupported or incomplete typed PR/provider context.
* `MAPPING_INCOMPLETE`: missing/duplicate/unexpected shard/test or incomplete collection/accounting.
* `REMOTE_TRANSIENT`: timeout or remote service/transport unavailable.
* `INTERNAL_ERROR`: unexpected implementation failure after bounded adapters cannot classify it.

`POINTER_CONFLICT` is an integrity/publication-consistency violation. `STALE_POINTER_UPDATE` is not a
failure category: it is a successful safety result.

## SCM/PR outcomes

`RESOLVED` continues. `NOT_PR_BUILD` and `UNSUPPORTED_SCM_CONTEXT` run the full suite in optional
automatic PR-selection context. Missing branch metadata, unresolved commits, ambiguous/synthetic-merge
classification, absent commits and shallow history also run full suite only when the trusted current
checkout can execute the complete target; otherwise they fail. A required explicit PR mode fails for all
of these. `SOURCE_BRANCH_UNKNOWN` is non-fatal because source branch is optional in the current resolver;
if emitted as a terminal resolution status, it follows the same optional-auto fallback rule.

## Revision/preflight outcomes

`ELIGIBLE` continues. Preflight `FULL_SUITE` passes through as safe selector-domain run-all.
`BASE_OUT_OF_DATE` fails the PR check and requires update/rebase; it is never converted to full suite.
`ERROR` fails by default because its current causes include missing/invalid frozen identity, invalid map,
Git failure and bad configuration. Its loss of cause is a policy-input gap.

## Selector outcomes

`SELECTED`, `NONE` and `FULL_SUITE` are authoritative domain results. Policy accepts `SELECTED`/`NONE`
with `CONTINUE` only after their declared safety prerequisites remain true. It accepts `FULL_SUITE` as
`RUN_FULL_SUITE`, preserving the existing safe decision rather than treating it as a new failure or
double-wrapping it.

## Inventory outcomes

`PR_HEAD_INVENTORY_UNAVAILABLE`, discovery unavailable and absent inventory run full suite in automatic
selection when the trusted complete target remains executable. Invalid/duplicate inventory, revision
mismatch, moved HEAD, or use of synthetic-merge inventory as source-head inventory fail closed when the
artifact is required. Current exception-only distinctions need producer normalization.

## Mapping outcomes

Preparation revision/configuration errors fail `MAPPING_BUILD`. Missing/extra fragments or evidence,
missing/unexpected/duplicate test identity or shard, collector incompleteness, evidence mismatch,
collection/runtime integrity failure, invalid joined map and publication revision mismatch all fail the
mapping/publication operation. No incomplete map is published. A PR flow looking up an older valid map is
independent and may continue/select or full-suite according to that lookup.

Per-test `FAILED`, `SKIPPED`, `TIMEOUT` and `COLLECTION_FAILED` are evidence, not automatic mapping-job
failure, when represented according to schema and the complete-map contract; selector unions preserve
their safety semantics. They become `MAPPING_INCOMPLETE` only when accounting or completeness fails.

## Storage outcomes and default policy matrix

“Full suite safe?” is contextual; `yes` below means in `MAP_LOOKUP`/PR optimization with a trustworthy
ordinary target. Publication decisions remain strict.

| Source | Typed fact/domain result | Category | Retryability | Full suite safe? | Default decision | Reason |
| --- | --- | --- | --- | --- | --- | --- |
| storage | `FOUND` | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | validated bytes are available |
| storage | `STORED` | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | immutable object stored |
| storage | `ALREADY_EXISTS_IDENTICAL` | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | idempotent publication success |
| storage | `POINTER_UPDATED` | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | latest advanced safely |
| storage | `POINTER_ALREADY_CURRENT` | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | idempotent pointer success |
| storage | genuine no-pointer `NOT_FOUND` | `OPTIMIZATION_UNAVAILABLE` | `NOT_RETRYABLE` | yes | `RUN_FULL_SUITE` | no map has yet been published |
| storage | dangling-target `NOT_FOUND` | `INTEGRITY_VIOLATION` | `UNKNOWN` | yes | `FAIL_BUILD` | published pointer contract is broken; current type gap must be closed |
| storage | `INVALID_RESPONSE` | `INTEGRITY_VIOLATION` | `UNKNOWN` | yes | `FAIL_BUILD` | protocol/data cannot be trusted |
| storage | `INTEGRITY_FAILURE` | `INTEGRITY_VIOLATION` | `NOT_RETRYABLE` | yes | `FAIL_BUILD` | checksum/identity/content binding failed |
| storage | `IMMUTABILITY_CONFLICT` | `INTEGRITY_VIOLATION` | `NOT_RETRYABLE` | n/a | `FAIL_BUILD` | same revision has conflicting bytes |
| storage | `POINTER_CONFLICT` | `INTEGRITY_VIOLATION` | `AFTER_USER_FIX` | n/a | `FAIL_BUILD` | revisions diverge; latest cannot be chosen safely |
| storage | `STALE_POINTER_UPDATE` | domain safety result | `NOT_RETRYABLE` | n/a | `CONTINUE` + warning | newer valid latest already won; refusing regression is success |
| storage | `REMOTE_UNAVAILABLE` | `REMOTE_TRANSIENT` | `TRANSIENT` | yes | lookup: `RUN_FULL_SUITE`; publication: `FAIL_BUILD` | fallback preserves PR verification; mapping loss remains visible |
| storage | `TIMEOUT` | `REMOTE_TRANSIENT` | `TRANSIENT` | yes | lookup: `RUN_FULL_SUITE`; publication: `FAIL_BUILD` | same as remote unavailable; no retry scheduling |
| storage | `CONFIGURATION_ERROR` | `CONFIGURATION_INVALID` | `AFTER_USER_FIX` | yes | `FAIL_BUILD` | fallback would hide broken configured production storage |
| credentials | `CREDENTIAL_REQUIRED` | `AUTHENTICATION_OR_AUTHORIZATION` | `AFTER_USER_FIX` | yes | `FAIL_BUILD` | required secure configuration absent |
| credentials | `CREDENTIAL_NOT_FOUND` | `AUTHENTICATION_OR_AUTHORIZATION` | `AFTER_USER_FIX` | yes | `FAIL_BUILD` | configured identity is unavailable in Item scope |
| credentials | `CREDENTIAL_TYPE_UNSUPPORTED` | `AUTHENTICATION_OR_AUTHORIZATION` | `AFTER_USER_FIX` | yes | `FAIL_BUILD` | configured secret type cannot be used safely |
| credentials | `AUTHENTICATION_FAILED` | `AUTHENTICATION_OR_AUTHORIZATION` | `AFTER_USER_FIX` | yes | `FAIL_BUILD` | remote rejected identity |
| credentials | `AUTHORIZATION_FAILED` | `AUTHENTICATION_OR_AUTHORIZATION` | `AFTER_USER_FIX` | yes | `FAIL_BUILD` | identity lacks required access |

## Complete non-storage default policy matrix

| Source | Typed fact/domain result | Category | Retryability | Full suite safe? | Default decision | Reason |
| --- | --- | --- | --- | --- | --- | --- |
| SCM | `RESOLVED` | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | authoritative context exists |
| SCM | `NOT_PR_BUILD`, `UNSUPPORTED_SCM_CONTEXT` | `OPTIMIZATION_UNAVAILABLE` / `SCM_CONTEXT_INVALID` | `AFTER_USER_FIX` | yes in auto mode | `RUN_FULL_SUITE` in auto; `FAIL_BUILD` if explicit PR required | optional optimization cannot resolve |
| SCM | `PR_METADATA_MISSING`, `TARGET_BRANCH_UNKNOWN`, `SOURCE_BRANCH_UNKNOWN` | `SCM_CONTEXT_INVALID` | `AFTER_USER_FIX` | contextual | auto + trusted target: `RUN_FULL_SUITE`; else `FAIL_BUILD` | provider metadata incomplete |
| SCM | `PR_HEAD_UNRESOLVED`, `INTEGRATION_REVISION_UNRESOLVED`, `PR_BASE_UNRESOLVED`, `WORKSPACE_REVISION_UNRESOLVED` | `REVISION_INCOMPATIBLE` | `AFTER_USER_FIX` | contextual | auto + trusted target: `RUN_FULL_SUITE`; else `FAIL_BUILD` | selective identity is unavailable |
| SCM | `AMBIGUOUS_SCM_CONTEXT`, `SYNTHETIC_MERGE_UNCLASSIFIED` | `SCM_CONTEXT_INVALID` | `AFTER_USER_FIX` | contextual | auto + trusted target: `RUN_FULL_SUITE`; else `FAIL_BUILD` | checkout meaning is not provable |
| SCM | `REQUIRED_COMMIT_MISSING`, `SHALLOW_HISTORY` | `REVISION_INCOMPATIBLE` | `AFTER_USER_FIX` | contextual | auto + trusted target: `RUN_FULL_SUITE`; else `FAIL_BUILD` | selective history is insufficient |
| automatic | `PR_HEAD_INVENTORY_UNAVAILABLE` | `OPTIMIZATION_UNAVAILABLE` | `AFTER_USER_FIX` | yes | `RUN_FULL_SUITE` | safe source-head inventory unavailable |
| preflight | `ELIGIBLE` | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | eligibility proven |
| preflight | `FULL_SUITE` | domain result | `NOT_RETRYABLE` | yes | `RUN_FULL_SUITE` | incompatible/too-old map safely disables selection |
| preflight | `BASE_OUT_OF_DATE` | `REVISION_INCOMPATIBLE` domain eligibility result | `AFTER_USER_FIX` | yes | `FAIL_BUILD` | strict update/rebase requirement |
| preflight/explicit | `ERROR` | `INPUT_INVALID`/`REVISION_INCOMPATIBLE`/`INTERNAL_ERROR` (ambiguous) | `UNKNOWN` | contextual | `FAIL_BUILD` | current result cannot prove a safe fallback |
| selector | `SELECTED` | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | safe selection proven |
| selector | `NONE` | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | safe empty selection proven after unions |
| selector | `FULL_SUITE` | domain result | `NOT_RETRYABLE` | yes | `RUN_FULL_SUITE` | selector already chose safe run-all |
| inventory | unavailable/missing | `OPTIMIZATION_UNAVAILABLE` | `UNKNOWN` | yes in auto flow | `RUN_FULL_SUITE`; mapping preparation: `FAIL_BUILD` | optional selection versus required mapping input |
| inventory | invalid/duplicate/revision mismatch/moved HEAD | `INTEGRITY_VIOLATION` or `REVISION_INCOMPATIBLE` | `NOT_RETRYABLE` | contextual | `FAIL_BUILD` | artifact identity/trust failed |
| mapping | valid fragment/complete map | domain result | `NOT_RETRYABLE` | n/a | `CONTINUE` | artifact contract satisfied |
| mapping | missing/unexpected/duplicate shard or identity; incomplete collector | `MAPPING_INCOMPLETE` | `UNKNOWN` | irrelevant to mapping build | `FAIL_BUILD` | publishing would lose correctness |
| mapping | evidence/revision/runtime/join mismatch or invalid published map | `INTEGRITY_VIOLATION` | `NOT_RETRYABLE` | irrelevant | `FAIL_BUILD` | execution evidence cannot be trusted |
| mapping | per-test unmapped reason in otherwise complete valid map | domain result | fact-dependent | n/a | `CONTINUE` | selector owns conservative union semantics |
| any | unexpected internal exception | `INTERNAL_ERROR` | `UNKNOWN` | context-derived | optimization-only + trusted full target: `RUN_FULL_SUITE`; otherwise `FAIL_BUILD` | unknown never enables selection |

## Policy precedence

For facts from one operation, deterministic precedence is:

```text
FAIL_BUILD required by hard integrity/security/configuration/execution correctness
> FAIL_BUILD required by revision eligibility (including BASE_OUT_OF_DATE)
> RUN_FULL_SUITE because selective safety is unavailable
> CONTINUE with warning
> CONTINUE
```

This ranks decisions, not incidental exception arrival. A `FULL_SUITE` fact cannot mask a simultaneous
integrity violation. A stale-pointer safety success cannot mask an immutable conflict.

## Aggregation

Policy accepts one fact or the complete bounded set of facts for one operation. Adapters must retain all
facts discovered before returning; the engine evaluates each deterministic input then chooses the
strongest action by the precedence above. Ties use stable source/outcome ordering only for diagnostic
presentation; the action is identical. Facts from independent operations are not globally combined (a
failed new mapping build does not poison a later lookup of an older valid map).

## Unknown/default behavior

Unknown never permits `SELECTED` or `NONE`. In `PR_SELECTION`, `TEST_SELECTION`, or `MAP_LOOKUP`, an
unknown failure chooses `RUN_FULL_SUITE` only when input explicitly proves the checkout, ordinary test
target and execution environment trustworthy and full execution possible. Missing that proof, and in
`TEST_EXECUTION`, `MAPPING_BUILD`, or `MAP_PUBLICATION`, it chooses `FAIL_BUILD`. Retryability is
`UNKNOWN`; the safe reason contains only the bounded source and operation.

## Configuration philosophy

Hard safety rules are not overrideable: corrupt/incomplete/wrong-revision data is never selected from;
`BASE_OUT_OF_DATE` remains blocked; security/configuration failures are not silently hidden; unknown does
not enable selection; incomplete mapping is not published. Future operational preferences may control
diagnostic verbosity and whether a successful stale-pointer refusal emits a warning. They may not weaken
the action below the hard-safety minimum. Arbitrary per-outcome remapping is rejected.

## Scenario matrix

| # | Facts | Context | Central decision | Expected later Jenkins behavior | Safety rationale |
| --- | --- | --- | --- | --- | --- |
| 1 | genuine no-pointer `NOT_FOUND` | `MAP_LOOKUP` before PR selection | `RUN_FULL_SUITE` | remove/avoid filter and run normal target | absence of optimization data does not reduce coverage |
| 2 | corrupt map / `INTEGRITY_FAILURE` | `MAP_LOOKUP` | `FAIL_BUILD` | fail public step/check | corruption must remain observable, not resemble ordinary absence |
| 3 | `BASE_OUT_OF_DATE` | `PR_SELECTION` | `FAIL_BUILD` | fail required check with update/rebase reason | preserves agreed strict eligibility |
| 4 | `PR_HEAD_INVENTORY_UNAVAILABLE` | automatic `PR_SELECTION` | `RUN_FULL_SUITE` | run normal PR target | no selective proof, but full verification remains possible |
| 5 | `REMOTE_UNAVAILABLE` or `TIMEOUT` | `MAP_LOOKUP` | `RUN_FULL_SUITE`, retryability `TRANSIENT` | run normal target; report fallback | no scheduler; verification continues safely |
| 6 | `AUTHENTICATION_FAILED` | `MAP_LOOKUP` | `FAIL_BUILD` | fail visibly | silent fallback would hide broken production security config |
| 7 | missing shard / accounting incomplete | `MAP_PUBLICATION` | `FAIL_BUILD` | fail publication/mapping build | incomplete map cannot be trusted or published |
| 8 | `STALE_POINTER_UPDATE` | `MAP_PUBLICATION` | `CONTINUE` + warning | mapping build succeeds, logs newer winner | safety guard worked and latest did not regress |
| 9 | `IMMUTABILITY_CONFLICT` | `MAP_PUBLICATION` | `FAIL_BUILD` | fail mapping build | same revision cannot have two meanings |
| 10 | selector `FULL_SUITE` structural trigger | `TEST_SELECTION` | `RUN_FULL_SUITE` | execute unfiltered target | pass through selector's safe domain decision |
| 11 | `UNSUPPORTED_SCM_CONTEXT` | automatic `PR_SELECTION` | `RUN_FULL_SUITE` if trusted target exists | run unfiltered target and report unsupported optimization | provider limitation must not under-test |
| 12 | `SHALLOW_HISTORY` | automatic `PR_SELECTION` | `RUN_FULL_SUITE` if trusted target exists | run normal target; explicit-required mode fails | ancestry cannot prove selection |
| 13 | unexpected exception | any | optimization-only with explicit full-suite proof: `RUN_FULL_SUITE`; otherwise `FAIL_BUILD` | deterministic fallback or visible failure | unknown never enables selection |

## Current vs target Jenkins behavior

| Case | Current behavior | Target centralized behavior | TASK74 change |
| --- | --- | --- | --- |
| SCM resolution failure | `stpPrSelect` returns `RESOLUTION_FAILED`; caller decides/possibly does nothing | usually `RUN_FULL_SUITE` in optional auto mode, strict contexts fail | route envelope through policy and realize action |
| synthetic merge inventory unavailable | returns `PR_HEAD_INVENTORY_UNAVAILABLE` | `RUN_FULL_SUITE` | execute normal target instead of leaving action to caller |
| selector `FULL_SUITE` | adapters already remove/avoid restrictive filters | same behavior through accepted domain decision | centralize observability; do not alter selector semantics |
| `BASE_OUT_OF_DATE` | returned outer result; build outcome depends on caller | `FAIL_BUILD` | fail public required check deterministically |
| preflight/explicit `ERROR` | returned if CLI succeeds; launcher failures throw | typed/normalized fact then `FAIL_BUILD` | normalize and route |
| no FILE/RAW map | lookup returns null/no output | `RUN_FULL_SUITE` in selection lookup | route genuine absence to policy |
| corrupt/dangling map | throws/fails step (dangling target currently says `NOT_FOUND`) | `FAIL_BUILD` | retain failure, improve typed distinction |
| RAW timeout/unavailable lookup | throws/fails step | `RUN_FULL_SUITE` when trusted full target exists | catch typed fact at policy boundary, not producer fallback |
| storage credential/config failures | throws/fails step | `FAIL_BUILD` | centralize reason/action; behavior remains strict |
| incomplete mapping/join | throws/fails publication | `FAIL_BUILD` | centralize typed reason; do not allow publication |
| stale pointer update | runtime reports successful status, public step succeeds | `CONTINUE` + warning | expose decision/fact consistently |
| immutable/pointer conflict | throws/fails step | `FAIL_BUILD` | centralize deterministic diagnosis |

## Public observability and auditability

Every decision exposes source, original typed outcome, context/operation, action, stable reason code,
short safe reason, fallback occurrence, retryability and user-action requirement. Example:

```text
source=STORAGE outcome=REMOTE_UNAVAILABLE context=PR_SELECTION
decision=RUN_FULL_SUITE retryability=TRANSIENT fallback=true
reason=remote optimization data unavailable; full verification remains possible
```

Default output never includes secrets, `Authorization`, credential metadata, response bodies or stack
traces. A later diagnostic mode may retain bounded allowlisted technical cause.

## Policy-input gaps

No gap blocks the conceptual TASK73 engine; adapters can initially normalize what is available. The
minimum producer-contract fixes belong in TASK73 when they are common/model-local, or TASK74 when the
distinction exists only at the Jenkins wrapper.

```text
Gap: lookup absence provenance
Producer: CoverageMapStore.getLatestMap / MappingTool.lookupFromStore
Current result: NOT_FOUND
Missing distinction: no latest pointer versus pointer exists but immutable target is missing
Why policy needs it: ordinary absence runs full suite; dangling pointer is an integrity failure
Minimum future change: bounded lookup reason/substatus retaining NO_POINTER vs TARGET_MISSING
Classification: BLOCKS TASK73 (decision table cannot safely map bare NOT_FOUND)

Gap: explicit/preflight ERROR cause
Producer: RevisionPreflight and ExplicitPrSelectorFlow
Current result: ERROR + free-form reason
Missing distinction: invalid input/configuration, revision/Git failure, artifact integrity, internal error
Why policy needs it: retryability and safe fallback differ
Minimum future change: stable diagnostic code/fact adapter; preserve existing public outer status
Classification: CAN BE ADDRESSED DURING TASK73

Gap: inventory failure typing
Producer: inventory generators and Jenkins inventory wrappers
Current result: exceptions; only synthetic merge has PR_HEAD_INVENTORY_UNAVAILABLE
Missing distinction: unavailable discovery versus invalid/duplicate artifact versus revision mismatch
Why policy needs it: auto unavailable may full-suite; integrity/mismatch fails
Minimum future change: Jenkins boundary adapter emits bounded inventory fact
Classification: CAN BE ADDRESSED DURING TASK74

Gap: mapping failure typing
Producer: prepare, fragment validation, evidence validation and join/publication wrappers
Current result: IOException/IllegalStateException text plus Completeness only inside artifacts
Missing distinction: configuration, incomplete accounting, revision mismatch, runtime/internal failure
Why policy needs it: deterministic category, retryability and audit output
Minimum future change: translate known checks into bounded mapping fact codes without changing semantics
Classification: CAN BE ADDRESSED DURING TASK74

Gap: FILE versus RAW public error parity
Producer: MappingRuntime subprocess and CoverageMapStorageResolver
Current result: FILE failures often generic exit IOException; RAW preserves StorageOutcome
Missing distinction: equivalent typed storage fact at public boundary
Why policy needs it: backend-neutral decisions
Minimum future change: structured subprocess result or wrapper classification
Classification: CAN BE ADDRESSED DURING TASK74
```

The first gap is the sole `BLOCKS TASK73` gap: TASK73 must introduce/consume the minimum bounded
distinction before the default `NOT_FOUND` rule is executable. This is a small producer-contract fix and
does not justify a new backlog task.

## TASK73 implementation recommendation

Place the Jenkins-neutral policy input, action, decision, retryability, context and deterministic engine
in `smart-test-picker-common`, which already owns shared selector/revision result models and has no Jenkins
dependency. Keep adapters from Jenkins-only SCM/storage/mapping types in the companion plugin. If the
mapping-runtime copy cannot depend directly on common core, adapt its status at the plugin boundary rather
than duplicating an engine. This is the smallest single-engine boundary with dependency direction intact.

## Item-8 plan

The plan remains:

* TASK72 — taxonomy + decision contract (this task)
* TASK73 — central policy engine, including the minimum blocking lookup distinction
* TASK74 — Jenkins integration and nonblocking producer-boundary normalization
* TASK75 — E2E failure-policy validation
* TASK76 — final closure audit

Item 8 is **IN PROGRESS**. No production engine or Pipeline behavior exists yet.
