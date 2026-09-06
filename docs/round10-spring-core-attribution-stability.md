<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Round 10: Spring Core per-test attribution stability

## Result

Three identical ASM maps for Spring Framework `99a366baf6640b275d08dde60f05da719139bb6a`,
`:spring-core:test`, Gradle 8.14.2, and JDK 21 contain 149,926 distinct per-test method edges. Of these,
149,705 (99.852594%) occur for the same canonical test in all three runs; 221 (0.147406%) move across 68 tests.
The global union remains the relevant ROUND 9 invariant: this round diagnoses attribution history, not collector loss.

ASM RUN 1 and RUN 2 are the established identical ROUND 9 executions copied without reinterpretation. RUN 3 is a
fresh successful execution with the same include/exclude, instrumentation, task, JDK, and Gradle configuration.
All three contain 3,638 logical identities and 3,629 mapped identities.

## Bounded causal trace

The experimental trace is opt-in through `stp.round10.trace.*` system properties and does not alter the normal map.
The three normal maps first selected 68 moving tests and 17 moving methods. One diagnostic execution then recorded
only hits matching those filters. Its structured records contain monotonically increasing event order, relative
nanosecond time, platform TestIdentity, source test key, thread id/name, logical context id, propagated task identity,
method descriptor identity, and whether the owner had already finished. The 20,000-event bound retained all 6,209
matching hits; zero were dropped. Raw uncontrolled per-hit text was not emitted.

All 6,209 filtered hits ran on `Test worker`. Zero ran with a propagated-task marker. Thus zero unstable edges execute
inside an observed correctly propagated async context, zero remain unstable despite correct propagation, and zero are
proved to result from missing/wrong context. Absence of a marker is not treated as proof about unsupported boundaries;
it establishes only that async propagation is not the cause of this observed moving set.

The trace includes 651 owner-finished hits across 22 moving test/method identities. Across the three normal maps, 23
moving identities also occur in a `LATE_EVENT` bucket in at least one run. These correlations do not prove that late
timing caused their normal edge movement, so they remain `UNKNOWN`. The aggregator checks `finished` before adding to
normal methods: no late hit entered a completed normal test bucket. The defect count is zero.

## Causes

No `<clinit>` edge is unstable across the three ASM runs. There is consequently no changing first-touch owner to
report for this experiment and no evidence that initializer behavior itself varied. This differs from ROUND 9's
JaCoCo-versus-ASM directional `<clinit>` set, which compared different collection semantics rather than identical ASM
runs. ROUND 10 does not force initializer ownership.

The moving inventory is heavily concentrated in `ConcurrentReferenceHashMap`: 180 of 221 edges are in its six leading
segment/reference methods. Spring source directly confirms `ResolvableType.cache` is a static
`ConcurrentReferenceHashMap`, and its lookup path purges, gets, conditionally constructs, and puts values. That is
consistent with shared cache and soft-reference/GC history. It is not sufficient for `SHARED_JVM_STATE`: the entry
trace has no receiver identity or cache occupancy and therefore cannot prove empty/full state, first insertion, or
reuse for each edge. No edge is classified from a method name alone.

The proven classification is:

| Cause | Edges |
| --- | ---: |
| `ASYNC_CONTEXT` | 0 |
| `LATE_EXECUTION` | 0 |
| `STATIC_INITIALIZATION` | 0 |
| `SHARED_JVM_STATE` | 0 |
| `EXECUTION_ORDER` | 29 |
| `UNKNOWN` | 192 |

## Controlled order experiment

Four representative unstable classes (`ResolvableTypeTests`, `TypeDescriptorTests`,
`DefaultConversionServiceTests`, and `ReflectionUtilsTests`) execute 365 logical test methods. Two successful fresh
JVM runs used JUnit random class/method order with fixed seeds 101 and 202. Configuration and selected tests were
otherwise identical. The maps differ for 1,452 method edges across 155 tests and reproduce 29 exact baseline-unstable
test/method edges. This proves execution order controls ownership for those 29. It does not prove a cache mechanism
for the other 192, which remain `UNKNOWN`.

## Safer representation and JaCoCo benchmark

For the 221 unstable ASM edges, the existing JaCoCo reference assigns 14 to the same test identity, assigns 191 to
another test while observing the method globally, and lacks or cannot distinguish 16. A single JaCoCo artifact cannot
identify which assignments are async, late, first-touch, shared-state, or order-sensitive, nor can it mark them as
measured instability.

ASM can safely retain its normal single-run map while producing separate experimental stability metadata: stable
edges, unstable edges with run presence, supported cause evidence, and quarantined late events. This does not use a
multi-run union as production semantics and does not define selector behavior. Process-initialization coverage may be
represented separately in a later design, but this data contains no unstable ASM `<clinit>` edge requiring a change.

## Evidence and regression safety

Canonical artifacts are in `stp-spring-core-spike/round10/evidence/`. `unstable-edge-inventory.json` is the compact
moving list and frequency grouping; `per-hit-causal-trace.json` is the bounded trace;
`unstable-edge-cause-classification.json` preserves per-edge proof or `UNKNOWN`; the remaining required JSON files
contain the async, late, initialization, shared-state, order, stability, and JaCoCo analyses. Spring source was not
modified and normal production map semantics were not changed. The complete STP regression suite passes.

```text
SPRING CORE STABILITY

runs: 3
mapped tests: 3629

stable method edges: 149705
unstable method edges: 221
stable percentage: 99.852594%
unstable percentage: 0.147406%

UNSTABLE CAUSES

ASYNC_CONTEXT: 0
LATE_EXECUTION: 0
STATIC_INITIALIZATION: 0
SHARED_JVM_STATE: 0
EXECUTION_ORDER: 29
UNKNOWN: 192

ASYNC RESULT

unstable edges with correct propagated context: 0
unstable edges caused by missing/wrong context: 0

LATE EVENTS

late events entering normal coverage: 0

ORDER EXPERIMENT

result: 29 baseline edges proven order-sensitive; 1452 focused edges changed across 155 tests

JACOCO COMPARISON

what ASM distinguishes better: logical/task ownership, owner-finished quarantine, and measured unstable metadata
what remains unresolved: 192 edges, including cache-compatible edges without direct cache-state evidence

CONCLUSION
99.852594% of observed edges are stable. The moving 0.147406% is not explained by async ownership or collector loss;
29 edges are directly order-sensitive, no late event leaks, no ASM <clinit> ownership moves, and 192 edges remain
explicitly unknown rather than being presented as deterministic coverage.
```
