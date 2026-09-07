# TASK 31 — Task 5c closure audit

## Decision

Task 5c remains **IN PROGRESS** with two closure tasks covering three true implementation gaps:

1. production detection and safe degradation for ownership-sensitive setup/execution that cannot be
   bounded to a test or container, plus fail-closed fragment replacement/write behavior; and
2. schema-v2 fragment production through the supported Maven adapter.

No Gradle collection, fragment-schema, inventory, merge, publication, selector, or Jenkins
implementation blocker remains. Task 5d remains **NOT STARTED — POC proven** and Task 5b remains
**NOT STARTED**.

## Reconstructed 5c contract

| 5c responsibility | Why it belongs to 5c | Current implementation and evidence | Status |
|---|---|---|---|
| Observe production execution and attribute method entries to logical tests | This is collector runtime reality, before merge or selection | `stp-agent`, `stp-runtime`, and `stp-junit-adapter`; Tasks 24–25 fixtures and Tasks 28–30 PetClinic runs | Proven for synchronous JUnit and instrumented supported async boundaries; one safe-degradation blocker remains |
| Produce exact stable identities | A fragment cannot safely express coverage without stable test and method keys | Schema-v2 `TestIdentity` retains declared parameter types and `MethodIdentity` retains JVM descriptors; overload, parameterized/dynamic, bridge/synthetic, and PetClinic evidence in Tasks 25 and 28 | Complete |
| Represent bounded setup ownership without fabricated edges | Setup executed outside a leaf still affects selection inputs and is collector-observed reality | Thread-local JUnit class/nested-container intervals, including `BeforeAll`/`AfterAll`; Task 25 fixtures and Tasks 28–30 single/multi equality | Complete for supported bounded setup; unsupported-pattern detection is incomplete |
| Gate local collector integrity | Only the collector can say whether its own observations/projector completed safely | `CollectorIntegrity`, projector completion gating, typed setup errors, unmapped `COLLECTION_FAILED`, and agent output handling | Complete for known signals; ownership-sensitive unattributed events are not yet promoted to a known signal |
| Emit a deterministic revision- and shard-bound schema-v2 fragment | This is the collector/build-adapter deliverable consumed by 5d | `CoverageFragmentCodec`, `AsmCoverageFragmentProjector`, agent output, Gradle ASM backend; Tasks 24–30 | Complete for Gradle; absent from Maven |
| Attach collection through supported build-tool adapters | A runtime that cannot be invoked by a supported adapter does not complete that adapter's fragment-production path | Gradle ASM/default and JACOCO/fallback are proven; Maven retains its legacy JaCoCo map path | Maven blocker |

Expected inventory, shard assignment, transport, merge, global completeness, and publication are not
5c. Selector lookup/fallback is not 5c.

## Already proven

- Schema v2 is authoritative in `smart-test-picker-common`; no compatibility layer is needed.
- Gradle defaults to ASM and supports explicit JACOCO fallback through one backend boundary.
- Gradle automatically resolves the aligned external agent and coexists with project JaCoCo.
- ASM emits deterministic schema-v2 fragments with explicit revision and shard identity.
- Production method identities retain JVM descriptors and logical test identities retain declared
  test signatures while collapsing invocations of the same logical method.
- Bounded class/nested-container setup is safe, `AfterAll` does not leak to the last leaf, parallel
  containers do not share a global owner, and single/multi collection is semantically equal.
- PetClinic at `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` maps all 69 runnable cases with
  418 class edges, 1,343 method edges, ten setup scopes, and none of the historical unsafe relations.
- Task 30 proves three physical Jenkins agents, stash/unstash, schema-v2 decoding/join, global
  completeness rejections, and exact single/three-agent equality. These are 5d POC evidence.

## Candidate classification

### Inherited setup — `BLOCKER_FOR_5C_DONE` (narrow safe handling only)

