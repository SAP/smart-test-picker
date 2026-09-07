# Current architecture

This is the authoritative architecture snapshot after TASK 27 Gradle collector integration.

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
- `smart-test-picker-cli`: the existing user-facing CLI and legacy paths; schema-v2 CLI integration
  has not started.

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

The build-tool-neutral `HeadTestInventory` supplies exact runnable `TestIdentity` values. The resulting
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

The PetClinic 69-entry execution-produced map does not contain the four disabled DB-profile
declarations found by authoritative discovery, so the required no-change `NONE` regression remains
open under the locked new-test policy.

Canonical state: 5a **DONE**, 5b **IN PROGRESS**, 5c **DONE**, 5d **NOT STARTED — POC proven**, and 5e
**NOT STARTED / independent**. The dependency remains `5a -> {5b, 5c} -> 5d`.
