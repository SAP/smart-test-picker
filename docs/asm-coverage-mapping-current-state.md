# ASM coverage mapping: current state

This is the authoritative collector-attribution and adoption-boundary entry point,
with attribution findings through MAVEN-ASM-15 and per-identity completion policy
updated by STP-UNMAPPED-01 and its diagnostic-only health amendment. Maven's prepared
schema-v3 path supports ASM; Gradle ASM remains supported. Enabled collection is
not universal qualification for every test topology. The historical ROUND 17
research evidence below is preserved separately from the current Maven findings.
See [current development and coupled Jenkins delivery](current-development-state.md),
[research history](asm-research-summary.md), and
[collector comparison](asm-vs-jacoco.md).

## Current Maven collector boundaries

**A complete map proves identity accounting and structural completeness, not
temporal isolation or attribution soundness.** The current fail-closed checks
reject detected invalid/incomplete evidence; they cannot prove that a
process-global probe snapshot belongs causally to the named test. Successful
test bodies and an accepted map must not be presented as that stronger guarantee.

### Broken identities and assignment absorption

ASM projection preserves passing siblings when a known test fails or has no
finished/result observation. Failed tests are `FAILED`; unfinished or result-less
tests are `COLLECTION_FAILED`; wholly aborted tests remain `SKIPPED`. An agent
error affects only its explicitly owned identities. An unowned diagnostic remains
visible but does not discard other tests' coverage. Uninitialized runtime,
included-class transformation failures and method-ID collisions are informational:
their counters and error strings do not change classification or completion.
There is no agent-error class that invalidates the shard. Legacy `SHARD_FATAL`
labels in diagnostics are producer metadata only, not a publication decision.
Explicitly owned attribution failures affect only those identities.
ERROR setup diagnostics still make collection incomplete,
including the shared-context and parallel cases described below.

For a complete, correctly bound Maven ASM fragment, finalization adds each
unreported member of the authoritative target assignment to unmapped as
`COLLECTION_FAILED`. It does not parse unsupported platform IDs, infer that the
missing test ran, or invent positive non-execution. Unexpected identities,
duplicates, wrong bindings, incomplete fragments and absent whole fragments still
fail. The module fragment and evidence carry an `assignmentAbsorption` audit with
the cause `ASSIGNED_TEST_NOT_REPORTED`, count, exact identities and
`executionObserved=false`. Normalization preserves all reported coverage and
does not rewrite healthy fragments. This Maven audit extension is not retained
by generic fragment/map serialization; the module artifacts are its provenance.

Absorbed identities enter the existing evidence-v2 `EXECUTED` coverage-owner
bucket, because that contract accounts for every non-SKIPPED unmapped owner.
For those identities this bucket is **not proof of physical execution**: consult
`assignmentAbsorption`. `NON_EXECUTED` remains positive observation only.

Unmapped identities present in head are always mandatory for selection, so known
broken and assignment-absent tests run until a later map covers them. This does
not prove that an unowned propagation error left every passing test's edge set
complete: the policy preserves observed sibling coverage, but supplies no new
ownership proof for work that was not attributed. It is not a qualification of
Hybris or a repair for executor/ForkJoin/scheduled propagation. Missing whole
shards still block joining. See the [implementation and validation record](../verification/stp-unmapped-01/REPORT.md).

The [health-policy amendment](../verification/stp-unmapped-01-amendment/REPORT.md)
supersedes that record's retained structural-fatal exceptions. Completion means
accounting, not proof that instrumentation succeeded everywhere: ignoring a
transformation failure or collision preserves observations but does not prove
missing edges harmless. This policy deliberately does not force every sibling
to run on those counters. Artifact, scope, assignment and binding validation
remain separate; “always complete” applies to agent health, not ERROR setup
diagnostics or invalid/missing input artifacts.

### JaCoCo interval attribution and silent loss

The shipped JaCoCo collection path is interval-based.
[JacocoPerTestListener](../smart-test-picker-core/src/main/java/com/sap/oss/smarttestpicker/JacocoPerTestListener.java)
calls `startSession` at `executionStarted` and `snapshotAndReset` at
`executionFinished`. [JacocoExecutionDataSource](../smart-test-picker-core/src/main/java/com/sap/oss/smarttestpicker/JacocoExecutionDataSource.java)
sets/resets the process-global agent and obtains `getExecutionData(true)` from it.
There is no thread filtering. Per-test identity sidecars name the snapshot owner;
they do not isolate the probes that contributed to that snapshot.

Application work caused by one test can continue after its collection window.
When that work executes in the following test's window, JaCoCo attributes it to
the **following test**, not the requesting test. MAVEN-ASM-13 demonstrated exactly
this with real HTTP: the requesting test consumed the complete response, then
server-side work ran during a test that exercised no application code. A
successfully read HTTP response is not evidence that all server-side work has
finished. Work outside every retained window need not be attributed to a following
test; the demonstrated failure is specifically cross-window bleed.

