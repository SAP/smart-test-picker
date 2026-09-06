# ROUND 17 Spring Core grouped causal analysis

## Baseline and fixed input

ROUND 17 starts at STP commit and tag target `67ce76bb877e6aa87f34bd3be87677532f2206d0` on `research/asm-codex`. The Spring subject remains `99a366baf6640b275d08dde60f05da719139bb6a`, task `:spring-core:test`, Gradle 8.14.2, and JDK 21.0.11. The baseline worktree was clean and the large-file check passed.

The input is selected only from entries classified exactly `UNKNOWN` in `round16-causal-classification.json`. It contains 113 unique `(TestIdentity, MethodIdentity)` pairs, matching the canonical source count. The canonical SHA-256 over the sorted compact JSON pairs is recorded in `round17-input-unknown.json`. No historical map was used to reconstruct membership.

No new full-suite run was needed: all 113 input edges already move in the five accepted identical fresh-JVM ROUND 16 runs. Those signatures are reused only for reproduction and grouping, never as causal proof.

## Group model and priority

Every input edge has exactly one primary group.

| Group | Description | Edges | Tests | Result |
| --- | --- | ---: | ---: | --- |
| G01 | `ConcurrentReferenceHashMap` restructure/capacity operations | 69 | 25 | INSUFFICIENT_EVIDENCE |
| G02 | `ConcurrentReferenceHashMap` soft-reference traversal | 15 | 12 | INSUFFICIENT_EVIDENCE |
| G03 | traced `withGenericParameter` reflection-order path | 8 | 1 | GROUP_CAUSE_PROVEN |
| G04 | correlated annotation bridge-resolution paths | 20 | 2 | INSUFFICIENT_EVIDENCE |
| G05 | isolated `SerializableTypeWrapper#unwrap` | 1 | 1 | INSUFFICIENT_EVIDENCE |

Priority was G01, G03, G04, G02, then G05. G01 has the highest potential population impact; G03 had the strongest retained direct evidence; G04 is large and perfectly correlated but lacked a target-specific producer trace; G02 required exact reference identity that prior work did not capture; G05 could explain only one edge.

The JSON group records deliberately separate three concepts:

- `STRUCTURAL_GROUPING` says why methods plausibly share a mechanism.
- `OBSERVED_COMOVEMENT` records equal five-run signatures.
- `CAUSAL_EVIDENCE` states what direct trace, if any, connects a runtime event to coverage.

## G01: map restructure and capacity operations

This group contains 22 exact same-test triplets of `getLoadFactor`, `Segment#createReferenceArray`, and `Segment#restructure`, plus three restructure-only edges. Each triplet moves as one internal resize-shaped operation cluster; the 66 method-entry edges are not treated as 66 independent phenomena.

The retained evidence cannot determine which concrete cache owns these exact invocations. It has no exact map/segment identity, before/after occupancy, table size, restructure reason, or relevant reference identity. Consequently:

- concrete owner: not established;
- GET hit versus miss: not observed for the exact operations;
- occupancy correlation: not observed;
- restructure correlation: yes at method-entry level, but resize versus purge is unresolved;
- purge or cleared-reference correlation: not observed;
- multi-edge operation clusters: 22 triplets directly observed by identical signatures and call-path shape;
- prior-test trigger: not established;
- controlled warm/cold or order change: not established for these exact edges.

ROUND 11's cache-reset contrast did not change its representative unknown edge, its forced-GC run observed no relevant reclamation, and its exact-population method events lacked receiver identity. These are binding negative/limiting results. They prevent a shared-state or reference-lifecycle claim, but do not establish another cause. Population-wide exact receiver provenance would be new invasive infrastructure, so G01 stops as `INSUFFICIENT_EVIDENCE` and its 69 edges remain `UNKNOWN`.

## G02: soft-reference traversal

Three same-test `getHash` / `getNext` pairs move together. Other `get`, `getHash`, and `getNext` edges have distinct test-local signatures. This is compatible with ordinary bucket traversal, collision/occupancy differences, or reference lifecycle; it proves none of them.

ROUND 11's forced-GC contrast observed no reclamation of a relevant reference. ROUND 12 showed that generic allocation/old-object sampling could not establish provenance. No retained trace follows one exact relevant reference from present through cleared/purged to a changed target path. Therefore zero edges meet the new `REFERENCE_LIFECYCLE` threshold. G02 is `INSUFFICIENT_EVIDENCE`; all 15 remain `UNKNOWN`.

