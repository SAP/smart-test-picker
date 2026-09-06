<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Round 13: deterministic Spring Core cache tracing

## Result

ROUND 13 replaced the ROUND 12 sampled evidence with property-gated ASM receiver and operation tracing for exactly
three ROUND 10 `UNKNOWN` edges. Four identical focused JVM executions were needed. The direct
`ResolvableType#getInterfaces` edge for `BridgeMethodResolverTests#withGenericParameter_5de9f312` was present in runs
1 and 3 and absent in runs 2 and 4. The final controlled comparison uses runs 3 and 4, which share the finalized trace
schema. The two map-internal targets did not reproduce.

The fixed subject was Spring Framework revision `99a366baf6640b275d08dde60f05da719139bb6a`, task
`:spring-core:test`, Gradle 8.14.2, and OpenJDK 21.0.11. Spring source was not changed.

## Instrumentation and identity limits

The ROUND-13-only transformer is enabled only when `stp.round13.trace.output` is set. It instruments the selected
Spring classes and relevant `ConcurrentReferenceHashMap` operations. Normal method coverage, selector behavior, and
map behavior remain unchanged when the property is absent. The hook catches diagnostic failures at the application
boundary.

Each event records one per-JVM atomic sequence, `System.nanoTime`, `TestIdentity`, thread, method, operation, concrete
receiver class and `System.identityHashCode`, key class/identity/supplemental hash where applicable, resolved cache
owner, segment/reference identity, and safely observable state. Receiver identities are used only inside one JVM.
No cross-JVM sameness is inferred even where numeric identity hashes happen to match.

At `TEST_START`, the diagnostic reads the seven requested static fields and correlates their actual runtime values.
For each `ConcurrentReferenceHashMap`, its `segments` array maps each concrete Segment to the parent map. Observed
reference chains map entry references to segments. This yields the runtime chain `field -> map -> Segment -> reference`
without owner inference from method names.

`GET_MISS` is emitted only when `getReference` returns null; `GET_HIT` only when a reference or map result is non-null.
`PUT_NEW` versus `PUT_REPLACE` uses the observed count delta, avoiding ambiguity from nullable values. Restructure
labels use before/after table and reference counts. Soft-reference presence is observed with `Reference.get()` only;
the tracer does not clear, enqueue, retain beyond the hook call, or serialize referents.

## Targets and reproduction

The selected targets are the first three ROUND 12 targets:

1. `ResolvableTypeTests#classWithGenericsAs_1378ee35` -> `ConcurrentReferenceHashMap$Segment#restructure`
2. `ResolvableTypeTests#forMethodParameterWithNestingAndLevels_3ac7fcf8` -> `ConcurrentReferenceHashMap$SoftEntryReference#get`
3. `BridgeMethodResolverTests#withGenericParameter_5de9f312` -> `ResolvableType#getInterfaces`

All three selected Spring tests passed in every focused run. Target presence by run was `[absent, absent, present]`,
`[absent, absent, absent]`, `[absent, absent, present]`, and `[absent, absent, absent]`. Execution stopped after run 4
because finalized-schema runs 3 and 4 supplied a present/absent contrast. The maximum of five was not exhausted.

## Present versus absent causal comparison

For the moving target, both run 3 and run 4 start the selected test with `BridgeMethodResolver.cache` count zero and
table size 16. Both deterministically execute a `GET_MISS` for a `java.lang.reflect.Method` key with the same structural
class and supplemental hash, perform resolution, and finish with a proven `PUT_NEW` and count one. Both also follow
the same two `ReflectionUtils.declaredMethodsCache` miss/new paths. Therefore a different
`BridgeMethodResolver.cache` hit/miss or pre-existing entry directly contradicts the observed control flow and is
rejected as the cause of movement.

In run 3, `getInterfaces` receives a concrete `ResolvableType` receiver whose `interfaces` field is null immediately
before the call and initialized immediately after it. In run 4 no receiver enters that method. The extra
`ResolvableType.cache` work in run 3 occurs inside/after that resolution branch and is a consequence, not a proven
pre-call cause.

The narrow trace does not capture the order of candidate `Method` objects traversed by
`BridgeMethodResolver.searchCandidates`. That is the exact missing runtime evidence needed to promote the remaining
explanation to `NON_CACHE_RUNTIME_CAUSE_PROVEN`. No such claim is made. The classification `CAUSE_REJECTED` applies
specifically to the proposed cache hit/miss mechanism, not to every possible runtime mechanism.

## Non-reproduced map internals

`Segment#restructure` never entered for its selected test, so no resize/purge cause can be assigned.
`SoftEntryReference#get` never entered for its selected test, so no selected reference receiver or referent transition
exists to classify. Both are `NOT_REPRODUCED`, rather than negative causal findings.

Seven concrete `ConcurrentReferenceHashMap` owners were resolved in every run: `ResolvableType.cache`,
`SerializableTypeWrapper.cache`, `BridgeMethodResolver.cache`, `GenericTypeResolver.typeVariableCache`, both requested
`ReflectionUtils` caches, and `ClassUtils.interfaceMethodCache`. All observed map-internal receivers resolved to one
of these owners; unresolved count is zero.

## Evidence and safety

The required artifacts are in `stp-spring-core-spike/round13/evidence`. `round13-runtime-trace.json` retains raw events
under separate run scopes; `round13-test-timeline.json` uses the same sequence as method events. The cache registry,
bounded reproduction, controlled comparison, classifications, and summary are separate JSON documents.

No JFR, heap dump, thread dump, JMX, async-boundary extension, or distributed topology was used. The selected Spring
tests passed, the STP regression suite passed, Spring remained clean at the required revision, and `git diff --check`
passed.

```text
targets: 3
focused runs: 4
targets reproduced moving: 1
targets not reproduced: 2

CACHE_STATE_CAUSE_PROVEN: 0
NON_CACHE_RUNTIME_CAUSE_PROVEN: 0
CAUSE_REJECTED: 1
INSUFFICIENT_EVIDENCE: 0
NOT_REPRODUCED: 2

resolved ConcurrentReferenceHashMap owners: 7
unresolved map-internal receivers: 0
```