In-JVM parallel tests expose a second failure: overlapping `startSession` calls
reset the global probes. MAVEN-ASM-13 demonstrated and repeated a schedule where
an application method executed, yet disappeared from **every** per-test and setup
session. Tests passed, the map was complete and accepted, and no attribution
warning was raised. **There is no JaCoCo parallel-mapping guard.** The ASM
multiple-fork guard is neither a JaCoCo guard nor an in-JVM concurrency guard.
See [ASM-13 evidence](../verification/maven-asm-13/REPORT.md).

The required assumption for interval attribution is that all application work
caused by a test finishes inside its non-overlapping collection window, with no
unrelated application work or competing reset in that window and setup confined
to its correctly recorded scope. Sequential test execution alone does not prove
this assumption.

On unchanged PetClinic's 69-test H2 scope, both collectors were order-stable
across three method orders (ASM-14) and three class orders (ASM-15), with no leaf
movers. Combined edge-specific changed-successor detection power covered **71 of
74** suspect edges. Three blind edges remain; stable attribution is not proof
of temporal isolation, and this finite result does not undo ASM-13's controlled
counterexamples or qualify historical evaluation numbers. See
[method orders](../verification/maven-asm-14/REPORT.md) and
[class orders and detection power](../verification/maven-asm-15/REPORT.md).

### ASM HTTP ownership and cached contexts

In the measured PetClinic HTTP topology, requests execute on container worker
threads without an active leaf test. Workers capture a JUnit container during
server startup. While that container is open, production hits are retained as
class-level `CONTAINER` setup scopes affecting that test class, not assigned to
the requesting leaf. The current
[SchemaV2TestSelector](../smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/selector/SchemaV2TestSelector.java)
selects **all head tests in each affected container** when a changed class
intersects the scope; the executable selection bridge preserves these scopes.
This is coarser than a leaf edge and conservative in the over-selection direction
for the correctly bounded container. It is not evidence of missing controller
instrumentation or a promise of arbitrary request-specific propagation.
See [ASM-11 routing and selector inspection](../verification/maven-asm-11/REPORT.md).

A cached Spring web context reused across test classes has a different outcome.
MAVEN-ASM-12 demonstrated that prestarted workers retain the first container;
after it closes, another class's HTTP work triggers `ASYNC_SETUP_UNSUPPORTED`
at ERROR severity plus `LATE_EVENT`. Projection sets `collection.completed=false`,
finalization/join reject it, and no usable candidate map is produced. This is
**total collection rejection, not degraded coverage**, reproduced without the
observer and with reversed class order. It is an adoption blocker for this
shared cached-server topology. Its share of real Spring suites is **unknown**;
it does not establish that all Spring Boot suites fail. See
[ASM-12](../verification/maven-asm-12/REPORT.md).

ASM rejection in ASM-13's in-JVM parallel run is **incidental, not a parallel-test
guard**. It resulted from JUnit's own ForkJoin submission being unobserved:
`ASYNC_SETUP_UNSUPPORTED` named an execution without observed submission. It
did not detect parallel tests as such, and `PARALLEL_CONTAINER_SETUP_UNSUPPORTED`
did not fire. Another topology must not rely on that incidental rejection as
a general concurrency safety mechanism.

### Declared but unwired setup concepts

The current runtime collectors have no producer for
`SetupScopeType.SHARED_CONTEXT`, `SetupScopeType.INHERITED_SETUP`, or
`SetupDiagnostic.Kind.PARALLEL_CONTAINER_SETUP_UNSUPPORTED`. The enum values,
codecs and test-created examples do not constitute runtime support.
`beginUnboundedSharedContextSetup` has no production caller; its test callers
do not change that status. If invoked, its current hit path emits
`SHARED_CONTEXT_SETUP_UNSUPPORTED / ERROR` because the affected-container set is
unknown. **Wiring that method alone would still reject collection**, not supply
usable shared-context ownership. This is verified by source references in
`RuntimeContextService`, `AsmCoverageFragmentProjector`, `SetupScopeType`, and
`SetupDiagnostic`, and the ASM-12 audit.

## Open product decisions

These decisions are listed, **not taken** by consolidation:

