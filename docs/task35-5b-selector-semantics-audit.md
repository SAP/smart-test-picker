# TASK 35 — 5b selector semantics audit

## Baseline and scope

- Branch: `research/asm-codex`
- Starting HEAD: `b8d7bfa5cd79f488fb48289990c6680679265b51`
- Starting working tree clean: **YES**
- Scope: contract reconstruction and implementation planning only. No production behavior changed.
- Backlog remains: 5a **DONE**; 5b **NOT STARTED**; 5c **DONE**; 5d **NOT STARTED — POC
  proven**; 5e **NOT STARTED / independent**.

This document uses **RUN_ALL** for the semantic instruction to execute the complete applicable test
inventory. The current public wire value is `FULL_SUITE`; they are equivalent, not distinct states.

## Evidence classification

### Authoritative after schema v2

- Schema v2 and `coverage.model.CoverageMap` are authoritative and exactly versioned.
- A map is immutable, bound to exactly one `CoverageMapRevision`, globally complete, and
  `PUBLISHED` before selection may consume it.
- Mapped and unmapped tests together are the reported inventory. A mapped `FAIL` remains valid
  coverage; `COLLECTED_EMPTY` is a valid mapped observation; `COLLECTION_FAILED` is unmapped.
- Setup coverage is a bounded relation from covered production classes to `affectedContainers`.
- The collector reports what ran. Orchestration supplies independent expectation, proves global
  completeness, and publishes. The selector decides what should run from safe published state.
- The checksum is integrity, not authenticity. Provenance fields and statistics are descriptive.

### Historical behavior retained only where compatible

- Missing, unreadable, invalid, unreachable, or stale map state fails open.
- New/unknown tests run conservatively.
- User-configured `fullSuiteTriggers` force the full suite.
- Historical outcomes do not remove coverage dependencies.

### Legacy or temporary behavior that is not schema-v2 authority

- `mapper.CoverageMap`, `CoverageMapReader`, `IndexedCoverageMap`, `metadata.commitId`, and
  `metadata.baseBranch` are legacy schema-v1 representations.
- Simple or hashed test keys and descriptor-less `Class#method` coverage are not schema-v2 identity.
- Selecting the nearest local/remote legacy map by `metadata.commitId` is cache policy, not proof that
  a schema-v2 map is admissible.
- Returning `NONE` while separately asking adapters to run unmapped tests is a legacy status
  ambiguity. Under the contract below, `NONE` means the effective mandatory execution set is empty.
- Maven reactor-local filtering of unmapped tests by changed module is a POC optimization and is
  unsafe as a shared selector policy.

## Current selector flow

The actual current path is:

1. An adapter supplies a map file directly, or the CLI's `CoverageMapResolver` picks a cached legacy
   local/remote file. `NEAREST` reads `metadata.commitId` directly and compares
   `git rev-list --count <commit>..HEAD`; invalid maps receive maximum distance.
2. `TestSelectionEngine` loads the file with legacy `CoverageMapReader`. It checks only existence,
   parseability, legacy metadata, nonblank commit ID, Git object existence, and maximum commit
   distance. It does not use `CoverageMapCodec`, schema version, checksum, lifecycle, completeness,
   setup scopes, schema-v2 unmapped entries, provenance, or statistics.
3. Trigger matching obtains all paths from `<commitId>..HEAD` plus staged/working-tree paths against
   `HEAD`. A configured glob match returns `FULL_SUITE`.
4. `GitChangeDetector` derives production classes from changed `.java` paths beneath recognized
   production source roots. It ignores test/resource/non-Java paths unless a trigger matches.
5. It parses Git Java hunk headings into descriptor-less `binary.Class#methodName`. It mutates local
   `.gitattributes` through `ensureJavaDiffDriver`; this historical side effect is not needed for the
   minimum safe class-level 5b contract.
6. `NewTestDetector` scans compiled classes by naming convention, compares simple class names with
   legacy map keys, and marks unknown classes plus modified known test classes as always-run. It
   ignores nested class files, handles `A`/`M`, treats rename target as `R` without adding it through
   that status rule, and ignores deletion. Scan/Git failures are logged and otherwise suppressed.
