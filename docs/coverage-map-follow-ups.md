# Coverage-map follow-up backlog

This document records current implementation requirements established after the original coverage-map contract. Detailed
experimental evidence remains with the POC that produced it; this file contains only the consequences
for the corresponding backlog items.

## Task 5b — Selector semantics

Status: **DONE — authoritative logical inventory aligned and selector safety preserved**

TASK 35 establishes that 5b must consume only a validated, globally complete `PUBLISHED` schema-v2
map and must anchor the normal comparison at the map revision. Minimum safe selection is class-level;
the current descriptor-less Git method detector cannot safely drive schema-v2 `MethodIdentity`.
Selection is the union of direct class edges, bounded `SetupScope.affectedContainers`, every unmapped
logical test regardless of reason, and new/changed head tests. `NONE` means that final union is empty.
Unsafe map, revision, Git, identity, or structural-change state means semantic RUN_ALL, represented by
the existing public `FULL_SUITE` spelling. Implementation is intentionally deferred to Tasks 36–38;
see [`task35-5b-selector-semantics-audit.md`](task35-5b-selector-semantics-audit.md).

TASK 36 adds the shared decode-once schema-v2 ingress, exact revision-to-fixed-head Git analysis,
conservative structural class detection, configured full-suite triggers, and an authoritative exact
`TestIdentity` head-inventory boundary. It yields safe semantic context or `FULL_SUITE`; it does not
apply direct, setup-scope, or unmapped unions.

TASK 37 adds the pure shared policy over that context. It unions exact class coverage, exact bounded
setup containers, all published unmapped tests still at head, and new/changed head identities; then
intersects with head inventory, deduplicates, sorts, and normalizes to `SELECTED`, `NONE`, or
`FULL_SUITE`. Missing affected containers fail open. Method edges and outcomes are not selection
filters. Adapter inventory provisioning, execution consistency, and real-project regression remain
TASK 38. No 5d orchestration or 5e base/head behavior is introduced.

## Task 5c — Coverage runtime and fragment production

Status: **DONE — Gradle runtime/ASM and Maven schema-v2 adapters implemented**

TASK 24 established direct ASM observation projection and TASK 25 made schema v2 authoritative.
The current foundation proves ASM direct collection, descriptor-aware production method identity,
distinct overloaded test identity, JUnit invocation collapsing, explicit revision/shard binding,
deterministic fragment production, collector integrity gating, bounded setup ownership,
single-versus-multi collector setup equivalence, parallel container ownership, and a real
javaagent-to-schema-v2-fragment end-to-end path.

The three-agent Spring PetClinic POC exposed an unsafe behavior in the current experimental collector.
Run-wide setup attribution can create false setup-to-test relations because it combines every setup
class observed in a test JVM with every test container observed in that JVM.

The PetClinic baseline produced three false setup edges that were absent from the distributed result:

- `CacheConfiguration -> ValidatorTests`
- `CrashController -> ValidatorTests`
- `WelcomeController -> ValidatorTests`

Task 5c attributes setup coverage to the actual JUnit lifecycle/container scope and does not derive
affected tests from a run-wide Cartesian product. Schema-v2 `CONTAINER` and `NESTED_CONTAINER` scopes
represent this bounded attribution. `AfterAll` cannot leak to the last leaf.

Additional acceptance criterion:

```text
single-shard and multi-shard collection must produce
semantically identical setup coverage
```

TASK 27 adds production Gradle collector selection through one backend abstraction, makes ASM the
default, retains JaCoCo as an explicit fallback, resolves the agent automatically, and verifies
ordinary project-JaCoCo coexistence. It does not make Task 5c DONE.