| Decision | Known evidence and boundary |
| --- | --- |
| Add a fail-closed JaCoCo parallel guard? | ASM-13 proves silent destructive loss. Maven preparation before forks is the proposed integration boundary, with effective configuration considered across supported channels. A throw only from a launcher callback is insufficient because JUnit may log/swallow it; rejection or incomplete evidence must reach finalization. A guard costs parallel mapping throughput and does not solve sequential escaped work. No guard is implemented. |
| Support shared cached web contexts for ASM? | ASM-12 rejects the demonstrated topology. Safe support needs ownership and complete consumer accounting across the shared lifetime. Calling `beginUnboundedSharedContextSetup` alone records an ERROR; suppressing it is not a solution. No adapter or ownership redesign is approved here. |
| Which JaCoCo agent/core/report pair is intended? | The build pins core/report 0.8.13. ASM-13 through ASM-15 used agent 0.8.15 against that pair; ASM-10/12 also used 0.8.15. Older reactor and coexistence fixtures use agent 0.8.13, but the checked-in `shared-spring-web-context` and `http-reset-boundary` fixtures already use 0.8.15. Thus not every checked-in fixture uses 0.8.13. No version is changed or one pair declared the new standard. |
| What may Jenkins claim for HTTP-heavy Spring suites? | ASM-09 qualifies the packaged integration on the existing reactor fixture, not arbitrary Spring HTTP suites. ASM-10/11 are local PetClinic evidence; ASM-12 is a cached-web-context adoption blocker; ASM-13 limits attribution guarantees. Do not advertise universal Spring compatibility, precise HTTP leaf attribution, or soundness merely from an accepted map. Product support policy remains open. |

The S7 ClassSource-only Vintage ASM identity gap and per-fork merging remain
outside this consolidation. No further measurement of the three ASM-15 blind
edges, evaluation harness, dataset or paper change is made here.

## Collector support

The experimental shaded Java agent instruments production method entry with ASM, assigns deterministic FNV-1a method IDs, detects collisions, preserves descriptors in its catalog, and streams the final runtime envelope to avoid shutdown memory spikes. It excludes JDK/JUnit/Gradle/STP infrastructure, test and test-fixtures outputs, and recognized generated proxy/auxiliary classes.

JUnit Platform lifecycle events establish a logical `TestIdentity`. Per-test method hits use that logical context, not physical-thread identity. `beforeEach`/`afterEach` execute inside the leaf interval and are attributed to the test; `beforeAll`/`afterAll` remain outside. Once the owner finishes, later hits are quarantined as `LATE_EVENT` and cannot enter normal coverage or leak into the next test.

Supported transformed submission boundaries include common `Thread` constructors, JDK 21 virtual-thread start calls, exact `Executor`/`ExecutorService` and scheduled-executor overloads, executor-style `ForkJoinPool` overloads, [application ForkJoinTask submission/execution](asm-forkjoin-task-propagation.md), and selected `CompletableFuture` Runnable/Supplier/Function stages. Capture/attach/restore is exception-safe and nested. Unsupported boundaries include uninstrumented ForkJoinTask execution, ambiguous concurrent task reuse, reflection/method-handle submission, callers loaded before instrumentation, overridden `Thread.run`, and uninstrumented reactive/request-specific handoffs.

## Historical Gradle PetClinic evidence

This earlier 27-identity research scope is not the later Maven 69-test H2 scope.

At PetClinic `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, two identical ASM runs mapped the same 25 of 27 fixed identities, 119 class edges, and 239 method edges, with zero repeatability differences, failures, transformation errors, collisions, or unsafe unknown-context events. Six JaCoCo-reference-only constructor edges were directly explained by JaCoCo reset-boundary carryover versus ASM late-event quarantine. The fixed PetClinic inventory did not exercise propagation under an active test, so it proves compatibility and repeatability, not application-specific async propagation.

## Historical Spring Core evidence and topology

The Spring subject is revision `99a366baf6640b275d08dde60f05da719139bb6a`, task `:spring-core:test`, Gradle 8.14.2, JDK 21.0.11, one normal test task and a fresh worker JVM for every accepted full run. Spring source remained unchanged. ROUND 9 proved that Spring exercises active-context propagation and that global method/class unions can remain stable while per-test ownership moves.

ROUND 10 measured 149,705 stable and 221 unstable distinct per-test method edges across three runs (0.147406% unstable). Controlled order contrasts proved 29 of those edges `EXECUTION_ORDER`; the remaining fixed set contained 192 `UNKNOWN` edges. No member of that set was proven async-caused, late-execution-caused, or static-initialization-caused.

ROUND 11 found no directly proven shared-JVM-state edge among the 192. ROUND 12 object provenance did not close a causal chain. ROUND 13 directly rejected the selected BridgeMethodResolver cache hit/miss hypothesis. ROUND 14 proved candidate order changes the selected BridgeMethodResolver control path. ROUND 15 located its first cause at differing raw `Class#getDeclaredMethods()` order for the same structural method set, propagated by `ReflectionUtils` into `BridgeMethodResolver`.

## ROUND 16 full-run repeatability

Five accepted, identically configured full runs each passed and produced 3,638 logical identities, of which 3,629 mapped production methods. Across their union were 150,291 distinct per-test method edges: 149,374 appeared in all five and 917 did not, an unstable percentage of 0.610150%.

