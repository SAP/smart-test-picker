<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# TASK 21 full Spring Core ASM versus evaluation map

## Scope and inputs

TASK 21 measures population migration impact; it does not establish execution truth for every divergent edge. Accuracy evidence remains the bounded same-run JVMTI result from TASK 19 and focused mechanism confirmation from TASK 20.

The subject is Spring `99a366baf6640b275d08dde60f05da719139bb6a`, `:spring-core:test`, Gradle 8.14.2, and OpenJDK 21.0.11. The reference is evaluation repository commit `72bc94c60642467f3abce5b2664658aa93d77cb9`, `spring-core/results/test-coverage-map.json`, SHA-256 `936becf03857bfa0d22958ca88053f4c8bfabc85651f822f4ef51837bb2813b5`. Its metadata commit is the documented build-only setup commit over the authoritative Spring revision. It has 3,624 mapped tests, 171,206 method edges, and 53,328 class edges.

## ASM integrity

The fresh run completed with 4,700 physical executions: 4,676 successful and 24 aborted/skipped. It produced 3,638 logical tests, 3,629 mapped. All 3,624 reference identities were present. Agent errors, transformation failures, method-ID collisions, and `UNKNOWN_CONTEXT` were zero. `NO_ACTIVE_TEST` was 11,210 and `LATE_EVENT` 613,680; these intentionally unattributed hits do not invalidate owned buckets. The integrity gate passed.

The raw map remains at `/private/tmp/task21-raw/asm-full-map.json`, SHA-256 `d9f8b1b1e8633ac90694ed2af2dc49e6f4f92fc61e990cb0eaacd25a3a6915f9`.

## Population comparison

The primary identity is test to class plus method name because the historical map has no descriptors. No descriptor-exact cross-map comparison is possible; 13,478 historical name edges are overload-ambiguous in ASM's descriptor view.

| Metric | ASM | JaCoCo | Both | ASM only | JaCoCo only |
| --- | ---: | ---: | ---: | ---: | ---: |
| Per-test methods | 149,781 | 171,206 | 147,852 | 1,929 | 23,354 |
| Per-test classes | 44,691 | 53,328 | 44,546 | 145 | 8,782 |
| Global methods | 3,577 | 3,532 | 3,463 | 114 | 69 |
| Global classes | 586 | 582 | 576 | 10 | 6 |

The method symmetric difference is 25,283 (14.603055% of the edge union); class difference is 8,927 (16.694407%). Of 3,624 common tests, 1,400 are identical and 2,224 differ. Absolute per-test differences are P50 1, P75 4, P90 11, P95 29, P99 110, maximum 145.

Redistribution dominates: 22,919/23,354 (98.1374%) JaCoCo-only per-test edges name methods present somewhere in ASM. Conversely, 543/1,929 (28.1493%) ASM-only edges appear somewhere in JaCoCo. The global method difference is only 183 identities.

Constructors account for 10,441 differences (41.30%), ordinary methods 9,310 (36.82%), and `ConcurrentReferenceHashMap` 4,647 (18.38%). Generated enum/record/synthetic/proxy members total 503 (1.99%). Categories are structural, not causal proof.

## Proven mechanisms and topology

Only exact TASK 19 test-plus-method matches are proven: 57 ASM-only and 2,373 JaCoCo-only edges. Another 96 ASM-only and 228 JaCoCo-only edges are known patterns only. The remaining 1,776 ASM-only and 20,753 JaCoCo-only edges are unexplained. TASK 21 therefore confirms prevalence without extending the oracle claim.

For the 42 TASK 19 tests, isolated and full-suite ASM maps are identical for 10 and changed for 32. Full-suite maps add 25 edges and omit 898 isolated edges, a 923-edge symmetric difference. This is topology/runtime-state sensitivity, not collector error.

## Selector and evaluation impact

The evaluation repository's unchanged `select_original` semantics were replayed for all 454 committed KILLED PIT mutations. Selection is identical in 364 cases; 52 have ASM-only selections and 44 have JaCoCo-only selections. Across cases ASM adds 123 and removes 3,385 selections. Average size falls from 80.6 to 73.4 tests (delta -7.185; range -336 to +21).

Ten ASM-only selection edges across eight scenarios exactly match proven TASK 19 ASM advantages; 113 are unexplained and are not promoted by shape. Exact replay improves the repository's inclusiveness metric from 443/454 (97.58%) to 454/454 (100%). All 11 safety flips are JaCoCo-unsafe to ASM-safe; none become worse. Fourteen distinct newly selected killing tests contribute to recovery. Some individual killing tests are no longer selected, but every affected mutation retains another selected killing test.

## Required decisions

1. Maps differ materially per test: 25,283 method and 8,927 class edges.
2. Constructors, ordinary methods, and `ConcurrentReferenceHashMap` dominate; differences are not limited to generated families.
3. Redistribution dominates global detection difference.
4. Exact proven mechanisms explain 2,430 edges (9.61%); most remain causally unexplained.
5. Full topology changes 32/42 sampled ASM maps and 923 edges.
6. ASM changes 90/454 selections and reduces average size by 7.2 tests.
7. Yes, exact proven ASM advantages contribute selection edges and all 11 baseline safety gaps are recovered.
8. Selected counts decrease overall, with bounded increases in some cases.
9. No evaluation safety outcome worsens.
10. Decision: `RECOMMENDATION_STRENGTHENED`.

Use ASM as the STP production mapping collector. Retain JaCoCo for project coverage and diagnostic reference. The collector-choice topic is ready to close; ownership/topology refinements remain separate work.

## Reproduction

Build `:stp-agent:agentJar`; run the pinned Spring checkout with `task21/spring-agent.init.gradle` and the configuration recorded in `asm-run-integrity.json`; then run:

```sh
python3 stp-spring-core-spike/task21/analyze_task21.py
```

This recreates all compact evidence from the raw map and exact evaluation checkout paths declared in the script.