## G03: proven reflection-order path

This group extends an existing direct causal trace only to edges covered by that very same traced branch. It does not generalize from a representative edge.

ROUND 15 fresh-JVM runs 1 and 5 observed the same structural three-method set returned by `Class#getDeclaredMethods`, with the two non-bridge candidates swapped. ReflectionUtils preserved the order into callback traversal and `BridgeMethodResolver` candidate insertion. ROUND 14 located the first different decision: with the Class candidate first, direct resolution succeeds and returns; with the Integer candidate first, resolution enters the generic/interface path before evaluating the Class candidate.

The retained run-1 and run-5 per-test maps show all eight G03 input edges absent on the early-return path and present on the generic/interface path. Thus one observed raw-order event directly gates the eight-edge cluster:

```text
Class#getDeclaredMethods order
-> ReflectionUtils callback order
-> BridgeMethodResolver candidate order
-> early return or generic/interface resolution
-> eight method-entry edges absent or present together
```

All eight are classified `JDK_REFLECTION_ORDER` under P01. This scope is only `BridgeMethodResolverTests#withGenericParameter_5de9f312` and the listed edges.

## G04: correlated annotation bridge paths

Two annotation tests each have a ten-method path, and all 20 edges share `[1,0,1,1,1]`. ROUND 11 rejected cross-test BridgeMethodResolver cache reuse for its selected miss-path edges, and ROUND 13 rejected cache hit/miss as the selected moving edge's cause. No retained raw reflection result or first-decision trace is connected to either G04 test.

The similarity to G03 makes reflection order a candidate, not a classification. Equal vectors, method families, and downstream shape cannot prove that the same producer varied. G04 is `INSUFFICIENT_EVIDENCE`; all 20 remain `UNKNOWN`.

## G05: isolated unwrap

The lone `SerializableTypeWrapper#unwrap` edge has vector `[1,0,0,0,0]`. ROUND 11 obtained no contrasting hit/miss proof for this family. A dedicated investigation could explain at most one edge, so the bounded information-gain rule stops it as `INSUFFICIENT_EVIDENCE` and `UNKNOWN`.

## Causal pattern catalog

### P01 — Reflection result order gates a multi-method BridgeMethodResolver path

Producer: `Class#getDeclaredMethods`.

Consumer: ReflectionUtils traversal followed by BridgeMethodResolver insertion-order candidate search.

Trigger and mechanism: the same structural method set arrives with two candidates swapped. Spring preserves that order. One candidate returns directly; the other enters generic/interface resolution first.

Coverage consequence: eight G03 method entries move together as one downstream path.

Controlled proof: ROUND 15 fresh-JVM runs 1 and 5 provide the raw-order and map contrast; ROUND 14 provides the first differing decision and path.

Scope limit: G03 only. In particular, the pattern is not assigned to G04 without a producer trace for those tests.

## Final accounting and evidence boundary

| Classification | Edges |
| --- | ---: |
| JDK_REFLECTION_ORDER | 8 |
| SPRING_ORDER_SENSITIVE_CONTROL_FLOW | 0 |
| EXECUTION_ORDER | 0 |
| SHARED_JVM_STATE | 0 |
| REFERENCE_LIFECYCLE | 0 |
| STATIC_INITIALIZATION | 0 |
| LATE_EXECUTION | 0 |
| ASYNC_CONTEXT | 0 |
| OTHER_PROVEN_RUNTIME_CAUSE | 0 |
| NOT_REPRODUCED_ROUND17 | 0 |
| UNKNOWN | 105 |
| **Total** | **113** |

One group has one proven shared cause; four are insufficient. Eight edges are explained by a group-level pattern, none individually, and 105 remain unknown.

The answer to whether the remaining instability collapsed into a small number of recurring mechanisms is **NO**. One mechanism explains 8/113 edges (7.08%); 105/113 remain unknown across four groups. The 84 map-internal edges do collapse structurally into operation families, but without receiver/state evidence that is not a causal collapse.

No collector correctness defect was found. No collector, production-map, selector, or Spring semantics were changed. Further progress would require broad exact receiver/reference provenance or repeated target-specific rare-path tracing, which meets the final-round stop condition. No further coverage-attribution forensics are recommended before selector work.