7. If no changed production class/method exists, the engine returns `NONE`, even if its separate
   `unmappedTests` collection is nonempty.
8. `TestSelector` reloads the same legacy map. For each changed class with method information it first
   matches descriptor-less methods; a class with zero method hits expands to class edges. Classes
   without method information use class edges. Unknown changed classes may yield an empty selected set.
9. The engine emits `SELECTED`, `NONE`, or `FULL_SUITE`, with selected legacy string keys, separately
   detected class-level `unmappedTests`, and changed classes. `SelectionResult` itself models only a
   selected set versus full-suite-required; an empty selected set implicitly means none.

No current selector path consumes the authoritative schema-v2 map. Feeding schema-v2 JSON to the
legacy reader does not establish safety and will normally appear to have missing metadata.

## Input admissibility

The selector may trust a map only after all **REQUIRED** conditions pass. A failure to obtain or prove
one of them produces **RUN_ALL**, except that retrieval/publication operations themselves remain with
their stated owner.

| Condition | Classification | Contract |
|---|---|---|
| map exists | REQUIRED; absence => RUN_ALL | Adapter/orchestration may locate it; core handles no usable input safely |
| bytes readable and schema-v2 codec decodes | REQUIRED; failure => RUN_ALL | Includes well-formed JSON, indexes, enums, checksum and structural construction |
| exact supported `schemaVersion` | REQUIRED; mismatch => RUN_ALL | No automatic v1 conversion |
| revision present | REQUIRED; absence => RUN_ALL | Already structural validation |
| `lifecycleState == PUBLISHED` | REQUIRED; otherwise RUN_ALL | Fragment/candidate is never selector input |
| completeness present | REQUIRED; absence => RUN_ALL | A candidate with null completeness remains unusable |
| no missing tests or shards | REQUIRED; violation => RUN_ALL | Global publication proof |
| no duplicate tests or shards | REQUIRED; violation => RUN_ALL | Global publication proof |
| no unexpected tests or completed shards | REQUIRED; violation => RUN_ALL | Identity mismatch is incomplete |
| validator has no other error | REQUIRED; violation => RUN_ALL | Includes mapped/unmapped overlap and malformed setup structure |
| revision resolves to a commit | REQUIRED; failure => RUN_ALL | Reachability is selection-time Git evidence |
| revision is an ancestor of selection head | REQUIRED; failure => RUN_ALL | Object existence alone is insufficient |
| distance `R..H` within configured policy | OPTIONAL policy; exceeded => RUN_ALL | Default threshold may be retained; distance must be computed only after ancestry |
| trusted publication channel | REQUIRED outside core | Checksum is not authenticity; 5d/storage supplies trusted bytes |
| recognized generator provenance | OPTIONAL | Versions are diagnostic; exact schema compatibility is authoritative |
| unmapped tests present | OPTIONAL and safe | Every such logical test is selected; does not make map globally incomplete |
| setup scopes present | OPTIONAL and safe | Apply bounded expansion below |
| zero mapped tests | OPTIONAL and safe | Valid if completeness/inventory is internally consistent; unmapped tests still run |
| an unmapped `COLLECTION_FAILED` entry | OPTIONAL and safe | Select that test like every other unmapped reason |

Statistics cannot substitute for completeness and must not control selection. A declared external
method-coverage artifact, if supported later, must verify its checksum and availability before any
method precision is used; its absence does not invalidate class-level edges, but guessed/partial
method use is forbidden. This audit does not implement that reserved path.

### Exact unsafe-map definition

A map is unsafe when its authoritative bytes cannot be decoded and validated exactly as schema v2;
it is not `PUBLISHED`; it lacks affirmative global completeness; any completeness discrepancy or
identity/structure/integrity error exists; its revision cannot safely anchor the comparison; or its
distribution is not trusted by the owning publication boundary. Unmapped tests, empty coverage, zero
mapped tests, diagnostic provenance, and statistics alone do not make a valid published map unsafe.

## Revision and diff contract

Define four separate values:

