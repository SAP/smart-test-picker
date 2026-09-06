<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Round 11: Spring Core shared JVM state

## Result

ROUND 11 used exactly the 192 edges classified `UNKNOWN` by ROUND 10. Direct evidence did not satisfy the
`SHARED_JVM_STATE` threshold for any edge, so all 192 remain `UNKNOWN`. This is a negative result, not a heuristic
reclassification: the representative experiment directly rejected the initially likely BridgeMethodResolver cache
reuse explanation, while receiver-specific ownership and observed reclamation remained unavailable for the dominant
ConcurrentReferenceHashMap internals.

Spring Framework remained at `99a366baf6640b275d08dde60f05da719139bb6a`; the task was `:spring-core:test` with
Gradle 8.14.2 and JDK 21. Spring source was not modified. The opt-in diagnostics do not change normal production map
or selector semantics.

## Fixed inventory and state groups

The canonical inventory contains 192 records with canonical TestIdentity, MethodIdentity, ROUND 10 run presence,
and owning class. No edge was added or removed. Of these, 162 own methods in ConcurrentReferenceHashMap, 27 form
the BridgeMethodResolver miss path, and three own SerializableTypeWrapper methods.

The 162 map-internal edges cannot safely be assigned to a particular cache from method names. Exact candidate owners
directly implicated by their test paths include `ResolvableType.cache`, `SerializableTypeWrapper.cache`,
`BridgeMethodResolver.cache`, `GenericTypeResolver.typeVariableCache`, ReflectionUtils' two caches,
`ClassUtils.interfaceMethodCache`, and per-instance `GenericConversionService.converterCache`. Because ROUND 10 did
not capture the receiver, the exact owner remains unresolved for those edges.

There are three UNKNOWN edges whose owning class is ResolvableType. Separately, 67 UNKNOWN edges belong to tests in
`ResolvableTypeTests`; that broader number is reported only as test context and is not treated as proof that
`ResolvableType.cache` owns them.

## Targeted diagnostics

An opt-in JUnit listener recorded only before/after snapshots for seven directly implicated caches. Each record has
event order, source test, field owner, receiver identity, size, and sorted key hashes. A deterministic opt-in class
orderer made the intended test order explicit. A reflection reset and one bounded `System.gc()` control were enabled
only by ROUND 11 properties. Arbitrary object contents were not recorded.

## Controlled order

The representative set contains the three tests responsible for the 27 BridgeMethodResolver miss-path UNKNOWN
edges. Order A ran BridgeMethodResolverTests, MergedAnnotationsComposedOnSingleAnnotatedElementTests, then
MultipleComposedAnnotationsOnSingleAnnotatedElementTests. Order B reversed the outer tests.

In both runs `BridgeMethodResolver.cache` began empty. Each test observed a miss, executed all nine relevant methods,
and inserted a different key hash. The cache progressed 0→1→2→3 in both orders. Thus no earlier selected test warmed
the relevant entry for a later selected test, and all 27 ROUND 10 UNKNOWN edges executed in both contrasts. This
rejects cross-test reuse of the same BridgeMethodResolver entry as their cause; it does not explain why their ROUND
10 presence moved, so they remain `UNKNOWN`.

## Cache reset

Immediately before `MultipleComposedAnnotationsOnSingleAnnotatedElementTests#findMultipleComposedAnnotationsOnBridgeMethod`,
the warm run had BridgeMethodResolver 2, ResolvableType 6, SerializableTypeWrapper 3, and ReflectionUtils methods 4.
The control cleared seven directly implicated caches by reflection, producing size zero before the same target.
All ten ROUND 10 UNKNOWN target methods still executed. Emptying those caches therefore neither removed nor restored
the moving edges and supplies no positive shared-state proof.

## GC and references

Eighty-six inputs involve `restructure`, `SoftEntryReference#get`, `getHash`, or `getNext`, so one bounded forced-GC
control was run before the same target. Three UNKNOWN map internals changed presence relative to the non-GC run, but
the state snapshots observed no reclaimed entry and no purge attributable to reclamation. A forced-GC timing
difference is not proof of soft-reference causality. The answer to whether forced or observed reclamation changed a
ROUND 10 UNKNOWN attribution is therefore: no directly proven case.

## JaCoCo and stability implication

There are no newly proven `SHARED_JVM_STATE` edges to compare. Across the complete 192-edge input, the existing ROUND
9 JaCoCo artifact assigns 11 to the same test, 167 to another concrete test, and lacks or cannot distinguish 14.
JaCoCo's ordinary single assignment has no cache-state or history evidence.

ASM can distinguish a history-dependent edge from a normal dependency only when paired with the targeted runtime
state evidence demonstrated here: exact owner, before/after state, a hit/miss or transition, and a contrasting order.
ASM method entry alone cannot do so, and this round deliberately does not infer such a cause from a cache-shaped
method name. No production policy is proposed.

## Evidence and regression safety

All required JSON artifacts are under `stp-spring-core-spike/round11/evidence/`. The focused order, reset, and GC
runs each completed successfully. The complete STP regression suite also passes. The Spring checkout is clean at the
required revision.

```text
ROUND 11 SHARED JVM STATE

input UNKNOWN edges: 192

SHARED_JVM_STATE: 0
EXECUTION_ORDER: 0
UNKNOWN: 192

CONCURRENTREFERENCEHASHMAP

unknown input: 162
proven shared-state: 0
remaining unknown: 162

RESOLVABLETYPE

unknown input: 3
proven shared-state: 0
remaining unknown: 3

CONTROLLED ORDER

result: 27 representative miss-path edges execute in both explicit orders; each test inserts a distinct key

CACHE RESET

result: all ten target UNKNOWN methods still execute after seven implicated caches are cleared

GC / REFERENCES

result: no observed reclamation changed attribution; three unproved presence differences remain UNKNOWN

JACOCO

history-dependent edges represented as ordinary coverage: 0 newly proven
details: full input is 11 same-test, 167 another-test, 14 absent/not distinguishable

CONCLUSION
Direct state controls reject the most plausible representative cache-reuse mechanism, but do not prove a shared
state cause for any of the 192 inputs. Unresolved receiver identity and unobserved reclamation prevent speculative
classification, so all inputs remain UNKNOWN.
```
