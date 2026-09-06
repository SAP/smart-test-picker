<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# Round 9: Spring Core ASM validation

## Conclusion

The pinned Spring Core suite completed twice with the actual shaded ASM agent and no test failures,
transformation errors, or method-ID collisions. The ASM global method/class unions are stable, but per-test
attribution is not: 65 of 3,638 logical ASM identities (1.79%) changed between the two runs, with 215 directional
method-edge changes. Thus the current implementation does not eliminate Spring's moving-data behavior. It
represents much of it more safely by quarantining late work, but substantial order/cache/initialization-sensitive
redistribution remains.

Against the canonical JaCoCo map, 2,236 tests changed at name-preserving method granularity. Of 23,330
reference-only method edges, 22,895 (98.14%) name a method seen elsewhere in ASM, demonstrating that the dominant
effect is redistribution rather than globally missing code. The comparison does not establish causality for most
individual edges, so unsupported classifications are retained as `UNKNOWN` in the classification artifact.

Spring exercises supported active-test propagation: each run has 93 active-context captures, 91 attaches, and 85
restores. Observed tasks include executor/Reactor scheduler work, executor worker creation, CompletableFuture
supplier work, and raw-thread-style tasks among the remaining supported wrappers. The current diagnostic format
does not put task/thread IDs on individual method-hit events, so zero changed edges are claimed as proven
`ASYNC_CONTEXT_PROPAGATION`; no late event is claimed as a proven late propagated task.

## Baseline

STP is branch `research/asm-codex`, commit `64c2636fe4f9224e7f3af4c9677c38fa38594115`, with the pre-existing
uncommitted Round 6/7/8 state. Round 9 made only two production corrections demonstrated by this validation:
streaming the final agent envelope to avoid a shutdown `OutOfMemoryError`, and excluding Gradle test outputs plus
generated proxy/auxiliary classes from production instrumentation. No async boundary was added.

The subject is `spring-projects/spring-framework` at
`99a366baf6640b275d08dde60f05da719139bb6a`, task `:spring-core:test`, Gradle 8.14.2, OpenJDK 21.0.11.
Both runs used a normal single Gradle test task with the default Spring test topology and identical ordering
configuration.

The final agent configuration was:

```text
includes=org.springframework.
excludes=org.springframework.jcl.,org.springframework.core.testfixture.,org.springframework.javapoet.,org.springframework.objenesis.
debug=true
instrumentation=on
```

The transformer additionally excludes Gradle `test`/`testFixtures` output locations, JUnit/JDK/STP infrastructure,
and generated proxy/auxiliary classes. Spring production source code was not modified.

## Reference and inventory

The canonical reference is evaluation repository commit `72bc94c60642467f3abce5b2664658aa93d77cb9`, path
`spring-core/results/test-coverage-map.json`. It records evaluation setup commit `25838a334c037b68e614f6b571af03a1f6bfec19`;
the evaluation documentation confirms this is build-only setup over the authoritative production revision
`99a366baf6640b275d08dde60f05da719139bb6a`. Its schema is `testMappings[testId] = {classes, methods}` plus
`classMetrics` and metadata. It has 3,624 mapped identities, 53,328 class edges, and 171,206 method-name edges.
It does not store outcomes or the identities of executed-but-unmapped tests.

Reference keys use `SimpleNestedClass#method_7hexHash`, where the hash is Java `hashCode(FQCN#method)`. ASM emits
FQCN and method separately. Normalization finds all 3,624 reference identities in ASM and no missing identities.
ASM discovers 3,638 production-logical identities, 14 more than the reference artifact; all 14 are preserved in
`asm-inventory-comparison.json`. Nine ASM identities have no attributed production method, leaving 3,629 mapped.
Because the reference does not preserve its full 4,705-execution inventory, exact full-inventory equality cannot
be proven; semantic comparison uses the union and does not hide the 14 ASM extras.

Reference methods omit descriptors. The primary comparison therefore uses exact class and method names. The full
descriptor-preserving view is intentionally excluded from canonical Git; its SHA-256 and reproduction instructions
are in `docs/removed-research-artifacts.md`.

## ASM run results