- **Map revision `R`:** exactly `CoverageMap.revision.value`; never configured base branch metadata.
- **Selection head `H`:** the current checked-out `HEAD` resolved once for the selection operation.
- **Comparison:** committed changes are `R..H`; staged and working-tree changes relative to `H` are
  added. `R` must resolve and be an ancestor of `H` before selection.
- **Staleness:** an optional configured maximum count of commits in `R..H`. Exceeding it is RUN_ALL;
  it never changes the diff base.

The core must not use a configured base branch, merge-base, current branch name, `HEAD~N`, or legacy
`metadata.baseBranch` to replace `R`. Arbitrary caller-supplied base/head remains independent 5e.
Every Git command needed for ancestry, distance, changed paths, and working state is part of one
fail-open analysis: command failure, nonzero exit, unparsable output, or a moving/inconsistent head
means RUN_ALL.

## Changed-code granularity

Current support is path-derived class identity plus heuristic descriptor-less method names. It is not
descriptor-aware and cannot distinguish overloads, constructors reliably, initializers, field/type
declaration changes, annotations, imports, lambdas, or changes whose hunk header lacks a method.

`METHOD_LEVEL_SELECTOR_REQUIRED_FOR_5B_DONE: NO`.

Minimum 5b selection uses conservative **class-level expansion for every changed production class**.
Schema-v2 exact `MethodIdentity` remains valuable map data, but must not be joined to a guessed
descriptor-less Git name. Descriptor-aware precision is a later optimization requiring a change
analyzer that emits exact JVM identities and explicitly classifies every unresolvable edit for
class-level expansion. Method data may be ignored without losing safety because every
`COLLECTED_WITH_COVERAGE` mapping has class coverage.

The detector must still classify structural changes. Added or renamed production types, deleted
production types, ambiguous source-root/path-to-binary-name conversion, and any Java edit for which a
safe affected class set cannot be established produce RUN_ALL. A known changed class with no direct or
setup edge may safely add no mapped test: the complete map affirmatively reports that no historical
logical test covered it. This is distinct from detector ambiguity or an identity not safely derivable.

## Selection rules

### Direct coverage

For every safely identified changed production class, select every `TestIdentity` whose
`TestCoverage.coveredClasses` contains that binary class name. Deduplicate identities. Exact method
edges are not used in the minimum implementation. `PASS` versus `FAIL` never excludes a mapped test.
`COLLECTED_EMPTY` has no dependency edge and is selected only through another rule (for example,
because it is in an affected setup container or is newly changed after `R`).

### Setup scopes

`SETUP_SCOPE_SELECTION_REQUIRED_FOR_5B_DONE: YES`.

For each `SetupScope` whose `coveredClasses` intersects the changed class set, select every mapped and
unmapped logical test belonging to each container in exactly that scope's `affectedContainers`.
Both `CONTAINER` and `NESTED_CONTAINER` use the same bounded rule; their type records provenance of
ownership, not a different expansion algorithm. The core therefore needs a deterministic
`TestContainer -> TestIdentity` index built from the published inventory/map identities. Container
membership is exact JVM binary-container membership: ordinary methods use their declaring binary test
class; nested tests use the nested binary class. No parent/child, package, module, or global Cartesian
expansion may be guessed.

If an affected container has no logical test in the complete published inventory, the map has a
selector-relevant identity inconsistency and the result is RUN_ALL. A container with mapped leaves,
unmapped leaves, or both selects all of those leaves. A class present in direct and setup coverage
produces the union; duplicates are harmless.

### Unmapped tests

`UNMAPPED_TEST_POLICY: ALWAYS_SELECT`.

Every schema-v2 `UnmappedTest` is a known logical test with no valid dependency information and cannot
safely be excluded when selection is active. Select it for all admissible-map outcomes, independent of
`FAILED`, `SKIPPED`, `TIMEOUT`, or `COLLECTION_FAILED`. Reasons are diagnostics, not different safety
levels. Individual selection is sufficient because global completeness proves the rest of the
inventory; unmapped presence therefore does not force RUN_ALL.

