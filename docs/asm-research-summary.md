# ASM coverage-mapping research summary

This is the short narrative entry point to the completed ASM attribution research. The precise current contract is in [`asm-coverage-mapping-current-state.md`](asm-coverage-mapping-current-state.md), the chronological evidence trail is in [`asm-research-documentation-index.md`](asm-research-documentation-index.md), and retained implementation/diagnostic dispositions are in [`asm-baseline-status.md`](asm-baseline-status.md).

## 1. Why ASM was investigated

The original STP proof of concept used JaCoCo execution data and reset/dump boundaries to derive per-test maps. ASM was investigated to determine whether STP could instead observe production method entry directly and assign each observation to a controlled logical `TestIdentity`. The aim was explicit attribution semantics, deterministic method identities, and visible handling of work outside a test interval. ASM is not a drop-in JaCoCo replacement: the collectors observe and partition execution differently.

## 2. Final architecture

The experimental shaded Java agent instruments included production method entries. It creates deterministic descriptor-preserving method keys and FNV-1a IDs, detects collisions, and emits a catalog with the runtime result. JUnit Platform lifecycle integration opens and closes the leaf test's logical `TestIdentity`; `beforeEach` and `afterEach` fall inside that interval, while class-level lifecycle work falls outside it.

Logical context is propagated at supported transformed call sites through capture, wrapper execution, and exception-safe restoration. Supported boundaries include common raw and virtual `Thread` entry points, exact `Executor`/`ExecutorService` and scheduled-executor overloads, executor-style `ForkJoinPool` overloads, and selected `CompletableFuture` stages. Direct `ForkJoinTask` operations, reflection/method-handle submissions, overridden `Thread.run`, callers loaded before instrumentation, and uninstrumented reactive or request-specific handoffs remain unsupported. Events observed after their owner finishes are quarantined as `LATE_EVENT`; they neither become normal coverage nor leak to a later test.

## 3. JaCoCo coexistence and comparison

The historical POCs remain evidence for JaCoCo-based per-test mapping. The tested configurations show that existing JaCoCo project coverage can coexist technically with STP instrumentation. That fact is separate from semantic equivalence: a JaCoCo reset/dump interval and an ASM logical lifecycle interval need not own the same work, and their maps are not expected to be bit-identical. JaCoCo is therefore a useful side-by-side diagnostic/reference source, not an absolute oracle for ASM. In Spring Core, large map differences were dominated by attribution redistribution while global unions stayed stable, rather than by simple global collector loss. See [`asm-vs-jacoco.md`](asm-vs-jacoco.md).

## 4. PetClinic results

At PetClinic revision `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, the final two identical Round 8 ASM runs mapped the same 25 of 27 fixed identities, 119 class edges, and 239 method edges. They had zero repeatability differences, test failures, transformation errors, collisions, or unsafe unknown-context events. Six JaCoCo-reference-only constructor edges were explained by reset-boundary carryover versus ASM late-event quarantine. The fixed inventory did not exercise active-test propagation and therefore establishes compatibility and repeatability, not application-specific async completeness.

## 5. Spring Core results

Round 9 established the Spring Core baseline and showed stable global method/class unions alongside moving per-test ownership. Round 10 measured 149,705 stable and 221 unstable distinct per-test method edges across three runs; 29 were proved execution-order-sensitive and the fixed remainder was 192 `UNKNOWN`. Rounds 11–13 tested shared JVM state, object provenance, and a selected cache hit/miss hypothesis; none supplied a positive general cause, and Round 13 directly rejected that selected cache hypothesis. Rounds 14–15 proved that candidate order changed BridgeMethodResolver control flow and traced the first divergence to raw `Class#getDeclaredMethods()` order for the same structural method set.

Round 16 held the historical 192-edge population fixed across five accepted full runs: 78 did not move in that bounded sample, one edge was directly classified `JDK_REFLECTION_ORDER`, and 113 remained moving `UNKNOWN`. Round 17 grouped that 113-edge input without a new full-suite experiment. It extended the already traced reflection-order branch to eight directly gated edges and left 105 unknown.

## 6. Final instability model

Per-test coverage is an observation of a concrete execution, not an immutable semantic truth of a source revision. Valid executions can take different real control-flow paths because execution order, reflection/runtime behavior, caches, scheduling, or other runtime state can differ.

The final Round 17 input and result are:

| Population | Edges |
| --- | ---: |
| Round 16 moving `UNKNOWN` input | 113 |
| Proven JDK reflection-order path | 8 |
| Remaining `UNKNOWN` | 105 |

Its structural population is 84 `ConcurrentReferenceHashMap` internal edges, 28 bridge/reflection-path edges, and one isolated `SerializableTypeWrapper` edge. Structural pattern is not proven causal pattern. In particular, the 84 map-internal edges form operation/co-movement families but lack exact receiver and state-transition evidence.

## 7. Collector correctness conclusion

No collector correctness defect remains known at the final evidence boundary. The retained unknowns are unproved runtime causes, not known collector failures. This conclusion is bounded by the supported propagation and lifecycle contract; it is not a claim of universal async attribution.

## 8. Production candidate and research diagnostics

Production-candidate behavior is the ASM method-entry collector, deterministic catalog/output, logical test ownership, JUnit lifecycle integration, late-event quarantine, supported context-propagation wrappers, filtering, and their regression tests. Round 10–15 tracing transformers, recorders, class ordering/state probes, Spring init scripts, analysis programs, reproducer, and experiment evidence remain research-only. The definitive component table is in [`asm-baseline-status.md`](asm-baseline-status.md).

## 9. Known limitations

- Unsupported async boundaries do not acquire logical ownership.
- Late work is quarantined, and arbitrary descendants are not guaranteed to finish before publication.
- Reflection and other JVM/runtime variability can change genuine control flow.
- Wrappers may be visible to identity-sensitive executors or APIs.
- The map represents only the concrete execution observed; it is not an exhaustive semantic dependency set.

## 10. Final research conclusion

Coverage-attribution research is closed at the current evidence boundary. No further attribution forensics are recommended before selector semantics.

Task 5c: research and semantic hardening complete to final evidence boundary. It is ready for the user's final DONE decision.

Recommended next work: **5b Selector semantics**. Task 5b is **NOT STARTED**.