The concrete case is a lifecycle method declared by a superclass or JUnit interface and executed for
a subclass/container, such as inherited `@BeforeAll`. Today the JUnit listener opens the concrete
`ClassSource` container, so synchronous method hits are bounded to that container and often happen to
produce a safe `CONTAINER` scope. It does not inspect lifecycle declaration/ownership, emit
`INHERITED_SETUP`, or call `recordUnsupportedSetup`. Thus there is no production proof that all
inherited forms either attribute correctly or fail incomplete. The projector test proves only that a
manually supplied typed error makes the fragment incomplete. Minimum closure is a focused JUnit
fixture plus adapter/runtime classification: retain the bounded concrete-container edge when its
affected set is known; otherwise emit `INHERITED_SETUP_UNSUPPORTED` as `ERROR` and no guessed edge.

### Shared setup — `BLOCKER_FOR_5C_DONE` (safe detection, not broad framework support)

The concrete case is one framework-owned fixture/context initialized once but consumed by multiple
JUnit containers, for example a cached Spring test context. A synchronous initialization hit is
currently attributed only to the container whose callback happens to trigger it. No production code
emits `SHARED_CONTEXT_SETUP_UNSUPPORTED`, so later consumers can be absent from the affected set while
the fragment remains complete. The existing projector test proves gating after a diagnostic is
provided, not detection. Minimum closure is detection at the supported JUnit/framework boundary and
either an exact bounded affected-container set or an error diagnostic and incomplete fragment. It
does not require generalized Spring/JUnit context modeling.

### Async setup/execution — `BLOCKER_FOR_5C_DONE` (safe degradation gap)

Supported call sites already propagate the submitting leaf context through executors, scheduled
executors, common `CompletableFuture` forms, fork/join forms, raw `Thread` constructors, and JDK 21
virtual-thread APIs. Tests prove exact attribution, reuse cleanup, nesting, failure identity, and no
pool leakage. Work executed after lifecycle completion is recorded as `LATE_EVENT`; unpropagated work
is `NO_ACTIVE_TEST`. Neither signal enters an edge, which avoids false positive attribution.

The remaining issue is false-negative safety: application method hits that are demonstrably tied to
unsupported async work or setup can currently coexist with `collectionCompleted=true` because generic
`NO_ACTIVE_TEST` and `LATE_EVENT` are diagnostic-only. Not every no-active hit is unsafe—PetClinic has
46 benign startup/lifecycle observations—so making all such events fatal is not justified. Minimum
closure is to classify ownership-sensitive cases at the lifecycle/submission boundary and either
attribute them correctly or emit an error (`ASYNC_SETUP_UNSUPPORTED` or equivalent local integrity
failure) that makes the fragment incomplete. Reactive/request propagation and every concurrency API
remain hardening.

### Maven integration — `BLOCKER_FOR_5C_DONE`; `SEPARATE_5C_SUBTASK_AFTER_GRADLE`

Repository architecture lists Maven as a user-facing supported adapter; the Task 26 sequence places
Maven wiring/end-to-end validation after Gradle and before completing 5c; the follow-up backlog has
consistently retained Maven under 5c. Today `GenerateCoverageMapMojo` consumes per-test JaCoCo XML and
writes the legacy map, not a schema-v2 fragment. Therefore the whole repository's 5c adapter contract
is incomplete even though Gradle 5c has no adapter blocker.

Minimum Maven acceptance is one production Maven mapping path that emits a deterministic schema-v2
`CoverageFragment` with explicit revision/shard, exact `TestIdentity`, honest method identity (exact
descriptors when available; omission rather than guessing otherwise), bounded setup only when known,
unmapped reasons, local integrity completion, and a real Maven fixture decoded by the common codec.
The audit does not select ASM, change Maven behavior, or require parity with Gradle internals.

### Inventory-discovery boundary — `ALREADY_COMPLETE`

The collector needs no expected inventory. It reports mapped and unmapped tests it actually observed
and its local completed state. A build adapter must pass collection configuration and expose the
fragment artifact; it may expose discovery as an orchestration facility but must not synthesize
expectation from collector output. Jenkins/orchestration owns independent discovery of what should run,
disabled/runnable policy, deterministic assignment, expected shards, and the revision expectation.
Task 30 proves this split with 73 discovered, 69 expected runnable, and 32/12/25 shards.