This policy replaces adapter-local/module-local suppression. A mapped failed test remains dependency
selected normally; it is not automatically selected merely because `outcome == FAIL`.

### New, modified, renamed, moved, and deleted tests

- A new logical test introduced after `R` must run. Detection must use authoritative test inventory
  at `H` (or an equally sound adapter discovery input), not filename suffixes or simple-name matching.
- A changed known test container/method must run conservatively because it may contain a new or
  materially changed logical test. Class-level execution is an acceptable adapter representation.
- A rename or move is deletion of the old identity plus addition of the new identity. Run the new
  identity; do not request the absent old identity.
- A deleted test is removed from the executable head inventory and ignored for execution. Failure to
  distinguish deletion from discovery failure is uncertainty and produces RUN_ALL.
- Schema-v2 `TestIdentity` makes exact FQN/method/signature comparison possible, but the selector still
  needs trustworthy head inventory. Discovery ownership/facility remains orchestration/adapter; the
  selection policy and fail-open response belong to 5b.

### NONE

`NONE` is safe only when all of the following hold:

1. a safe published map and Git comparison are available;
2. no full-suite trigger or unknown/ambiguous structural change applies;
3. direct and setup expansion produces no mapped test;
4. the map contains no unmapped test;
5. head inventory comparison finds no new, renamed/moved target, or modified logical test requiring
   execution; and
6. the final effective execution set is empty.

Thus no production change, only ignored/non-trigger files, or a safely identified existing production
class with no map edge can yield NONE only after the remaining rules are evaluated. "All affected
tests absent" is not NONE unless absence is proven deletion against head inventory; unresolved
identity is RUN_ALL. A map with any unmapped test cannot yield NONE.

### RUN_ALL and full-suite triggers

RUN_ALL is the fail-open result whenever uncertainty could silently reduce the required tests. It is
not an exception report: adapters must execute the unfiltered suite and may additionally surface the
reason. User-configured `fullSuiteTriggers` remain part of 5b and are evaluated over every changed
path in `R..H` plus the staged/working tree. A matching build/dependency file, test infrastructure,
configuration, or other configured broad-impact path means RUN_ALL. The core supplies mechanism and
safe defaults/configuration semantics; it does not pretend that an unconfigured list recognizes every
project-specific broad-impact file. A failure to enumerate paths is RUN_ALL.

## Status/result model

| Semantic state | Meaning | Current representation | Required behavior |
|---|---|---|---|
| SELECTED | Nonempty exact mandatory test set | `SelectionOutput.status = SELECTED`; `SelectionResult.selected(nonempty)` | Include direct, setup, all published unmapped, and new/changed-head identities |
| NONE | Safe effective mandatory set is empty | `SelectionOutput.status = NONE`; empty selected result is implicit | Never accompany executable unmapped/new tests |
| RUN_ALL | Execute all applicable head tests without restrictive filter | wire status `FULL_SUITE`; `SelectionResult.fullSuite(reason)` | `FULL_SUITE == RUN_ALL` semantically |
| ERROR | Selection cannot deliver an executable instruction (bad invocation/output write, etc.) | No core semantic state; adapters throw/exit nonzero inconsistently | Reserve for inability to run safely, not unsafe selector input; unsafe input normally RUN_ALL |

`SelectionResult` cannot distinguish SELECTED-empty/NONE explicitly and knows nothing about unmapped
or setup policy. `SelectionOutput` uses unconstrained strings and splits selected/unmapped sets. The
minimum migration may preserve these public shapes, but the shared core must compute one semantic
decision before serialization. Unknown status values must fail open at execution adapters.

## Adapter consistency

### Gradle

`SelectTestsTask` is a thin engine wrapper and writes JSON. The `smartTest` consumer applies filters
for selected and separately detected unmapped tests; `FULL_SUITE` leaves execution unrestricted and
`NONE` without unmapped skips. Its input task is currently a legacy map. Gradle should remain a
representation/execution adapter only; schema-v2 unmapped and setup policy belongs in shared core.

### Maven