Task 31 narrows the remaining 5c work to two closure tasks: safe production handling for
ownership-sensitive inherited/shared/async setup or execution that the collector cannot attribute,
including fail-closed fragment replacement/write behavior; and schema-v2 fragment production through
the supported Maven adapter. The first need not add broad framework support: correct attribution or
explicit incomplete-fragment degradation is sufficient.
Maven need not copy Gradle's collector design, but it must emit the authoritative fragment contract
without guessing descriptor or setup data. Inventory discovery is not a remaining collector concern:
the collector reports what ran, while the orchestrator owns what should run. Fragment merge,
publication, and global completeness remain 5d; selector fallback policy remains 5b. Task 5c remains
**IN PROGRESS**.

TASK 28 validates the production Gradle ASM execution on pinned Spring PetClinic: 69/69 runnable logical
tests mapped, byte-identical repeat output, and exact single-versus-three-shard equality for test, class,
method, outcome, unmapped, and bounded setup semantics. Project JaCoCo coexistence, default ASM selection,
and configuration-cache reuse also pass. External automatic agent resolution and the real-project
JACOCO fallback remain follow-ups, so Task 5c stays **IN PROGRESS**.

TASK 29 closes those two Gradle follow-ups. Published consumers automatically resolve the aligned
shaded agent, and the dedicated JACOCO mapping lifecycle again produces per-test exec, XML, and the
legacy map. The existing Jenkins POC was audited and remains schema-v1-only, so no schema-v2 physical
multi-agent validation is claimed and Task 5c remains **IN PROGRESS**.

## Task 5d — Fragment merge, publication, and Jenkins orchestration

Status: **IN PROGRESS**

The three-agent Spring PetClinic POC proved the mechanism for:

- independent expected inventory before collection
- deterministic sharding
- parallel execution across three isolated Jenkins agents
- sequential tests inside each collector context
- workspace-relative fragment production
- fragment transport using Jenkins stash/unstash
- fragment join on an explicit agent
- completeness validation against expected tests and shards
- exact revision consistency
- detection of missing, duplicate, and unexpected join input

This evidence does not complete Task 5d. Production storage, retention, lookup, scheduling, publication
policy, failure recovery, and CI-wide configuration remain unimplemented.

TASK 43 officially starts 5d. It adds a versioned orchestration plan, immutable revision-keyed storage,
validated latest-map lookup, idempotent/conflict-safe writes, branch pointers and ancestry-based stale-build
protection in the Jenkins companion repository. Positive non-execution evidence, automatic Pipeline
stash/unstash, direct discovery from preparation, a remote backend, the full failure matrix and fresh
physical validation remain open, so 5d is **IN PROGRESS**.

TASK 44 closes the next 5d production gap: authoritative prepare-owned inventory, positive exact logical execution/non-execution evidence for Gradle and Maven, exact fail-closed join accounting, and plan-owned Jenkins stash/unstash. The existing Docker Jenkins topology validates pinned PetClinic across three physical agents. Durable remote storage, its failure injection, full Spring Core publication, and the final 5d audit remain; 5d therefore stays **IN PROGRESS** and 5e stays independent and untouched.

## Canonical POC conclusion

> Distributed mapping itself did not introduce coverage loss. The only semantic difference was caused
> by an existing run-wide setup attribution defect in the collector.

The evidence is documented in `stp-jenkins-plugin-poc/docs/coverage-mapping-poc.md`: both complete maps
contained the same 58-test inventory, 54 mapped tests, four explicitly skipped tests, 338 test-to-class
edges, and 1,045 test-to-method edges. Setup comparison alone differed: 304 baseline edges versus 301
distributed edges.
Task 30 adds physical Jenkins evidence to 5c/5d: the schema-v2 Gradle ASM path, stash transport, model-aware join, completeness rejection, and single-vs-three-agent semantics are proven in the Docker POC. Statuses remain 5c IN PROGRESS and 5d NOT STARTED — POC proven; 5b is unchanged.

