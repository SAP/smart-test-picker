<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Round 14: BridgeMethodResolver control flow

## Result

The moving edge is caused by candidate iteration order. Six identical fresh-JVM focused runs produced PRESENT runs
3 and 6 and ABSENT runs 1, 2, 4, and 5. The selected Spring test passed in every run. The compared candidate lists
have the same structural set but opposite order.

In PRESENT run 3, candidate index 0 is `StringGenericParameter#getFor(Integer)->String`. Its direct
`isResolvedTypeMatch` returns false. `isBridgeMethodFor` then calls `findGenericDeclaration`, which finds
`GenericParameter#getFor(Class)->Object`, and invokes the reverse `isResolvedTypeMatch`. Resolving that generic
declaration against `StringGenericParameter` enters `ResolvableType#getInterfaces`; the receiver's `interfaces`
field changes from null to initialized. The reverse match returns false, iteration advances to
`getFor(Class)->String`, and that candidate matches.

In ABSENT run 1, candidate index 0 is `StringGenericParameter#getFor(Class)->String`. Its direct
`isResolvedTypeMatch` returns true, so `searchCandidates` returns it immediately. It never calls
`findGenericDeclaration` for the Integer candidate and never reaches `ResolvableType#getInterfaces`.

## Candidate and reflection order

The candidate-order hypothesis is **PROVEN**. The exact same two structural candidates occur in reversed order, and
the nested decision trace proves that the reversal changes whether generic/interface resolution executes.

`ReflectionUtils#doWithMethods` supplies the list. Its observed filter-callback traversal has the same three leaf
methods in different orders, and the pinned source shows it iterates the array from
`ReflectionUtils#getDeclaredMethods(clazz, false)` without reordering it. However, ROUND 14 did not directly hook the
array returned by `Class#getDeclaredMethods`. Therefore JVM reflection nondeterminism is not claimed and the narrower
reflection-order cause is recorded as `NOT_PROVEN`; the final classification remains
`CANDIDATE_ORDER_CAUSE_PROVEN`, not `REFLECTION_ORDER_CAUSE_PROVEN`.

## Instrumentation and non-interference

The tracer is enabled only by `stp.round14.trace.output`. It records structural Method keys, bridge/synthetic flags,
modifiers, same-JVM identity hashes, the actual unsorted `searchCandidates` list, nested helper inputs/results, and
safe `ResolvableType` receiver state. It neither sorts nor retains Method objects beyond synchronous diagnostic
events. Spring source at `99a366baf6640b275d08dde60f05da719139bb6a` was not changed. Normal STP map and selector
semantics are unchanged.

The evidence is under `stp-spring-core-spike/round14/evidence`.
