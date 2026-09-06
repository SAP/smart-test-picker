<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Round 12: Spring Core object provenance

## Result

ROUND 12 selected four of the 192 ROUND 10 `UNKNOWN` edges and used a focused JDK 21 JFR recording, an STP-correlated
test timeline, existing ASM method hits, and one fresh-versus-warmed contrast. The runtime evidence does not establish
object-history causality for any selected edge. All four are `INSUFFICIENT_EVIDENCE`; none is classified as
`OBJECT_HISTORY_PROVEN` or `OBJECT_HISTORY_REJECTED`.

The fixed Spring revision was `99a366baf6640b275d08dde60f05da719139bb6a`; Gradle was 8.14.2, the task was
`:spring-core:test`, and both the Gradle launcher and explicitly configured test executable used JDK 21. Spring source
was not modified. ROUND 12 diagnostics are opt-in and do not alter normal map or selector semantics.

## Representative targets

The two primary map targets cover different recurring internal behaviors: `Segment#restructure` for
`ResolvableTypeTests#classWithGenericsAs_1378ee35` and `SoftEntryReference#get` for
`ResolvableTypeTests#forMethodParameterWithNestingAndLevels_3ac7fcf8`. Their suspected owner is `ResolvableType.cache`,
but ROUND 10 did not capture the map receiver, so that owner remains a hypothesis.

`ResolvableType#getInterfaces` for `BridgeMethodResolverTests#withGenericParameter_5de9f312` was selected because it is
a directly ResolvableType-owned edge on the known BridgeMethodResolver miss path. `SerializableTypeWrapper#unwrap` for
`MapToMapConverterTests#collectionMapSourceTarget_5bff386f` is the non-map-internal control and has a directly implicated
`SerializableTypeWrapper.cache` owner. Selection details and run presence/absence are in `round12-targets.json`.

## JFR and TestIdentity correlation

The focused recording enabled only allocation-in/outside-TLAB events, allocation samples, old-object samples, garbage
collection and heap summaries, and thread start/end. Old-object GC-root paths were enabled on the recording command.
The exact settings are preserved in `round12-jfr-config.txt`, and the binary recording is
`round12-recording.jfr`.

The opt-in JUnit listener emitted `Instant` wall-clock start/end timestamps, monotonic timestamps, Java thread ID/name,
the canonical source class/method, and the JUnit logical unique ID. All four selected tests ran on the `Test worker`
thread (Java thread ID 1), and no propagated logical work was observed. `round12-test-timeline.json` contains the
correlation-ready intervals from the same JVM as JFR.

## Allocation evidence

The recording contains 440 `ObjectAllocationInNewTLAB`, 50 `ObjectAllocationOutsideTLAB`, and 431
`ObjectAllocationSample` events. None named `ResolvableType`, `ConcurrentReferenceHashMap`, its Segment or
SoftEntryReference, `SerializableTypeWrapper`, or `BridgeMethodResolver` as the allocated class. Therefore no relevant
allocation could be assigned to a TestIdentity and no selected allocation stack is available.

This is an absence from sampled JFR events, not proof that no such object was allocated. TLAB and sampling events are
not an allocation census. The exact object instance cannot be identified.

## Retention evidence

Twenty old-object samples were emitted at recording end. JFR supplied usable allocation stacks, ages, retaining paths,
and GC roots for unrelated objects, including JDK locale cache entries. None was a selected Spring object and none had
a selected Spring cache/map/field in its path. Consequently the evidence does not prove that a relevant object survived
its creating test, was retained by a suspected structure, or was reused by a later test. Same-instance identity is not
proven.

A heap dump was not taken. With no relevant sampled instance or narrow object marker to anchor a query, an end-of-run
dump could show class-level reachability but could not connect an instance to an earlier TestIdentity and moving edge.
Forcing an exact between-test dump would require extra control behavior. It would not materially resolve the selected
causal questions, so broad heap analysis was avoided.

## Controlled contrast

The selected ResolvableType restructure edge was compared in two fresh JVMs. In the warm case,
`BridgeMethodResolverTests#withGenericParameter` and another ResolvableType test ran before `classWithGenericsAs`. In
the cold case, `classWithGenericsAs` ran alone. JDK, Gradle, revision, task, and agent configuration were identical.
The edge was absent in both runs.

Because this focused contrast did not reproduce the ROUND 10 movement, it neither links provenance to the movement nor
directly contradicts every possible object-history mechanism. It is therefore `INSUFFICIENT_EVIDENCE`, not
`OBJECT_HISTORY_REJECTED`.

## Causal answers

For every selected edge: no relevant allocation was observed; no allocation TestIdentity or selected allocation stack
was established; no relevant retained object, GC root, or suspected-cache path was sampled; no later same-instance
reuse was observed; and no reuse was causally connected to edge movement. These are separate negative findings about
the available evidence, not a combined claim that allocation or retention did not occur.

## JaCoCo

No object-history edge was proven, so the requested proven-edge-only JaCoCo comparison is not applicable and the full
map was not regenerated. For context, the existing ROUND 9 reference assigns three selected methods to another test and
does not contain or distinguish one. It provides ordinary coverage ownership only, with no evidence about prior object
or JVM history.

## Evidence and safety

Required evidence is under `stp-spring-core-spike/round12/evidence/`. The four-test JFR run and isolated contrast passed.
The complete STP regression suite passed with the timeline diagnostic present. The Spring checkout remained clean at
the required revision, and `git diff --check` passed. Diagnostic activation requires ROUND 12 system/environment
properties.

```text
ROUND 12 OBJECT PROVENANCE

targets: 4
JFR recording: round12-recording.jfr (valid; 921 allocation events, 20 old-object samples)
heap dump used: no

TARGET 1
test: ResolvableTypeTests#classWithGenericsAs_1378ee35
method: ConcurrentReferenceHashMap$Segment#restructure
allocation observed: no relevant JFR sample
allocation owner test: not established
allocation stack: not available
retaining path: not sampled
same instance later observed: no; same-instance identity not proven
classification: INSUFFICIENT_EVIDENCE

TARGET 2
test: ResolvableTypeTests#forMethodParameterWithNestingAndLevels_3ac7fcf8
method: ConcurrentReferenceHashMap$SoftEntryReference#get
allocation observed: no relevant JFR sample
allocation owner test: not established
allocation stack: not available
retaining path: not sampled
same instance later observed: no; same-instance identity not proven
classification: INSUFFICIENT_EVIDENCE

TARGET 3
test: BridgeMethodResolverTests#withGenericParameter_5de9f312
method: ResolvableType#getInterfaces
allocation observed: no relevant JFR sample
allocation owner test: not established
allocation stack: not available
retaining path: not sampled
same instance later observed: no; same-instance identity not proven
classification: INSUFFICIENT_EVIDENCE

TARGET 4
test: MapToMapConverterTests#collectionMapSourceTarget_5bff386f
method: SerializableTypeWrapper#unwrap
allocation observed: no relevant JFR sample
allocation owner test: not established
allocation stack: not available
retaining path: not sampled
same instance later observed: no; same-instance identity not proven
classification: INSUFFICIENT_EVIDENCE

CONTROLLED CONTRAST

result: the selected ResolvableType restructure edge was absent in both warm and isolated fresh-JVM runs

OBJECT HISTORY

proven: 0
rejected: 0
insufficient evidence: 4

JACOCO

proven history-dependent edges represented as ordinary coverage: 0
details: no proven edge required comparison; existing reference has three other-test assignments and one absent/not distinguished

CONCLUSION
JFR/runtime evidence does not establish object-history causality for the selected cases.
```