This is not labeled an improvement or regression versus ROUND 10: a five-run union has more opportunities to expose rare movement than a three-run union, so the sampling cardinality is not directly comparable even though the subject and launch configuration match.

The exact historical 192-pair input is preserved with a canonical hash. ROUND 16 classifications are:

| Classification | Edges |
| --- | ---: |
| JDK_REFLECTION_ORDER | 1 |
| SPRING_ORDER_SENSITIVE_CONTROL_FLOW | 0 |
| EXECUTION_ORDER | 0 |
| SHARED_JVM_STATE | 0 |
| STATIC_INITIALIZATION | 0 |
| LATE_EXECUTION | 0 |
| ASYNC_CONTEXT | 0 |
| OTHER_PROVEN_RUNTIME_CAUSE | 0 |
| STABLE_IN_ROUND16 | 78 |
| UNKNOWN | 113 |

The proven edge is `BridgeMethodResolverTests#withGenericParameter_5de9f312 -> ResolvableType#getInterfaces`, present with vector `[1,0,1,1,1]`. Its direct chain is raw declared-method order, preserved ReflectionUtils/candidate order, differing first-candidate decision, and interface-resolution presence/absence. No sibling edge is automatically promoted from association. Of the 78 stable-in-round edges, some were always present and some always absent; this category means only that historical movement was not reproduced in the bounded five-run sample. The 113 moving edges remain `UNKNOWN`; cache-family and reflection-related names are hints, not causal classifications.

## ROUND 17 grouped causal characterization

ROUND 17 preserved the exact 113 ROUND 16 `UNKNOWN` pairs as immutable input and assigned each to one of five exclusive causal-analysis groups. The population is structurally concentrated in 84 `ConcurrentReferenceHashMap` internal edges, 28 bridge/reflection-path edges, and one isolated `SerializableTypeWrapper#unwrap` edge. Existing five-run vectors already reproduced every input edge, so no new full-suite run was needed.

The map population split into 69 capacity/restructure edges and 15 soft-reference traversal edges. Twenty-two same-test `getLoadFactor` / `createReferenceArray` / `restructure` triplets move as internal operation clusters. That is a structural and co-movement result, not a causal cache-owner result: retained evidence does not connect the exact invocations to a concrete receiver, occupancy transition, resize versus purge reason, or cleared reference. Prior cache-reset and GC evidence remains negative or non-probative. Those 84 edges remain `UNKNOWN`.

ROUND 17 identified one additional recurring causal pattern within the fixed input. The already-proven `withGenericParameter` raw `Class#getDeclaredMethods` order event directly gates the whole traced generic/interface-resolution branch, not only `ResolvableType#getInterfaces`. Retained present/absent maps show eight ROUND 17 input edges on that same branch moving together. Those eight are now `JDK_REFLECTION_ORDER`. The superficially similar 20-edge annotation group remains `UNKNOWN` because it has no target-connected producer-order trace; shared methods and an equal presence vector are not generalized as cause. The isolated unwrap edge also remains unknown.

Final ROUND 17 accounting is 8 `JDK_REFLECTION_ORDER`, 105 `UNKNOWN`, and zero in every other allowed category. One group has a proven shared cause and four groups have insufficient evidence. One shared pattern explains 8/113 edges (7.08%), so the remaining instability did not materially collapse into a small set of recurring proven mechanisms.

This was the final evidence boundary of the ROUND 17 investigation. Further progress would require broad exact receiver/reference provenance or repeated target-specific rare-path investigations. No collector correctness defect was found in that investigation, and no collector, map, selector, or Spring behavior was changed there. Its recommendation against further attribution forensics before selector work is historical, not a statement that the later Maven findings above do not exist.

## Semantic limitations

Method-entry coverage records execution, not selector meaning. It does not make reflection order deterministic, guarantee completion of arbitrary asynchronous descendants, or infer ownership across unsupported boundaries. Wrapper identity can be visible to custom executors. Periodic tasks retain their captured owner and become late after that owner finishes. `LATE_EVENT` preserves historical ownership but is excluded from normal coverage; correlation alone is not proof that timing caused an unstable edge.

The agent currently carries bounded ROUND 10-15 diagnostics for reproducibility. They are disabled by default and are not all production candidates; their dispositions are in `docs/asm-baseline-status.md`. The standalone research fixture demonstrates the proven reflection-order mechanism without STP, and the upstream text remains a draft only.

### Historical ROUND 17 closure recommendation

The following task statuses describe that research checkpoint, not the current
implementation or the open Maven product decisions above.

Task 5c:
research and semantic hardening complete to final evidence boundary

Task 5c can be considered ready for closure decision.

Recommended next work:
5b Selector semantics

Task 5b remains NOT STARTED.