`SelectTestsMojo` delegates to the engine, writes JSON, and `SmartTestFilter` writes default patterns
for `FULL_SUITE`, a no-test sentinel for empty `NONE`, and whole-class includes for selected/unmapped.
The reactor `SmartTestMojo` additionally limits unmapped tests to already selected or changed-code
modules, determines affected modules, prunes absent tests, and later enriches unmapped classes from a
new legacy map. Those choices duplicate/alter semantic selection and must not govern schema-v2 5b.
Core must decide the exact mandatory logical identities; Maven may widen a selected logical identity
to its class because Surefire filtering cannot safely express it, and may locate modules, but must not
drop a core-selected unmapped/new identity.

### CLI

The CLI resolves legacy cached maps, then delegates. JSON preserves statuses; text and Ant output
blindly concatenate selected and unmapped tests and cannot represent RUN_ALL (an empty list could be
misread as run none). Missing explicit/resolved map exits 1 before the engine, unlike Gradle/Maven's
RUN_ALL behavior. For 5b, every executable output format must represent RUN_ALL unambiguously or the
CLI must reject that format with an error that cannot be mistaken for an empty selection. Map lookup
may remain CLI/5d infrastructure, but a missing usable map passed to selection means RUN_ALL.

### Logic to move to shared core

- schema-v2 admissibility, lifecycle/completeness, and revision checks;
- the final union of direct, setup, published-unmapped, and new/changed-test selections;
- NONE versus SELECTED normalization;
- fail-open reasons and trigger decision;
- identity-level deletion/rename interpretation once authoritative head inventory is supplied.

Adapters retain map location configuration, head inventory acquisition/injection, filter syntax,
module routing, file output, and the guarantee that RUN_ALL actually removes restrictive filters.

## Fail-open and ownership matrix

| Scenario | Result | Owner |
|---|---|---|
| no map / no usable candidate | RUN_ALL | 5b response; adapter/5d lookup |
| unreadable, malformed, checksum-corrupt map | RUN_ALL | 5b |
| unsupported schema (higher, lower, or legacy) | RUN_ALL | 5b |
| lifecycle not `PUBLISHED` | RUN_ALL | 5b validates; 5d publishes |
| completeness absent or any discrepancy | RUN_ALL | 5b validates; 5d constructs/proves completeness |
| map revision absent | RUN_ALL | 5b validates; 5d publication should prevent it |
| revision Git object missing/unreachable | RUN_ALL | 5b |
| revision is not ancestor of `H` | RUN_ALL | 5b |
| staleness threshold exceeded | RUN_ALL | 5b policy |
| Git command/diff/path parse fails | RUN_ALL | 5b |
| moving/inconsistent selection head | RUN_ALL | 5b |
| ambiguous source root, binary name, add/rename/delete, or structural edit | RUN_ALL | 5b until analyzer proves a safe class set |
| configured full-suite trigger matches | RUN_ALL | 5b mechanism; adapter supplies project policy |
| trusted trigger enumeration succeeds and none matches | continue | 5b |
| valid changed class has direct edges | SELECTED union | 5b |
| valid changed class has setup edges | SELECTED bounded union | 5b |
| valid existing changed class has no edge | NONE if final union empty | 5b |
| map contains unmapped tests | SELECTED union (all unmapped) | 5b |
| map contains `COLLECTION_FAILED` unmapped | SELECTED union (that test) | 5b; 5c already reports reason |
| map has zero mapped tests | SELECTED if unmapped/new exists; otherwise NONE | 5b |
| setup scope names container absent from complete inventory | RUN_ALL | 5b consistency check; 5d should prevent publication |
| mapped/unmapped overlap or duplicate identity | RUN_ALL | 5b validates; 5d should prevent publication |
| new/modified test proven in head inventory | SELECTED union | 5b policy; adapter/orchestration supplies inventory |
| renamed/moved test | select new identity; ignore proven absent old identity | 5b policy; inventory supplier identifies |
| deleted test proven absent | ignore | 5b |
| test discovery/inventory comparison failure | RUN_ALL | 5b response; adapter/orchestration facility |
| unsupported generator provenance only | continue | other/diagnostic |
| statistics mismatch alone | continue, unless validator later defines structural invariant | other/diagnostic |
| storage retrieval failure | RUN_ALL instruction if selection can proceed | adapter/5d retrieval; 5b fallback |
| selector internal exception | RUN_ALL if adapter can still execute unfiltered; otherwise ERROR | 5b + adapter |
| cannot emit/apply RUN_ALL safely | ERROR/nonzero, never empty selection | adapter |

