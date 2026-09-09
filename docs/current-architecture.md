# Current architecture

## TASK63 automatic Jenkins PR selection wiring

The Jenkins plugin now exposes `stpPrSelect`, a block step that resolves the current typed PR with
`ScmPrContextResolver`, binds head inventory generation to the resolved source-head revision, and
passes `ResolvedPrContext.toFiveERevisions()` unchanged into the existing explicit 5e flow. Callers
do not provide integration, PR-base, PR-head, or workspace revisions. Resolver failures remain typed
and do not select or create a plan; no item-8 fallback policy is implied. The `SOURCE_HEAD` automatic
public flow is fully Docker validated end-to-end through real Gradle head-inventory generation and
common-core explicit selection.
`SYNTHETIC_MERGE` is safely reported as `PR_HEAD_INVENTORY_UNAVAILABLE` until a separate immutable
source-head workspace exists; the merge workspace is never relabeled or mutated. The manual
`stpExplicitPrSelect` API and all TASK62 common-core semantics remain unchanged. Backlog item 6 is
still in progress pending real external PR E2E and closure audit; items 7 and 8 are not started.

This is the authoritative architecture snapshot through TASK 54, with later clarification notes.

## TASK62 PR base semantics clarification

GitHub Branch Source provider evidence proved that `PullRequestSCMRevision.baseHash` is the frozen
target/base commit associated with the indexed PR revision (and, for a validated merge revision, the
non-head merge parent), not the historical point at which the feature branch diverged. The old
`prBaseRevision == integrationRevision` eligibility comparison therefore duplicated provider target
metadata for GitHub and could not reliably express stale-PR policy across providers.

The canonical eligibility rule is now that the frozen `integrationRevision` must be an
ancestor-or-equal of the frozen source `prHeadRevision`. Failure produces `BASE_OUT_OF_DATE`; Git can
prove that the head is not based on the required integration commit but cannot distinguish an ordinary
out-of-date branch from force-pushed or otherwise divergent history. `prBaseRevision` remains required,
resolved as a full commit, and retained as provider provenance for compatibility, but does not decide
eligibility. Map ancestry/distance rules, missing/shallow protections, committed `map..head` selection,
and the separation of synthetic workspace revision from source head are unchanged. Historical TASK48–54
sections below describe the contract as it existed when those tasks closed.

## Modules

- `smart-test-picker-common`: schema v2 authority, semantic coverage/test/method model, map and
  fragment codecs, validation, and retained legacy JaCoCo/common support.
- `smart-test-picker-core`: existing JaCoCo per-test collection and selector-support path.
- `stp-agent`: ASM javaagent with method-entry instrumentation, deterministic method catalog,
  context propagation, integrity metrics, and schema-v2 fragment emission.
- `stp-runtime`: runtime ownership/context, event aggregation, ASM-to-schema projection, and typed
  setup/integrity state.
- `stp-junit-adapter`: internal listener-only JUnit Platform lifecycle bridge, bundled with the agent.
- `smart-test-picker`: the Gradle plugin with one `CoverageCollectorBackend` selection boundary. ASM
  is the default and JaCoCo is the explicit legacy fallback.
- `smart-test-picker-maven`: the Maven plugin, retaining its legacy JaCoCo map path and adding direct
  schema-v2 fragment projection from authoritative JUnit identity sidecars and per-test JaCoCo reports.
- `smart-test-picker-cli`: the user-facing CLI for local/worktree selection and explicit PR selection;
  explicit mode delegates frozen integration/base/head eligibility and committed schema-v2 analysis to
  the common core.

Gradle production mapping now attaches the version-aligned ASM agent automatically to the dedicated
`generateSmartTestCoverage` task and emits a deterministic workspace-relative schema-v2 fragment.
The existing JaCoCo exec/XML/map path remains intact behind the fallback backend. Maven's additive
`generate-coverage-fragment` goal emits exact descriptor-aware class/method observations but no setup
scopes, because the Maven JaCoCo path does not own setup attribution. Fragment publication,
joining, global completeness, and Jenkins orchestration remain outside the Gradle backend.

## Intended release-artifact surface

User-facing artifacts are the Gradle plugin, Maven plugin, STP CLI, and STP agent. `stp-runtime`, the
JUnit listener, and relocated ASM are internal/bundled. Common/core schema and selector libraries are
shared internal dependencies. Publishing/configuration work needed to realize this target remains a
follow-up. TASK 27 adds the agent's consumable/published artifact direction needed by the Gradle
plugin; the runtime and JUnit adapter remain bundled inside that shaded agent artifact.

