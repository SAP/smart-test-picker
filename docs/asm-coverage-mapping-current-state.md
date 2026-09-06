# ASM coverage mapping: current state

This is the canonical entry point after closure of ASM coverage-attribution research through ROUND 17. It records the final evidence boundary; it does not start selector semantics. Read `docs/asm-research-summary.md` for the complete narrative, `docs/asm-research-documentation-index.md` for history, and `docs/asm-vs-jacoco.md` for the collector comparison.

## Collector support

The experimental shaded Java agent instruments production method entry with ASM, assigns deterministic FNV-1a method IDs, detects collisions, preserves descriptors in its catalog, and streams the final runtime envelope to avoid shutdown memory spikes. It excludes JDK/JUnit/Gradle/STP infrastructure, test and test-fixtures outputs, and recognized generated proxy/auxiliary classes.

JUnit Platform lifecycle events establish a logical `TestIdentity`. Per-test method hits use that logical context, not physical-thread identity. `beforeEach`/`afterEach` execute inside the leaf interval and are attributed to the test; `beforeAll`/`afterAll` remain outside. Once the owner finishes, later hits are quarantined as `LATE_EVENT` and cannot enter normal coverage or leak into the next test.

Supported transformed submission boundaries include common `Thread` constructors, JDK 21 virtual-thread start calls, exact `Executor`/`ExecutorService` and scheduled-executor overloads, executor-style `ForkJoinPool` overloads, and selected `CompletableFuture` Runnable/Supplier/Function stages. Capture/attach/restore is exception-safe and nested. Unsupported boundaries include direct `ForkJoinTask` operations, reflection/method-handle submission, callers loaded before instrumentation, overridden `Thread.run`, and uninstrumented reactive/request-specific handoffs.

## PetClinic evidence

At PetClinic `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, two identical ASM runs mapped the same 25 of 27 fixed identities, 119 class edges, and 239 method edges, with zero repeatability differences, failures, transformation errors, collisions, or unsafe unknown-context events. Six JaCoCo-reference-only constructor edges were directly explained by JaCoCo reset-boundary carryover versus ASM late-event quarantine. The fixed PetClinic inventory did not exercise propagation under an active test, so it proves compatibility and repeatability, not application-specific async propagation.

## Spring Core evidence and topology

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

This is the final evidence boundary. Further progress would require broad exact receiver/reference provenance or repeated target-specific rare-path investigations. No collector correctness defect was found, and no collector, map, selector, or Spring behavior was changed. No further coverage-attribution forensics are recommended before selector work.

## Semantic limitations

Method-entry coverage records execution, not selector meaning. It does not make reflection order deterministic, guarantee completion of arbitrary asynchronous descendants, or infer ownership across unsupported boundaries. Wrapper identity can be visible to custom executors. Periodic tasks retain their captured owner and become late after that owner finishes. `LATE_EVENT` preserves historical ownership but is excluded from normal coverage; correlation alone is not proof that timing caused an unstable edge.

The agent currently carries bounded ROUND 10-15 diagnostics for reproducibility. They are disabled by default and are not all production candidates; their dispositions are in `docs/asm-baseline-status.md`. The standalone research fixture demonstrates the proven reflection-order mechanism without STP, and the upstream text remains a draft only.

Task 5c:
research and semantic hardening complete to final evidence boundary

Task 5c can be considered ready for closure decision.

Recommended next work:
5b Selector semantics

Task 5b remains NOT STARTED.
