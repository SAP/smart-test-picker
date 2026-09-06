<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# TASK 20 JaCoCo false-negative confirmation

## Result

`CONFIRMED_JACOCO_METHOD_ENTRY_GAP`

The exact TASK 19 input contains 57 edges. Ten descriptor-exact representative edges were rerun, one logical test per fresh JVM, with JaCoCo first, ASM second, and JVMTI observing both. All ten are `REAL_JACOCO_METHOD_COVERAGE_LIMITATION`; no decoder defect, class-byte mismatch, reset artifact, representation mismatch, or unknown cause was found.

Safe RAD1a wording is:

> JaCoCo's method-coverage model can fail to represent some physical JVM MethodEntry events as covered methods for specific bytecode shapes. In the confirmed sample this occurred when JaCoCo intentionally filtered generated methods/classes, and when a method exited by an implicit exception before reaching JaCoCo's normal-flow probe evidence.

This does not support the broader statement that JaCoCo is broken.

## Input and selection

[`jacoco-fn-inventory.json`](../stp-spring-core-spike/task20/evidence/jacoco-fn-inventory.json) is reconstructed only from the 42 committed TASK 19 joined observations. Its total is exactly 57: 19 `CONCURRENT_REFERENCE_HASH_MAP`, 13 constructors, 13 ordinary methods, six reflection methods, three async methods, two static initializers, and one setup/lifecycle-shaped method.

[`selected-fn-cases.json`](../stp-spring-core-spike/task20/evidence/selected-fn-cases.json) fixes ten cases spanning those shapes. Every selected edge has one descriptor candidate. The originally chosen bridge-named test reproduced its known reflection-order assertion failure in two fresh JVMs, so the selected bridge case uses a different edge from the exact 57 population which executes the identical `SoftEntryReference#get` descriptor. The failed attempts are non-evidence and remain only with the external raw material.

## Validation harness checks

For each canonical run, JaCoCo's `classdumpdir` bytes are the bytes presented to JaCoCo before its transformation. TASK 20 computed JaCoCo's CRC64 class ID from those bytes and analyzed the same bytes with `Analyzer` and the selected session `.exec` file. Nine cases have a matching execution-data class ID. The exceptional `ConvertingComparator` constructor has no class entry because execution leaves while evaluating the delegating-constructor arguments, before JaCoCo accesses the class probe array; this is absence, not an ID mismatch. SHA-256 and CRC64 evidence is in each case file.

The TASK 19 decoder and the independent direct analyzer agree in all ten cases: covered instruction count is zero and method-covered is false. Filtered members are absent from `IMethodCoverage`; non-filtered exceptional paths have zero covered and nonzero missed instructions. Consequently TASK 19 has no decoder defect in the sampled misses.

The timestamp trace records leaf start, descriptor-exact JVMTI entry, JaCoCo dump/reset start and end, and leaf end. The JaCoCo listener's `executionFinished` callback runs just before the oracle listener records leaf end. In every case, the owned synchronous entry and method path precede dump/reset. No selected miss was cleared early, assigned to another session, or created by reset timing.

Name and descriptor comparisons agree in all cases. No overload, bridge-target substitution, or representation collapse explains a selected miss.

## Bytecode and probe mechanisms

The ten cases split into two semantic families and six concrete mechanisms:

| Mechanism | Cases | Exact observation |
| --- | ---: | --- |
| Empty enum-constructor filter | 2 | Original `ALOAD ALOAD ILOAD INVOKESPECIAL RETURN`; the return probe is hit, but the filtered constructor is absent from `IMethodCoverage`. |
| Record-member filter | 2 | Generated `ALOAD GETFIELD ARETURN` accessors hit their return probes but are absent from `IMethodCoverage`. |
| Synthetic bridge filter | 1 | The exact synthetic/bridge `ALOAD INVOKESPECIAL CHECKCAST ARETURN` method hits its return probe but is absent from `IMethodCoverage`. |
| Synthetic switch-map class filter | 1 | The compiler-generated class executes; six of ten relevant probes are hit, but the class and method are absent from analyzed coverage. |
| Implicit exception before exit probe | 3 | JVMTI and ASM observe entry, then an invoked operation throws before any method exit/control-flow probe; JaCoCo reports zero covered instructions. |
| Implicit exception before probe-array access | 1 | Constructor entry occurs, argument creation for its delegating constructor call throws, and the class never registers in exec data; its sole probe is not hit. |

The compact case JSON files include original instruction sequences, access flags, probe IDs/states, exact counters, hashes, IDs, and timing. They demonstrate either hit probes that JaCoCo intentionally excludes from its analyzed method model, or physical entry without enough control-flow evidence to mark an instruction covered. Physical JVM `MethodEntry` and JaCoCo method coverage are therefore not semantically equivalent.

## Mandatory ASM-first enum contrast

The two enum constructors were also run as ASM → JaCoCo → JVMTI. Under canonical JaCoCo-first ordering, each original constructor has five instructions and matches JaCoCo's empty-enum-constructor filter. ASM-first prepends the collector's `LDC` and `INVOKESTATIC` entry call, producing seven instructions. That changed shape no longer matches the filter; JaCoCo assigns two relevant probes, both are hit, and reports seven covered instructions. The execution path and ASM method set do not change. This proves that the TASK 19 order effect comes from the filter's bytecode-shape recognition, not from different test execution.

## Decision and TASK 19 metrics

The focused sample found no systematic decoder, class-byte, exec-data, reset, or representation defect. The TASK 19 count remains 57 and its metrics do not require revision. The conclusion is deliberately bounded: the sample confirms multiple concrete ways in which physical entries are outside JaCoCo's covered-method semantics; it does not inspect all 57 edges.

## Reproduction and evidence

Run:

```sh
stp-spring-core-spike/task20/build_inventory.py
stp-spring-core-spike/task20/select_cases.py
stp-spring-core-spike/task20/run_selected.sh /private/tmp/task20-raw-fresh
TASK20_RAW_ROOT=/private/tmp/task20-raw-fresh stp-spring-core-spike/task20/analyze_cases.py
```

The pinned Spring checkout, Gradle 8.14.2, JDK 21.0.11, and TASK 19 agent paths are inherited from the TASK 19 scripts. Raw `.exec`, dumped and instrumented classes, full maps, and traces remain under `/private/tmp/task20-raw`; [`raw-artifact-hashes.json`](../stp-spring-core-spike/task20/evidence/raw-artifact-hashes.json) identifies the retained inputs. Git contains only the inventory, selection, scripts, compact case evidence, and this report.

The stop condition is satisfied at ten cases: more than five cases across more than three categories prove the broader method-entry/coverage semantic gap, both observed mechanism families are characterized, and no systematic validation-harness defect was found.
