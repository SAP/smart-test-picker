# ROUND 15 — Reflection order origin

## Result

`FIRST_DIVERGENT_STAGE = CLASS_GETDECLARED_METHODS`

`reflection-order hypothesis = PROVEN`

`final classification = JDK_REFLECTION_ORDER_CAUSE_PROVEN`

For this Homebrew OpenJDK 21.0.11 arm64 / Spring `99a366baf6640b275d08dde60f05da719139bb6a` / Gradle 8.14.2 / focused-test setup, `Class#getDeclaredMethods()` returned the same structural `Method` set in different orders across fresh JVM runs. This is deliberately a setup-specific observation, not a general claim about JVM reflection.

## Target and method identity

The only target was `BridgeMethodResolverTests#withGenericParameter_5de9f312`, with moving edge `org.springframework.core.ResolvableType#getInterfaces`.

Methods were compared across JVMs only by `declaringClass#method(parameterTypes)->returnType`, plus `bridge`, `synthetic`, and `modifiers`. `identityHashCode` is retained only for correlation inside one JVM.

## Direct PRESENT/ABSENT contrast

Final run 5 was PRESENT and final run 1 was ABSENT. Both raw arrays queried the same class, `BridgeMethodResolverTests$StringGenericParameter`, and contained the same three structural methods.

ABSENT run 1 raw order:

1. `getFor(Class)->Object` (bridge/synthetic)
2. `getFor(Class)->String`
3. `getFor(Integer)->String`

PRESENT run 5 raw order:

1. `getFor(Class)->Object` (bridge/synthetic)
2. `getFor(Integer)->String`
3. `getFor(Class)->String`

The first difference is raw array index 1. The observation hook runs synchronously immediately after the exact `clazz.getDeclaredMethods()` invocation in the private `ReflectionUtils#getDeclaredMethods(Class, boolean)` path. It records the array in place and returns the same array reference; it does not sort, normalize, mutate, or retain `Method` instances.

## Propagation

The JVM-wide monotonic trace proves this sequence:

```text
RAW_REFLECTION_RESULT
  indexes 1/2: Integer,Class (PRESENT) vs Class,Integer (ABSENT)
-> REFLECTIONUTILS_RETURN
  same sequence as the corresponding raw array
-> DOWITHMETHODS_CALLBACK
  bridge rejected; two non-bridge methods accepted in raw order
-> CANDIDATE_INSERT
  accepted methods inserted in callback order
-> SEARCH_CANDIDATES_INPUT
  Integer,Class (PRESENT) vs Class,Integer (ABSENT)
-> ROUND 14 proven control flow
  PRESENT explores Integer and reaches ResolvableType#getInterfaces;
  ABSENT directly matches Class and returns first
```

Spring adds no reorder in this observed path. The differing raw order propagates unchanged through the relevant `ReflectionUtils` return and traversal, candidate insertion, and `searchCandidates` input.

## Reproduction

Six identical fresh-JVM runs used the finalized `round15-runtime-trace-1` schema. Runs 1–4 and 6 were ABSENT; run 5 was PRESENT. Every focused test passed and every trace reported zero dropped events and zero agent transformation errors.

The requested two-PRESENT/two-ABSENT sampling target was not reached before the hard six-run maximum. The single PRESENT/ABSENT contrast is nevertheless sufficient to identify the first divergence directly; the shortfall is recorded in the reproduction and summary artifacts.

## Instrumentation scope and safety

ROUND 15 diagnostics are enabled only by `stp.round15.trace.output`. The transformer is restricted to the exact `ReflectionUtils` and `BridgeMethodResolver` boundaries needed by this target. It does not instrument `java.lang.Class`, use bootstrap instrumentation, sort reflection results, change collections, or alter selector/map semantics. The Spring source checkout remains untouched.

## Evidence

The evidence bundle is under `stp-spring-core-spike/round15/evidence/`, including raw per-run maps/traces and all requested aggregate JSON artifacts. The main comparison is `round15-present-absent-order-diff.json`; the scoped conclusion is in `round15-reflection-order-classification.json`.