## Existing test coverage audit

All current selector/adapter tests are legacy schema-v1 evidence unless stated otherwise.

| Scenario | Current proof/behavior | Desired 5b behavior | Gap |
|---|---|---|---|
| missing map | `TestSelectorTest`, `TestSelectionEngineTest`: FULL_SUITE; CLI explicit path exits 1 | RUN_ALL across executable adapters | CLI inconsistency; no schema-v2 core test |
| unreadable/no metadata | selector/engine legacy tests | RUN_ALL on codec/validation failure | No checksum/schema/structure cases |
| revision exists | engine and `GitChangeDetectorTest` validate Git object | require ancestor of fixed `H` | No ancestry/diverged-head test |
| staleness | engine test proves legacy commit count threshold | RUN_ALL after ancestry | Failure/negative-distance not covered |
| revision-bound diff | Git tests prove `<commit>..HEAD` plus worktree | use schema-v2 `revision` exactly | No schema-v2 integration or stable-head test |
| trigger | engine and CLI tests prove configured glob => FULL_SUITE | RUN_ALL | No diff-failure/structural-trigger coverage |
| class selection | `TestSelectorTest` proves legacy edges | schema-v2 `TestIdentity`/`TestCoverage` | Entire migration missing |
| method selection | legacy descriptor-less matching and zero-hit escalation tests | deferred; class-expand every changed class | Existing behavior is not schema-v2 proof and must not be preserved as precision |
| empty/no matching edges | selector tests return empty; engine returns SELECTED-empty for production change | NONE only after all mandatory sources empty | Status normalization gap |
| no production changes | engine/CLI tests return NONE | still select unmapped/new first | Current early NONE may carry work separately |
| outcome PASS/FAIL | schema codec/model tests preserve values | outcome never excludes dependency | No selector test |
| `COLLECTED_EMPTY` | schema model/codec tests only | no direct edge; selectable by other rule | No selector test |
| setup scope | `CoverageMapContractTest` proves round trip/overlap only | bounded container expansion | No selector implementation/test |
| affected container absent | no proof | RUN_ALL | Missing |
| schema-v2 unmapped reasons | schema contract round trips all reasons | always select every reason | No selector implementation/test |
| map with unmapped plus NONE | `SmartTestFilterTest` proves adapter runs legacy unmapped | status SELECTED/effective nonempty set | Core/adapters disagree semantically |
| new test class | engine/new-test/CLI legacy scanning tests | exact new logical identities always run | Heuristic, nested, FQN, method/signature gaps |
| new test method | modified known class becomes class-level unmapped | run changed/new head identities | No exact inventory comparison |
| rename/move/delete | detector parses `R` target and `D`, but policy ignores both | new target runs; proven deletion ignored | Missing end-to-end policy/tests |
| globally incomplete map | validator/schema contract tests reject discrepancies | RUN_ALL | Selector never calls validator |
| lifecycle candidate/fragment | validator test establishes not published | RUN_ALL | Selector never checks lifecycle |
| zero mapped tests | legacy selector forces FULL_SUITE; schema tests allow valid structures | valid; run unmapped/new or NONE | Conflicting legacy behavior |
| result model | `SelectionOutputTest` round trips arbitrary strings/nulls | four semantic states, RUN_ALL wire-compatible | No typed invariant/unknown-status safety |
| Gradle adapter | no focused schema-v2 selector tests found | shared decision faithfully applied | Missing |
| Maven adapter | `SmartTestFilterTest` covers FULL_SUITE/NONE/SELECTED/unmapped | must not suppress mandatory identities | Reactor/module policy gap |
| CLI adapter | `SelectTestsCommandTest` covers JSON/txt/ant and statuses | RUN_ALL unambiguous in every format | txt/ant ambiguity and missing-map inconsistency |
| resolver | `CoverageMapResolverTest` proves legacy local/remote/nearest | only validated schema-v2 candidates usable | Reads legacy metadata without validation |
| Git failure/internal exception | no direct proof | RUN_ALL, or ERROR only if unfiltered execution cannot be issued | Missing |