## Setup scenario audit

| Scenario | Current behavior | Existing proof | Required for 5c |
|---|---|---|---|
| Direct `BeforeAll`/`AfterAll` | Supported in bounded class-container interval | Task 25 lifecycle fixtures | Complete |
| Superclass/interface lifecycle | Often bounded to concrete container, but declaration/unsupported forms are not classified | No production detection fixture found | Yes: test plus safe classification |
| Nested containers | Supported with binary nested container scope | Task 25 fixtures and PetClinic nested tests | Complete |
| Parallel containers | Independent thread-local stacks; unowned events quarantined | Task 25 parallel fixture | Complete for callback-bounded work |
| Shared cached fixture/context | First triggering container may receive the only edge; consumers are not discovered | Only manual diagnostic gating test | Yes: detection and fail-incomplete, not generalized modeling |
| Parameterized/repeated/dynamic leaves | Invocation identities collapse by declared source; dynamic factory source recovered where JUnit supplies it | Task 25 fixtures | Complete; unknown source fails incomplete |
| Supported executor/future/thread work | Submission context propagated and restored | Agent transformation fixtures | Complete |
| Late or unsupported asynchronous work | Quarantined as `LATE_EVENT`/`NO_ACTIVE_TEST`, but not necessarily incomplete | Agent fixtures prove diagnostics, not fragment gating | Yes: ownership-sensitive safe degradation |

## Collector integrity audit

| Signal | Current boundary | Classification |
|---|---|---|
| Agent errors | `CollectorIntegrity.criticalFailure`; identifiable tests become unmapped and fragment incomplete | Fatal |
| Transformation failures | Counted and critical | Fatal |
| Method-ID collisions | Counted and critical; ambiguous hit is not attributed | Fatal |
| Runtime initialization failure | Agent startup fails; no trustworthy fragment is emitted | Fatal/no artifact |
| Fragment serialization/write failure | Caught only in the shutdown hook; the JVM may still succeed, and a pre-existing target is not invalidated before projection/write | Intended fatal, but stale-artifact safety gap |
| Unsupported logical identity | Projector diagnostic and incomplete fragment; no identity is invented | Safe degradation |
| Setup attribution error | Error-severity `SetupDiagnostic` makes fragment incomplete | Fatal when detected |
| `NO_ACTIVE_TEST` | No edge; global diagnostic; does not itself gate completion | Diagnostic; unsafe only when it represents ownership-sensitive lost work |
| `LATE_EVENT` | No edge; attached diagnostic on the finished test; does not gate completion | Diagnostic; unsafe when late work is part of the test's intended execution |

An unsafe fragment **can currently report `collectionCompleted=true`** when unsupported inherited,
shared, or async ownership is not recognized and therefore never becomes an error diagnostic. There
is also an artifact-level variant: fragment projection/write failure is caught without failing the JVM
and does not first invalidate an existing output, so a stale previously completed fragment can remain
at the configured path. For recognized pre-projection integrity signals, gating is sufficient.

## Fragment contract audit

`CoverageFragment` contains schema version, revision, shard ID, mapped tests, explicit unmapped tests,
setup scopes, and shard-local collection completion. `TestCoverage` contains class and descriptor-aware
method edges, outcome, and collection status. Constructors, validation, sorting, and the codec preserve
stable identities, mapped/unmapped disjointness, deterministic output, setup IDs, and explicit
revision/shard binding. Tasks 28–30 prove production encoding and downstream model-aware join.

The contract is sufficient for 5c and for downstream 5d merge/completeness. No field is missing.
Expected tests, expected shards, global completeness, diagnostics/provenance, and publication lifecycle
must not be added to the fragment.

## Provisional markers