| Metric | Run 1 | Run 2 |
| --- | ---: | ---: |
| executed / skipped / failed | 4,705 / 29 / 0 | 4,705 / 29 / 0 |
| logical inventory / mapped | 3,638 / 3,629 | 3,638 / 3,629 |
| class edges | 44,700 | 44,697 |
| method-name edges | 149,827 | 149,800 |
| global classes / methods | 586 / 3,577 | 586 / 3,577 |
| raw / unique descriptor hits | 6,331,154 / 4,309 | 6,330,668 / 4,309 |
| transformation errors / collisions | 0 / 0 | 0 / 0 |
| `LATE_EVENT` | 613,704 | 613,714 |
| `NO_ACTIVE_TEST` | 11,210 | 11,210 |
| `UNKNOWN_CONTEXT` | 0 | 0 |

## Repeatability

Run 1 versus run 2 changes 65 tests (1.7867%): 121 run-1-only and 94 run-2-only method edges, plus three
run-1-only and zero run-2-only class edges. Outcomes are identical. Global class and method unions are identical.
This is a semantic comparison; JSON bytes, counts, timestamps, and task object identities are not compared.

## JaCoCo versus ASM

At method-name granularity, 2,236 tests change (61.6998% of the 3,624 reference mapped identities). There are
23,330 reference-only and 1,951 ASM-only method edges; 8,777 reference-only and 149 ASM-only class edges. The
class symmetric difference is 8,926 (16.7379% of 53,328 reference edges); the method symmetric difference is
25,281 (14.7664% of 171,206 reference edges).

Globally the reference has 582 classes and 3,532 methods, while ASM has 586 classes and 3,577 methods. The global
directional differences are 6 reference-only versus 10 ASM-only classes, and 69 reference-only versus 114 ASM-only
methods. The canonical artifact has no outcomes, so outcome differences cannot be computed; both ASM runs have
identical outcomes and zero failures. The full decoded directional diff is intentionally excluded from canonical Git;
its SHA-256 and reproduction instructions are in `docs/removed-research-artifacts.md`. Compact classifications and
summaries remain.

## Redistribution

22,895/23,330 (98.14%) reference-only method edges refer to methods present somewhere in ASM. 564/1,951 (28.91%)
ASM-only edges refer to methods present somewhere in the reference. Directional edges contain 579 unique
reference-only identities and 269 unique ASM-only identities, with 30 occurring in both directional sets. This
strongly supports redistribution as the dominant reference-to-ASM difference, while the lower ASM-direction ratio
also reflects ASM-only entry semantics and the 14 extra mapped identities.

## Context propagation

Each run records 96 captures: 93 with an active `TestIdentity` and three without one. Each has 91 attaches and 85
restores. The unmatched attaches are long-lived/cancelled work at process completion; the log preserves task class,
submission thread, execution thread, and originating platform identity. Observed classified mechanisms include 16
executor worker-creation captures, six Reactor scheduler tasks via executor execution, two CompletableFuture
supplier/Callable-style tasks, and 69 other supported wrappers including raw `Thread-*` executions. There is no
evidence for direct ForkJoinTask, reflection/MethodHandle submission, overridden Thread subclasses, or preloaded
caller propagation, and none is claimed.

Per-hit task/thread correlation is not present in the current output schema. Consequently no changed edge is
causally labeled async merely because concurrent code is involved. The evidence proves context propagation was
active and preserved logical ownership for wrapped tasks, but not which comparison edge it changed.

## Initialization, cache, and historical patterns

There are 54 reference-only and five ASM-only `<clinit>` edges: 58 unique initializer identities affecting 26
tests. Historical hot areas occur directionally, notably `ConcurrentReferenceHashMap` (3,330 reference-only / 1,329
ASM-only edges), `ResolvableType` (1,354 / 10), `StringUtils` (351 / 0), `Assert` (535 / 4),
`AnnotationTypeMappings` (22 / 69), `DataBufferUtils` (99 / 7), and `MimeTypeUtils` (1 / 17). Full top-20 lists and
all requested historical counts are in `static-initializer-analysis.json`.

