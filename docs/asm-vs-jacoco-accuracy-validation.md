<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# ASM versus JaCoCo accuracy validation

## TASK 20 bytecode/probe confirmation

TASK 20 independently confirmed a descriptor-exact sample of ten of the 57 TASK 19 JaCoCo false negatives. JaCoCo's direct analyzer agreed with the TASK 19 decoder, input bytes and execution-data IDs matched, and reset timing and representation ambiguity explained none. Six cases were generated-code filter exclusions despite hit probes; four entered and then exited exceptionally before relevant normal-flow probe evidence. TASK 19's 57-FN metric remains unchanged. See [TASK 20 JaCoCo false-negative confirmation](task20-jacoco-fn-confirmation.md) for the bounded evidence and safe claim wording.

## Scope and experiment

TASK 19 adds execution truth to the earlier repeatability comparison. It uses Spring Framework `99a366baf6640b275d08dde60f05da719139bb6a`, `:spring-core:test`, Gradle 8.14.2, and JDK 21.0.11. Spring source was not modified and selector semantics were not started.

The historical name-level population contains 25,281 divergent `TestIdentity -> MethodIdentity` edges across 2,236 tests: 1,951 ASM-only and 23,330 JaCoCo-only. The machine-readable inventory retains every edge and 64 positive controls. Its categories are sampling strata, not inferred causes.

Forty initially selected tests plus two eligible late-related supplements represent 2,795 divergent edges, 11.055734% of the population. Parameterized methods with multiple historical leaves were excluded because a Gradle method selector would violate the one-leaf rule. Every accepted unit ran in a fresh JDK 21 worker with JaCoCo, ASM, JVMTI, and the lifecycle recorder simultaneously. The test executed once; historical maps only selected the test and its target union.

## Agent order

Both orders passed the smoke test without transformation errors and produced the same normalized physical execution and ASM sets. ASM-first changed JaCoCo by adding four implicit enum constructors. ASM's entry call had become executable input to JaCoCo analysis. The canonical order is therefore JaCoCo first, ASM second, with JVMTI observing both. This lets JaCoCo analyze original Spring bytecode.

## Execution and ownership

Execution truth comes only from JVMTI entries. Ownership truth uses the independent leaf interval and finer lifecycle records:

- same-thread entries from leaf start through leaf end are `TEST_OWNED`;
- `beforeAll`/`afterAll` invocations are `OUTSIDE_TEST`;
- work after leaf end is `LATE` unless it is the recorded same-thread `afterAll` invocation;
- other-thread work during the leaf is `AMBIGUOUS` without independent task context.

The 42 target-union joins contain 3,679 oracle test-owned method observations, 3,327 outside observations, no late observations, and 55 ambiguous observations. Forty-nine ambiguous edges fell in the target unions and were excluded from precision/recall. There is therefore insufficient Spring-sample evidence to rank late handling, despite the controlled late fixture passing.

## Primary results

Metrics are name-level and omit true negatives.

| Collector | TP | FP | FN | Precision | Recall | F1 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ASM | 2,955 | 0 | 0 | 1.000000 | 1.000000 | 1.000000 |
| JaCoCo | 2,898 | 2,373 | 57 | 0.549801 | 0.980711 | 0.704595 |

ASM matched every non-ambiguous owned target edge and excluded every outside target edge in this sample. JaCoCo's dominant defect was attribution precision: its first leaf dump contained pre-leaf execution accumulated since JVM startup. The 57 JaCoCo false negatives were physical entries seen by both JVMTI and ASM, dominated by implicit/synthetic enum methods and constructors, simple accessors, and cache internals that its probe/instruction representation did not report.

| Stratum | ASM P/R/F1 | JaCoCo P/R/F1 |
| --- | --- | --- |
| ordinary | 1.000/1.000/1.000 | 0.635/0.993/0.775 |
| constructor | 1.000/1.000/1.000 | 0.197/0.952/0.327 |
| setup/lifecycle name stratum | 1.000/1.000/1.000 | 1.000/0.857/0.923 |
| async, non-ambiguous | 1.000/1.000/1.000 | 1.000/0.930/0.964 |
| reflection | 1.000/1.000/1.000 | 0.936/0.964/0.950 |
| `ConcurrentReferenceHashMap` | 1.000/1.000/1.000 | 0.688/0.969/0.805 |
| static initialization | 1.000/1.000/1.000 | 0.885/0.958/0.920 |
| late | no scored Spring observation | no scored Spring observation |

The labels above use method-name strata. Lifecycle ownership itself is derived from timestamps, not names.

## Descriptor normalization

All 5,542 primary comparisons were performed at level 1 (`class + method`). Same-run JaCoCo decoding preserves JVM descriptors, enabling level 2 evidence. There were 402 name-level overload ambiguities; joined entries list every candidate descriptor and explicitly label `OVERLOAD_AMBIGUITY` rather than guessing. The primary score does not invent descriptor-specific historical edges.

## Global mode

Six representative same-run captures use all production class names found in Spring's compiled main outputs. Across per-test sums they contain 885 methods seen by oracle and both collectors, 9 oracle-only, 36 JaCoCo-only versus oracle, and no ASM-only versus oracle. Seven oracle-only observations are outside-test implicit enum/cache initialization; two are ownership-ambiguous async accessors. These are physical-execution findings, not normal per-test false negatives.

## Repeatability and observer effect

Two representative tests were each run in three fresh triple-instrumented JVMs. Oracle-owned, ASM, and JaCoCo sets were stable in all six observations.

Four controls compared ASM+JaCoCo with ASM+JaCoCo+oracle. Three were identical. In one reflection/cache test the no-oracle run executed two additional cache methods, and both collectors reported them. The observer effect is `SOME_CASES`; this is why accuracy is always judged inside one triple-observed execution.

## Error taxonomy and decisions

The primary evidence contains 2,373 reset-boundary/extra-attribution errors, 57 JaCoCo collector misses, and 49 target-union async ownership ambiguities. Four method-representation differences were proven in the order smoke. The observer control proved one two-method actual-path variation. No scored disagreement remains `UNKNOWN`.

- Q1 precision: ASM.
- Q2 recall: ASM in this sample.
- Q3 wrong lifecycle-window attribution: JaCoCo produced more, by 2,373 to zero.
- Q4 late execution: insufficient Spring evidence; controlled oracle fixture only.
- Q5 setup/lifecycle: ASM on the bounded name stratum and overall interval policy, but the named setup stratum is small.
- Q6 exact physical entry: ASM matched all non-ambiguous owned level-1 entries; JaCoCo missed 57.
- Q7 most differences: attribution semantics/reset boundary, not physical detection, by observed error count.
- Q8 overall: `ASM_CLEARLY_BETTER` under the defined semantics and sampled population.

The conclusion is bounded by 11.06% historical edge coverage, the cross-thread ambiguity policy, and the measured observer effect. It is not a global proof over every Spring difference.

## Reproduction and artifacts

Build and run the oracle fixtures with `oracle/run-fixture.sh` and `oracle/run-lifecycle-fixture.sh`. Reconstruct/select with `reconstruct_population.py` and `select_sample.py`; build the repository agent/listener JARs; then use `run_sample.sh`. `run_and_join.sh` is the single-test primitive. `aggregate_results.py` produces the summary.

Raw method traces remain under `/private/tmp/task19-primary-v2`; `raw-trace-hashes.json` records sizes and SHA-256 values. Git retains each joined observation, each required comparison, six global joins, fixture acceptance, order/observer/repeatability evidence, and summaries.