## TASK 28 real-project validation

At Spring PetClinic `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, the production Gradle ASM task
mapped all 69 runnable logical tests with exact single-versus-three-shard equality across test identities,
418 class edges, 1,343 descriptor-aware method edges, and 68 bounded setup edges. Repeat fragments were
byte-identical; normal project JaCoCo coexistence, the default ASM selection, and configuration-cache
reuse passed. No critical collector integrity failure or historical Cartesian setup relation appeared.

TASK 29 closes both Gradle limitations: an external published consumer resolves the version-aligned
shaded agent without an override, and the dedicated JACOCO task restores the legacy listener runtime,
destination, per-test exec, XML, and map pipeline. Version selection comes from a Gradle-generated
resource and fails closed rather than assuming a fallback version.

Task 30 updated the Docker Jenkins POC to transport and join schema-v2 Gradle ASM fragments on three
physical agents. That is validation evidence, not production orchestration: storage, retention,
lookup, scheduling, failure recovery, and publication policy remain Task 5d. Task 5d remains
**NOT STARTED — POC proven**.

## Jenkins physical multi-agent mapping POC (Task 30)

The Gradle ASM mapping path is validated through Jenkins on three distinct Docker agents. Jenkins orchestrates the production `generateSmartTestCoverage` task; schema-v2 fragments and diagnostics move by stash/unstash and are decoded/joined with the shared common model. The 69-runnable-test PetClinic baseline and 32/12/25 physical shard join are semantically identical (418 class edges, 1,343 method edges, ten setup scopes). This is POC evidence, not production storage or retention.

## Task 32 runtime safety

Ownership-sensitive runtime work now follows exact bounded attribution or explicit safe degradation.
Inherited lifecycle setup remains bounded to the concrete JUnit container. Recognized unbounded shared
setup and unsupported async submission emit typed errors, create no guessed edge, and make the local
fragment incomplete; generic `NO_ACTIVE_TEST` and `LATE_EVENT` remain nonfatal. Fragment publication
invalidates stale output, validates serialized schema-v2 bytes, and replaces through a sibling temporary
file. The Gradle mapping task then decodes and verifies completion, revision, and shard.

The one-agent Jenkins Spring Core regression at
`99a366baf6640b275d08dde60f05da719139bb6a` passes through `stpCoverageMap` and production
`:spring-core:generateSmartTestCoverage` on JDK 21/Gradle 8.14.2. The completed fragment contains 3,632
mapped and six skipped/unmapped logical tests with clean critical integrity. Task 32 completed the
Gradle/runtime portion of 5c; Task 33 subsequently completed Maven schema-v2 fragment production.
Task 5c is complete, and Task 5d remains **NOT STARTED — POC proven**.

## Task 33 Maven schema-v2 adapter

The Maven listener writes structured declared-method identity beside each per-test exec artifact;
parameterized/template invocations collapse by declared signature and declared overloads remain
distinct. `generate-reports` records `COVERED`, `EMPTY`, or `FAILED` for each session. The new fragment
goal requires explicit revision and shard, validates through the shared codec/validator, and writes
deterministically through a temporary file. Missing or malformed local artifacts yield an incomplete
fragment and known identities become `COLLECTION_FAILED` unmapped entries. It neither discovers the
revision nor asserts expected inventory/global completeness.

The Maven capability is class coverage YES, exact descriptor-aware method coverage YES, setup scopes
NO, direct schema-v2 fragments YES, unmapped reporting YES, and local integrity gating YES. Gradle ASM
additionally has bounded setup ownership. The schema semantics are shared. Task 5c is **DONE**; Tasks
5b and 5d remain unchanged.

## Task 35 selector contract

Task 35 reconstructs 5b without implementing it. The current production selector still consumes the
parallel legacy `mapper.CoverageMap`; it is not safe for schema-v2 production. The authoritative 5b
boundary must decode and validate a complete `PUBLISHED` schema-v2 map, use its exact revision `R` as
the Git base for `R -> HEAD`, and fail open to the wire status `FULL_SUITE` (semantic RUN_ALL) whenever
map, revision, diff, identity, or structural-change safety cannot be proved. Minimum safe selection is
class-level: descriptor-aware method precision is deferred until change analysis can produce exact JVM
identities. Direct class edges, bounded setup-scope containers, every schema-v2 unmapped test, and
new/changed head tests form one union. `NONE` is valid only when that effective union is empty.

The detailed decision and minimal Tasks 36–38 plan are in
[`task35-5b-selector-semantics-audit.md`](task35-5b-selector-semantics-audit.md). Completing this audit
does not change 5b from **NOT STARTED**, reopen 5c, or start 5d/5e.

## Task 36 schema-v2 selector safety boundary

Task 36 starts 5b by adding an authoritative schema-v2 ingress and change-analysis API in common.
It decodes once through `CoverageMapCodec`, requires a validated complete `PUBLISHED` map, fixes one
selection head, verifies the map revision is its ancestor, and computes committed, staged, unstaged,
and untracked changes without method-level parsing or Git mutation. Unsafe input or analysis returns
semantic RUN_ALL with public status `FULL_SUITE`.

The build-tool-neutral `HeadTestInventory` supplies exact logical JUnit `TestIdentity` values. A Test
Inventory is the authoritative complete logical test set for one concrete test target at one concrete
revision; it is neither execution evidence nor an arbitrary list of names. The resulting
`SelectionContext` classifies new, deleted, and changed-container tests but deliberately performs no
direct/setup/unmapped selection union. Inventory provisioning and adapter execution remain TASK 38;
CI-wide inventory and orchestration remain 5d. Task 5b is **IN PROGRESS**.

## Task 37 schema-v2 selector core policy

Task 37 adds `SchemaV2TestSelector`, a pure consumer of TASK 36's analysis result and context. It
selects exact class-coverage dependencies, expands matching setup scopes only to their explicitly
named head containers, always selects published unmapped identities still runnable at head, and adds
new and changed head tests. The sorted union is intersected with authoritative head inventory.

Safe empty unions become `NONE`; nonempty unions become `SELECTED`; TASK 36 RUN_ALL and internal
selector identity inconsistencies become public `FULL_SUITE`. `selectedTests` is authoritative while
`unmappedTests` is diagnostic. Gradle, Maven, CLI, and PetClinic execution integration remain TASK 38,
so Task 5b remains **IN PROGRESS**.

## Task 38 adapter closure

Gradle, Maven, and CLI now call the common schema-v2 analyzer and selector through an exact structured
head-inventory file. `selectedTests` is the sole mandatory execution set; unmapped data is diagnostic.
Adapters conservatively widen to complete test classes, represent `NONE` explicitly, and remove
restrictive filtering for `FULL_SUITE` or unknown states. Pinned PetClinic execution proves SELECTED,
NONE, missing-map, trigger, nonancestor, unmapped, new-test, and bounded setup behavior. Details are in
[`task38-5b-adapter-and-petclinic-closure.md`](task38-5b-adapter-and-petclinic-closure.md).

TASK 39 implements production provisioning. Gradle and Maven now generate the exact
inventory themselves through shared JUnit Platform discovery; the CLI retains explicit input.
See [`task39-head-inventory-generation.md`](task39-head-inventory-generation.md).

TASK 40 aligns published logical inventory with authoritative discovery. Orchestration explicitly
accounts for intentionally non-executable declarations in completeness without representing them as
unsafe `UnmappedTest`; mapped/unmapped executable tests retain their existing selection semantics.
Pinned PetClinic now has exact 73-of-73 logical equality while preserving 69 mappings, 418 class
edges, 1,343 method edges, and ten setup scopes. No-change selection is `NONE` and executes zero tests.
The real Maven lifecycle and TASK 33 identity equality are also validated. See
[`task40-logical-vs-executable-inventory-contract.md`](task40-logical-vs-executable-inventory-contract.md).

Canonical state: 5a **DONE**, 5b **DONE**, 5c **DONE**, 5d **NOT STARTED — POC proven**, and 5e
**NOT STARTED / independent**. The dependency remains `5a -> {5b, 5c} -> 5d`.

## Task 42 Spring Core end-to-end validation

Pinned Spring Core now validates the whole local schema-v2 chain at large-project scale: 3,643
authoritative logical target identities, 3,638 mapped executions, five positively evidenced
intentional non-executions, zero missing/unexpected/duplicate identities, 45,694 class edges, 171,917
method edges, and 112 bounded setup scopes. The published map passes no-change, selected-change,
new/changed-test, missing/corrupt/nonancestor/trigger fail-open, setup-scope, unsafe-unmapped, and actual
Gradle execution scenarios. See
[`task42-spring-core-full-e2e-validation.md`](task42-spring-core-full-e2e-validation.md).

Spring exposed and TASK 42 fixed two narrow regressions: mixed runnable/skipped parameterized
invocations now retain collected logical-test coverage, and `smartTest` mirrors the standard target's
JVM/system-property/candidate environment. The validated fixes close the temporary 5c reopening. The
canonical backlog remains 5a/5b/5c **DONE**, 5d **NOT STARTED — POC proven**, and 5e **NOT STARTED /
independent**.

## Task 43 Jenkins map orchestration

TASK 43 officially starts 5d. The companion Jenkins plugin now has an orchestration plan v2 that freezes
project, branch, revision, build tool, target, collector, exact inventory, deterministic assignments and
bounded artifact identities. Its mapping runtime adds a storage interface, immutable revision-keyed map
writes, validated latest-map lookup, branch pointers and locked Git-ancestry stale-publication protection.
The file backend is a local/reference implementation, not a claimed remote production service.

The orchestrator/collector ownership boundary and mapping/selection separation are unchanged. Production
positive non-execution evidence, automatic stash/unstash, prepare-owned discovery, remote storage, the full
failure matrix and fresh physical validations remain open. Canonical state is therefore 5a/5b/5c **DONE**,
5d **IN PROGRESS**, and 5e **NOT STARTED / independent**. See
[`task43-5d-map-orchestration-productization.md`](task43-5d-map-orchestration-productization.md).

## Task 44 positive execution accounting and Jenkins transport

The companion Jenkins plugin now freezes and captures authoritative Gradle/Maven inventory during preparation, produces exact revision/shard/target/tool-bound execution evidence, and aggregates physical invocation outcomes to logical `TestIdentity`. Plan v2 owns successful shard stash and publisher unstash. Join publication requires exact disjoint accounting from mapped, executable-unmapped, and positively non-executed identities; subtraction never proves non-execution. See [`task44-5d-execution-accounting-and-transport.md`](task44-5d-execution-accounting-and-transport.md). Item 5d remains **IN PROGRESS** and 5e is unchanged.

## Task 45 Spring Core Jenkins validation

The existing Docker Jenkins plan-v2 workflow now validates pinned Spring Core through the public prepare,
map, and publish steps. One collector shard accounts exactly for all 3,643 logical identities as 3,638
mapped plus five positively non-executed, publishes 45,704 class edges, 171,910 method edges, and the same
112 bounded setup scopes as TASK42, and preserves the 4,705-case physical result. Spring required focused
Gradle adapter fixes for typed filters, late task configuration of the runtime/evidence properties, and
exact `@Disabled` parameterized evidence. The local/reference publication is valid and immutable. Item 5d
remains **IN PROGRESS**, but is ready for a separate closure audit; 5e remains independent and untouched.

## Task 46 5d closure audit

The final audit reconstructs the 5d contract from the architecture, backlog, implementation, tests, and
TASK44/45 evidence. Revision and authoritative inventory remain orchestrator-owned; execution, coverage,
executable-unmapped results, setup scopes, diagnostics, and runtime integrity remain collector-owned. The
join requires exact disjoint accounting from mapped, executable-unmapped, and positively non-executed
identities, and publication remains complete-schema-v2-only, immutable, read-back verified, and protected
against stale pointer updates. The public plan-v2 lifecycle owns fragment/evidence transport and safe latest
lookup returns no map rather than synthesizing an empty map.

No correctness blocker remains. The file store is still the **LOCAL / REFERENCE IMPLEMENTATION**. Future
remote backends share one storage contract; planned families are RAW (generic artifact/raw HTTP, not
Nexus-specific), GCS, S3, and AZURE_BLOB. Their implementations, retention operations, deployment
scheduling/cadence, retry scheduling, and CI-wide rollout are intentionally deferred extension or adoption
work and do not block base orchestration closure. Canonical state is now 5a/5b/5c/5d **DONE**, with 5e
**NOT STARTED / independent**. See [`task46-5d-closure-audit.md`](task46-5d-closure-audit.md).

## Task 48 5e revision contract audit

TASK48 initially defined, without implementation, the strict eligibility equality
`prBaseRevision == mapRevision == integrationRevision`. TASK49 refines that decision for production:
`prBaseRevision == integrationRevision` remains mandatory, while `mapRevision` may be an
ancestor-or-equal of integration and integration must be an ancestor-or-equal of the PR head. All
inputs resolve as frozen commits. Selection uses `diff(mapRevision, prHeadRevision)`, and the existing
`maxCommitDistance` applies to that complete interval. A stale PR is still rejected before selector
invocation as `BASE_OUT_OF_DATE`; an incompatible or too-old map produces `FULL_SUITE`; invalid input
or head ancestry produces a preflight error. PR CI remains commit-to-commit and 5b selector policy is
unchanged. TASK49 implements the common core model and preflight, but adapter wiring and real-project
validation remain open. Canonical state is 5a/5b/5c/5d **DONE** and 5e **IN PROGRESS**. See
[`task48-5e-revision-contract-audit.md`](task48-5e-revision-contract-audit.md).

## Task 50 5e explicit selector flow

TASK50 integrates the TASK49 revision preflight into a build-tool-neutral explicit PR selector flow in
the common core. Eligible analysis uses only the committed `mapRevision..prHeadRevision` diff with
rename detection, binds `SelectionContext.headRevision` to the explicit PR head, and compares the
published map inventory with the supplied PR-head inventory. It does not inspect current HEAD, staged,
unstaged, or untracked state. The existing local/worktree analyzer remains unchanged and separate.

The explicit result distinguishes `SELECTION_RESULT`, `BASE_OUT_OF_DATE`, and `ERROR` without changing
`SelectionOutput`; preflight `FULL_SUITE` becomes the existing selector fail-open output. TASK51 closes
TASK50's remaining common-core provenance gap: revision-bound inventories persist their
frozen full commit ID, and explicit PR selection requires that ID to resolve exactly to
`prHeadRevision` before committed analysis or 5b policy runs. Revisionless legacy inventories remain a
local/worktree-only compatibility format and are rejected by explicit PR mode. Adapter wiring and
real-project proof remain open, so 5e stays **IN PROGRESS**. Canonical state remains 5a/5b/5c/5d
**DONE**, 5e **IN PROGRESS**.

## Task 52 CLI explicit PR selection

TASK52 exposes the common-core `ExplicitPrSelectorFlow` through the existing `select-tests` command.
The caller supplies the coverage map, revision-bound head inventory, project directory, and frozen full
integration, PR-base, and PR-head commit IDs. Map and inventory revisions remain artifact-owned. The
CLI adds no revision policy and does not stamp legacy inventories; it emits a JSON envelope that keeps
`BASE_OUT_OF_DATE` and `ERROR` separate from unchanged normal `SelectionOutput` statuses. Existing
local/worktree selection remains separate and unchanged. Gradle/Maven adapter exposure, real-project
validation, and the final 5e closure audit remain open, so 5e stays **IN PROGRESS**.

## Task 53 Gradle/Maven explicit PR adapters

Gradle and Maven now expose frozen `integrationRevision`, `prBaseRevision`, and `prHeadRevision`
inputs as an additive mode. Both decode artifact-owned map and inventory revisions and delegate
eligibility plus the `mapRevision..prHeadRevision` interval to `ExplicitPrSelectorFlow`.
`BASE_OUT_OF_DATE` and `ERROR` remain outside `SelectionOutput` and stop selective execution.
Ordinary HEAD/worktree-aware local selection is unchanged.

New explicit inventories are bound to `prHeadRevision` only after proving workspace HEAD is that exact
full commit. No checkout, reset, merge, or rebase occurs. Maven inventory-generating goals request
test-scope dependency resolution so discovery uses the configured target runtime. Pinned PetClinic
dual-build evidence is in
[`task53-5e-gradle-maven-petclinic.md`](task53-5e-gradle-maven-petclinic.md). Task 5e remains
**IN PROGRESS** pending its closure audit.

## Task 54 5e closure audit

The final audit verifies the complete implementation chain: TASK49 revision preflight, TASK50 explicit
selector flow, TASK51 revision-bound inventory provenance, TASK52 CLI exposure, and TASK53 Gradle/Maven
exposure plus pinned PetClinic equivalence. Explicit PR selection requires frozen full commits,
`prBaseRevision == integrationRevision`, map ancestry of integration, integration ancestry of PR head,
and exact inventory/head equality. Both distance and selection use `mapRevision..prHeadRevision`.
Stale PRs remain `BASE_OUT_OF_DATE`; divergent or too-old maps produce normal `FULL_SUITE`; invalid
head ancestry and provenance failures remain `ERROR`.

No correctness blocker remains. Local/worktree selection, the 5b selector union, 5c mapping, and 5d
orchestration/publication are unchanged. Historical-map discovery, remote registry lookup,
CI-provider revision extraction, further Jenkins deployment/adoption configuration, and distance-policy
tuning are deferred extensions or adoption work. The companion Jenkins repository now exposes and has
validated the explicit three-revision selector flow; Jenkins still does not derive SCM/PR revisions.
Canonical state is 5a/5b/5c/5d/5e **DONE**. See
[`task54-5e-closure-audit.md`](task54-5e-closure-audit.md).