## Schema-v2 migration boundary and risk

Current legacy dependencies are `mapper.CoverageMap`, `CoverageMapMetadata`, `CoverageMapReader`, and
`IndexedCoverageMap` in `TestSelectionEngine`, `TestSelector`, `NewTestDetector`, resolver/cache paths,
reports, CLI query/merge, and legacy generators. The authoritative parallel model is
`coverage.model.CoverageMap` decoded by `CoverageMapCodec` and validated by
`CoverageMapValidator`.

The smallest 5b boundary is the shared selection ingress: decode bytes once with
`CoverageMapCodec`; require a validation result with no error including `PUBLISHED` and non-null
complete completeness; pass semantic `CoverageMap` plus a fixed change analysis/head inventory into
a pure selector policy; emit one normalized `SelectionOutput`. `TestSelector` must stop re-reading a
file. Legacy generation/report/query/merge can remain outside this boundary and are not accepted as
5b selector input. `CoverageMapResolver` must either become schema-v2-aware or return candidates for
the ingress to validate; it may never make "nearest" equal "safe".

Parallel model risk: **HIGH**. Identically named `CoverageMap` classes currently make accidental
legacy imports easy, and the production selector has no access to schema-v2 lifecycle, completeness,
setup, unmapped, exact identities, or checksums.

## Backlog ownership

### 5b

Schema-v2 selection ingress and validation; revision/ancestor/staleness/diff policy; conservative
change classification; full-suite triggers; direct class dependency selection; setup-container
expansion; all-unmapped policy; new/changed/deleted-test policy from supplied head inventory; normalized
SELECTED/NONE/RUN_ALL decision; fail-open handling shared by adapters.

### 5c

**DONE.** It owns observations, local collection completeness, exact identities, outcomes, unmapped
reasons, and bounded setup scopes. This audit found no contract inconsistency and does not reopen it.

### 5d

**NOT STARTED — POC proven.** It owns independent expected inventory/shards, fragment transport and
merge, global completeness construction, immutable publication, trusted distribution, storage,
retention, scheduling, and failure recovery. It must publish only valid `PUBLISHED` maps, but 5b still
validates what it consumes.

### 5e

**NOT STARTED / independent.** Arbitrary caller-selected base/head behavior is not needed for normal
`R -> H` selection.

## Minimal implementation plan

Two implementation tasks plus a focused closure task are justified. Combining ingress with policy
would make it difficult to prove fail-open behavior at the legacy/schema boundary; omitting the final
adapter regression would leave known Maven/CLI contradictions untested.

### TASK 36 — Migrate selector ingress and change analysis to safe schema-v2 revision semantics

- **Why:** the current selector consumes only legacy maps and cannot establish admissibility or a safe
  Git boundary.
- **Scope:** decode/validate schema-v2 published maps once; require completeness; establish fixed
  `R`/`H`, ancestry and staleness; make Git failures explicit; conservatively classify production
  classes and structural ambiguity; retain trigger evaluation; define/inject exact head test inventory
  needed for new/deleted identity comparison; normalize unsafe cases to RUN_ALL.
- **Acceptance:** focused tests cover every input-admissibility row, exact `R..H` plus worktree,
  nonancestor/unreachable/stale revisions, Git failures, triggers, added/renamed/deleted structural
  cases, and new/modified/deleted test identities. No legacy map is silently accepted. No method name
  is matched to schema-v2 descriptor data.
- **Modules:** `smart-test-picker-common`; minimal adapter wiring in Gradle/Maven/CLI only to supply
  paths/inventory and preserve RUN_ALL.
- **Must not change:** schema v2, codecs' wire contract, collection/fragments, publication/storage,
  Jenkins, 5d, 5e arbitrary base/head, or legacy generators/reporting.