Task 32 closes the Gradle/runtime side of 5c. Inherited lifecycle setup retains concrete-container
ownership; recognized unbounded shared setup and unsupported async ownership fail incomplete without
fabricated edges; generic unattributed/late noise remains nonfatal. Fragment production invalidates old
targets, validates temporary serialized bytes, uses atomic replacement where supported, and is verified
after the Gradle mapping JVM exits. PetClinic retains its 69-test/418-class-edge/1,343-method-edge result
without historical false setup relations. A one-agent Jenkins Spring Core run passes through
`stpCoverageMap` and production `:spring-core:generateSmartTestCoverage` with a fresh completed schema-v2
fragment and clean critical integrity. The sole remaining 5c blocker is Task 33, the Maven schema-v2
fragment adapter; 5c remains **IN PROGRESS**.

Task 33 closes the final adapter blocker. Maven retains its legacy goals and adds
`generate-coverage-fragment`, using authoritative JUnit `MethodSource` identity sidecars plus per-test
JaCoCo report status/XML. JaCoCo XML supplies exact JVM descriptors, so Maven emits descriptor-aware
method edges without converting legacy `Class#method` strings. Setup scopes are deliberately empty:
the Maven source has no honest affected-container ownership. Explicit revision/shard configuration,
shared schema-v2 codec validation, deterministic output, known-test unmapped reporting, and local
artifact integrity gating are proven by the real Maven fixture. Task 5c is **DONE**. Task 5b remains
**NOT STARTED** and Task 5d remains **NOT STARTED — POC proven**.

Canonical post-Task-33 state:

- 5a: **DONE**
- 5b: **IN PROGRESS** — TASK 38 integrated the shared schema-v2 decision across adapters; TASK 39
  implements production inventory but leaves the disabled-test publication transition open.
- 5c: **DONE**
- 5d: **NOT STARTED — POC proven**
- 5e: **NOT STARTED / independent**

The dependency remains `5a -> {5b, 5c} -> 5d`; the next dependent backlog item is 5d.

TASK 39 supplies the production mechanism left after TASK 38: shared discovery-only JUnit inventory generation and
automatic Gradle/Maven provisioning. Exact `MethodSource` identities are deterministic and unsafe
unsupported leaves fail open. The CLI remains explicit-input. See
[`task39-head-inventory-generation.md`](task39-head-inventory-generation.md).

TASK 40 distinguishes logical declarations from executable and runtime-reported populations. An exact,
orchestration-owned signal accounts for intentionally non-executable declarations without turning them
into unsafe executable `UnmappedTest`. Silent missing tests remain incomplete; `ALWAYS_SELECT`, new-test,
setup, and fallback rules are unchanged. PetClinic reconciles 73 logical declarations with 69 mappings
and four intentionally non-executable DB-profile declarations, and no-change returns `NONE` with zero
executions. Maven lifecycle/reactor discovery and TASK 33 sidecar identity equality are validated. See
[`task40-logical-vs-executable-inventory-contract.md`](task40-logical-vs-executable-inventory-contract.md).

TASK 42 validates the complete current workflow on pinned Spring Core. The authoritative 3,643-entry
target inventory publishes completely as 3,638 mapped plus five positively known non-executions;
schema-v2 selection and actual Gradle execution pass every required selected and fail-open scenario.
Two Spring-exposed 5c/adapter regressions were fixed with focused coverage: partial parameterized skips
no longer discard runnable sibling coverage, and `smartTest` inherits the configured target execution
environment. The core is ready for 5d, whose remote store, registry, retention, lookup, scheduling,
and production orchestration remain intentionally unimplemented.

TASK 45 validates the productionized 5d Jenkins orchestration on the same pinned Spring Core revision.
The public plan-v2 steps produce exact `3643 = 3638 mapped + 0 executable-unmapped + 5 positive
non-executed` accounting, automatic fragment/evidence transport, 112 bounded setup scopes, and a
read-back-verified immutable local/reference publication. Edge totals are 45,704 class and 171,910 method
edges, a runtime-sensitive delta of +10/-7 from TASK42 with no logical, setup, transformation-error, or
collision difference. Focused Gradle lifecycle fixes were required. No remote storage, retention, selector,
Maven, or 5e work was added. 5d remains **IN PROGRESS** pending its separate closure audit.