The most recurring reference-only methods begin with `ConcurrentReferenceHashMap#getLoadFactor` and
`Segment#createReferenceArray` (353 edges each), then `Assert#isTrue` (336). The leading ASM-only method is
`ConcurrentReferenceHashMap$SoftEntryReference#get` (1,150), followed by its `getNext` (50) and segment
restructure (43). These patterns are compatible with cache/JVM-state and execution-order sensitivity, but the
per-edge classifications remain `UNKNOWN` absent causal traces.

## Unattributed events

Run 1 has 613,704 `LATE_EVENT` hits across 522 methods, 11,210 `NO_ACTIVE_TEST` hits across 143 methods, and zero
`UNKNOWN_CONTEXT`; run 2 differs by ten late hits only. All top unattributed methods are recorded with whether they
are covered in an active bucket elsewhere. The largest late population is concurrent-reference-map cleanup; the
largest no-active population includes `Assert#notNull` and asynchronous `DataBufferUtils` callbacks.

The data proves no late event has a safe active owner. It does not prove a specific late event is a propagated task
after test completion, so `late propagated events` remains zero and the detailed late partition is conservative.

## Regression safety and evidence

`./gradlew test --rerun-tasks` passed all 57 actionable tasks, covering the complete existing suite and Round 6/7
fixtures. Both Spring runs passed. `git diff --check` passes. Evidence is under
`stp-spring-core-spike/round9/evidence/`, including compact inventories, repeatability summaries, context diagnostics,
unattributed/redistribution/static/classification analyses, metrics, and the run manifest. Bulk raw artifacts are
intentionally excluded; their hashes and reproduction instructions are in `docs/removed-research-artifacts.md`.

```text
REFERENCE
repository: https://github.com/ljubisap/smart-test-picker-evaluation (spring-core/results/test-coverage-map.json)
revision: 99a366baf6640b275d08dde60f05da719139bb6a
task: :spring-core:test
inventory: 3624 mapped identities (full executed identity inventory not stored)
mapped: 3624
class edges: 53328
method edges: 171206

ASM RUN 1
inventory: 3638 logical identities
mapped: 3629
class edges: 44700
method edges: 149827
LATE_EVENT: 613704
NO_ACTIVE_TEST: 11210
UNKNOWN_CONTEXT: 0

ASM RUN 2
inventory: 3638 logical identities
mapped: 3629
class edges: 44697
method edges: 149800
LATE_EVENT: 613714
NO_ACTIVE_TEST: 11210
UNKNOWN_CONTEXT: 0

ASM REPEATABILITY
changed tests: 65 (1.7867%)
class edge diff: 3 run1-only / 0 run2-only
method edge diff: 121 run1-only / 94 run2-only
global class diff: 0 / 0
global method diff: 0 / 0

REFERENCE VS ASM
changed tests: 2236
changed test percentage: 61.6998%
reference-only class edges: 8777
ASM-only class edges: 149
reference-only method edges: 23330
ASM-only method edges: 1951
class symmetric diff percentage: 16.7379%
method symmetric diff percentage: 14.7664%

GLOBAL COVERAGE
reference-only classes: 6
ASM-only classes: 10
reference-only methods: 69
ASM-only methods: 114

REDISTRIBUTION
reference-only method edges redistributed: 22895 / 23330 (98.14%)
ASM-only method edges redistributed: 564 / 1951 (28.91%)
method identities both directions: 30

STATIC / CACHE
differing <clinit> edges: 59 (54 reference-only / 5 ASM-only)
top recurring methods: ConcurrentReferenceHashMap#getLoadFactor (353 reference-only); ConcurrentReferenceHashMap$SoftEntryReference#get (1150 ASM-only)

CONTEXT PROPAGATION
active-context captures: 93 per run
attach/restore count: 91 / 85 per run
observed mechanisms: Executor.execute/Reactor scheduler, executor worker creation, CompletableFuture supplier/Callable-style, raw-thread/other supported wrappers
edges attributable to propagation: 0 proven with available per-hit diagnostics
late propagated events: 0 proven

CONCLUSION
The current ASM/context-propagation implementation does not eliminate Spring Core per-test attribution instability: 65 tests move coverage between repeat runs despite identical global unions. It quarantines late work and preserves active context across supported wrappers, but against JaCoCo it primarily differently represents attribution history; 98.14% of reference-only method edges are globally present in ASM. No unsupported boundary is claimed.
```