- **Dependency:** Task 35; 5a and 5c complete.
- **Size:** MEDIUM.

### TASK 37 — Implement schema-v2 direct, setup-scope, and unmapped selection policy

- **Why:** admissible schema-v2 data still needs one authoritative safe selection decision.
- **Scope:** pure class-level direct-edge union; container-to-logical-test index; bounded setup-scope
  expansion; all-reasons unmapped union; new/changed-test union from Task 36 input; deleted-test
  exclusion; absent-container consistency fallback; deduplication; SELECTED/NONE/RUN_ALL
  normalization. Preserve current wire spelling `FULL_SUITE`.
- **Acceptance:** table-driven tests cover direct edges, overlap, PASS/FAIL, COLLECTED_EMPTY, every
  unmapped reason, CONTAINER/NESTED_CONTAINER one/many scopes, no Cartesian expansion, missing
  affected container, zero mapped tests, new/renamed/deleted tests, and the exact NONE conditions.
- **Modules:** `smart-test-picker-common` and only minimal shared output-model changes needed to
  express the decision.
- **Must not change:** method-level precision, schema, change lookup semantics established in Task 36,
  collectors, fragments, map publication/lookup/storage, Jenkins, or 5d/5e.
- **Dependency:** Task 36.
- **Size:** MEDIUM.

### TASK 38 — Close 5b with adapter and real-project selector regression

- **Why:** Gradle, Maven, and CLI currently interpret statuses/unmapped work differently, and unit
  policy proof alone cannot show that RUN_ALL is actually unfiltered.
- **Scope:** make all three adapters faithfully consume the core result; remove semantic suppression or
  duplication (especially Maven unmapped module gating); make CLI text/Ant RUN_ALL unambiguous; add
  focused adapter regressions and one pinned real-project validation. Update closure docs/status only
  after evidence passes.
- **Acceptance:** each adapter proves SELECTED, true empty NONE, FULL_SUITE/RUN_ALL, setup-selected and
  unmapped/new execution; unknown/invalid output cannot run fewer tests; the pinned project proves
  revision-bound direct and setup selection plus controlled RUN_ALL and new-test cases; production
  collectors and map contents remain unchanged.
- **Modules:** `smart-test-picker`, `smart-test-picker-maven`, `smart-test-picker-cli`, focused common
  integration tests, docs/evidence.
- **Must not change:** schema v2, collectors, Jenkins, publication/storage/orchestration, 5d, 5e, or
  map generation.
- **Dependency:** Task 37.
- **Size:** MEDIUM.

## Real-project validation recommendation

- **Project:** Spring PetClinic pinned to the Task 28/32 revision.
- **Reason:** it is small enough for a selector regression, already has authoritative schema-v2 Gradle
  evidence (69 tests, rich direct edges, ten bounded setup scopes), and avoids rerunning Spring Core's
  expensive 3,638-test collection. JGraphT evidence is legacy schema v1 and cannot prove 5b.
- **Scenarios:** start from the existing pinned schema-v2 evidence or a Task-5d-style test fixture,
  change one directly covered class, change one setup-covered class, inject one controlled unmapped
  identity, advance from `R` to `H`, add a test, and separately force missing/invalid/nonancestor/trigger
  RUN_ALL. Verify actual executed identities, not only JSON.
- **Required for 5b closure:** **YES**, once in Task 38; it is not run in Task 35.

## Final decision

- 5b contract reconstructed: **YES**.
- Current selector safe for schema-v2 production: **NO**.
- Implementation blockers:
  1. selector ingress/change analysis still uses legacy schema-v1 types and does not validate schema-v2
     lifecycle, completeness, checksum, ancestry, or a stable revision-bound diff;
  2. shared core lacks setup-scope, schema-v2 unmapped, exact head-inventory/new-test, and normalized
     fail-open/NONE policy, while adapters contain contradictory semantic logic.
- Recommended implementation tasks: **three** (two implementation tasks and one adapter/real-project
  regression closure task).
- Exact next task: **TASK 36 — Migrate selector ingress and change analysis to safe schema-v2
  revision semantics**.