| Marker | Location | Still justified | Recommendation |
|---|---|---:|---|
| `PROVISIONAL collector contract pending producibility confirmation in backlog 5c` | `CoverageFragment` | No | Tasks 24–30 prove production; remove mechanically after this audit or with final 5c cleanup |
| `PROVISIONAL schema-v2 fragment codec` | `CoverageFragmentCodec` | No | Production Gradle and Jenkins decode/join prove it; remove mechanically with contract cleanup |
| `Experimental` aggregator wording | `RuntimeEventAggregator` | Yes for now | Runtime remains internal and the two ownership blockers remain |

No marker is removed by Task 31.

## Backlog ownership matrix

| Capability | 5b | 5c | 5d | Other |
|---|---:|---:|---:|---:|
| Runtime attribution |  | owner |  |  |
| Fragment encoding |  | owner |  |  |
| Setup semantics |  | owner |  |  |
| Collector integrity |  | owner |  |  |
| Expected inventory |  |  | owner | discovery supplies input |
| Shard assignment |  |  | owner |  |
| Fragment transport |  |  | owner |  |
| Fragment merge |  |  | owner |  |
| Global completeness |  |  | owner |  |
| Publication |  |  | owner |  |
| Storage/retention |  |  | owner |  |
| Selector fallback | owner |  |  |  |
| Maven adapter |  | owner |  | adapter implementation |

## Minimal remaining 5c plan

### TASK 32 — Fail closed for unsupported ownership and fragment-output failure

- **Why required:** ownership-sensitive lost work can currently be diagnostic-only while the fragment
  reports complete, and a caught fragment write failure can leave a stale completed artifact.
- **Scope:** add focused inherited lifecycle, shared fixture/context, unpropagated async setup, and
  late-work fixtures; classify each at the narrowest JUnit/runtime/agent boundary; attribute only with
  a proven bounded owner, otherwise emit an error and incomplete fragment. Make fragment replacement
  atomic/fail-closed and ensure failure cannot leave a consumable stale target or a successful mapping
  task that appears to have produced a fresh fragment.
- **Acceptance:** direct and inherited lifecycle cases with known concrete ownership have exact edges;
  unsupported shared/async cases create no guessed edge, carry a typed diagnostic, and serialize
  `collection.completed=false`; benign startup `NO_ACTIVE_TEST` remains nonfatal; simulated projection
  and write failures cannot expose an old completed fragment as new output; existing executor,
  parallel-container, PetClinic setup, and single/multi semantics remain unchanged.
- **Modules:** `stp-junit-adapter`, `stp-runtime`, and only if submission classification requires it,
  `stp-agent`.
- **Must not change:** schema v2, global expectation, selector policy, collector choices, merge,
  publication, Maven, or generalized framework/reactive propagation.
- **Dependency:** Tasks 24–30 and this audit.
- **Conceptual size:** MEDIUM.

### TASK 33 — Maven schema-v2 fragment adapter

- **Why required:** Maven is a supported build-tool adapter but cannot produce the authoritative 5c
  artifact.
- **Scope:** choose and implement the smallest honest Maven collection-to-fragment path and expose
  explicit revision/shard/output configuration.
- **Acceptance:** a real Maven fixture produces deterministic schema-v2 decoded by
  `CoverageFragmentCodec`, with exact tests/outcomes, honest descriptor/setup capability behavior,
  unmapped reporting, and local integrity gating; repeated output is identical.
- **Modules:** `smart-test-picker-maven`, plus existing common/runtime/agent artifacts only as required
  by the chosen design.
- **Must not change:** Gradle backends, selector semantics, schema, 5d merge/publication, Maven test
  filtering, or introduce guessed JVM descriptors/setup edges.
- **Dependency:** TASK 32 only if Maven reuses its newly closed runtime safety contract; otherwise it
  may proceed independently, but both must finish before 5c closes.
- **Conceptual size:** MEDIUM.

After Tasks 32 and 33, remove the two obsolete provisional comments as mechanical documentation
cleanup and mark 5c DONE if their acceptance evidence passes. Framework-general shared-context
modeling, broader async/reactive propagation, additional framework adapters, and richer diagnostics
are hardening after 5c. Production transport, merge, completeness, publication, storage, retention,
scheduling, and failure recovery remain 5d.
