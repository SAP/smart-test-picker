<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# ASM Git artifact audit

## Scope and totals

This audit covers every blob reachable from pre-cleanup `research/asm-codex` at `aebe205fc845fe16e356cb7c0aa44a76c7a8932f`, including history through cutoff `64c2636fe4f9224e7f3af4c9677c38fa38594115`. Before cleanup there were 2,084 unique reachable blobs totaling 709,396,099 uncompressed bytes, 14 over 5 MiB and 11 over 20 MiB. The current tree contained 1,897 files totaling 830,201,507 uncompressed bytes (tree paths count duplicate blob content separately). The originating ROUND 16 commits were primarily `12b7857` (ROUND 9), `38a487a` (ROUND 10), `405ab7c` (ROUND 11-13), and `28490cd` (ROUND 14-16).

After cleanup there are 815 unique reachable blobs totaling 29,487,911 uncompressed bytes, with none over 5 MiB or 20 MiB. The clean current tree contains 620 files totaling 26,743,277 uncompressed bytes. Its largest reachable blob is the reviewed 3,523,697-byte targeted ROUND 13 trace.

`REMOVE` means remove from the current tree and every rewritten post-cutoff commit. `KEEP` means the file is compact enough or has reviewed, unique evidentiary value. Sizes are bytes.

## Largest 50 reachable blobs before cleanup

| # | Size | Reachable path | Decision | Reason |
| ---: | ---: | --- | --- | --- |
| 1 | 60,784,231 | `stp-spring-core-spike/round16/evidence/asm-run-1-map.json` | REMOVE | Full raw map; compact ROUND 16 presence vectors remain. |
| 2 | 60,780,937 | `stp-spring-core-spike/round10/evidence/asm-run-1-map.json` (also ROUND 9 path) | REMOVE | Full raw map duplicated by content/path history. |
| 3 | 60,780,640 | `stp-spring-core-spike/round10/evidence/asm-run-3-map.json` | REMOVE | Full raw map; stability inventory remains. |
| 4 | 60,775,489 | `stp-spring-core-spike/round16/evidence/asm-run-5-map.json` | REMOVE | Full raw map; compact ROUND 16 evidence remains. |
| 5 | 60,773,125 | `stp-spring-core-spike/round10/evidence/asm-run-2-map.json` (also ROUND 9 path) | REMOVE | Full raw map duplicated by content/path history. |
| 6 | 60,770,218 | `stp-spring-core-spike/round16/evidence/asm-run-4-map.json` | REMOVE | Full raw map. |
| 7 | 60,768,889 | `stp-spring-core-spike/round16/evidence/asm-run-3-map.json` | REMOVE | Full raw map. |
| 8 | 60,763,662 | `stp-spring-core-spike/round16/evidence/asm-run-2-map.json` | REMOVE | Full raw map. |
| 9 | 60,744,844 | `stp-spring-core-spike/round10/evidence/trace-run-map.json` | REMOVE | Full diagnostic-run map; bounded trace remains. |
| 10 | 57,116,220 | `stp-spring-core-spike/round9/evidence/reference-descriptor-preserving-view.json` | REMOVE | Generated full view; compact ambiguity evidence remains. |
| 11 | 24,766,565 | `stp-spring-core-spike/round9/evidence/full-decoded-changed-test-diff.json` | REMOVE | Full decoded diff; classifications/summaries remain. |
| 12 | 17,719,303 | `stp-spring-core-spike/round9/evidence/reference-spring-core-map.json` | REMOVE | Copied external reference; pinned provenance remains. |
| 13 | 11,425,609 | `stp-spring-core-spike/round10/evidence/order-a-map.json` | REMOVE | Full focused-run map; controlled-order summary remains. |
| 14 | 11,372,679 | `stp-spring-core-spike/round10/evidence/order-b-map.json` | REMOVE | Full focused-run map; controlled-order summary remains. |
| 15 | 3,527,672 | `stp-spring-core-spike/round9/evidence/semantic-diff-reference-vs-asm.json` | REMOVE | Full semantic diff; compact derived analyses remain. |
| 16 | 3,523,697 | `stp-spring-core-spike/round13/evidence/round13-runtime-trace.json` | KEEP | Reviewed bounded, targeted trace containing unique causal observations. |
| 17 | 3,337,987 | `stp-spring-core-spike/round10/evidence/per-hit-causal-trace.json` | KEEP | Reviewed bounded trace directly supporting causal exclusions. |
| 18 | 1,959,038 | `stp-petclinic-spike/round5/evidence/asm-full-inventory.log` | REMOVE | Redundant runtime log; compact failure JSON remains. |
| 19 | 732,700 | `stp-spring-core-spike/round14/evidence/round14-runtime-trace.json` | KEEP | Small targeted causal trace. |
| 20 | 727,616 | `stp-spring-core-spike/round13/evidence/focused-run-3-trace.json` | KEEP | Targeted trace supporting present/absent contrast. |
| 21 | 705,054 | `stp-spring-core-spike/round13/evidence/focused-run-1-trace.json` | KEEP | Targeted trace. |
| 22 | 666,271 | `stp-spring-core-spike/round9/evidence/asm-inventory-comparison.json` | KEEP | Canonical compact inventory comparison. |
| 23 | 646,153 | `stp-petclinic-spike/round5/evidence/probe-org-springframework-samples-petclinic-owner-PetControllerTests.log` | KEEP | Sub-1 MiB targeted probe evidence. |
| 24 | 604,081 | `stp-spring-core-spike/round13/evidence/focused-run-4-trace.json` | KEEP | Targeted absent-run trace. |
| 25 | 585,363 | `stp-spring-core-spike/round13/evidence/focused-run-2-trace.json` | KEEP | Targeted trace. |
| 26 | 573,327 | `stp-spring-core-spike/round12/evidence/round12-recording.jfr` | REMOVE | Binary runtime recording; derived allocation/retention analyses remain. |
| 27 | 544,632 | `stp-spring-core-spike/round9/evidence/difference-classifications.json` | KEEP | Compact per-edge classification. |
| 28 | 475,356 | `round9/evidence/asm-run-1-test-results-final/binary/results.bin` | REMOVE | Generated Gradle binary test result; entire result tree removed. |
| 29 | 475,356 | `round9/evidence/asm-run-2-test-results/binary/results.bin` | REMOVE | Generated Gradle binary test result. |
| 30 | 475,356 | `round9/evidence/asm-run-2-test-results-final/binary/results.bin` | REMOVE | Generated Gradle binary test result. |
| 31 | 475,356 | `round9/evidence/asm-run-1-test-results/binary/results.bin` | REMOVE | Generated Gradle binary test result. |
| 32 | 446,543 | `stp-spring-core-spike/round11/evidence/cache-reset-map.json` | KEEP | Small focused controlled-experiment map. |
| 33 | 446,355 | `stp-spring-core-spike/round11/evidence/order-a-map.json` | KEEP | Small focused controlled-experiment map. |
| 34 | 445,410 | `stp-spring-core-spike/round11/evidence/gc-control-map.json` | KEEP | Small focused controlled-experiment map. |
| 35 | 444,299 | `stp-spring-core-spike/round11/evidence/order-b-map.json` | KEEP | Small focused controlled-experiment map. |
| 36 | 420,988 | `stp-petclinic-spike/round5/evidence/probe-org-springframework-samples-petclinic-owner-OwnerControllerTests.log` | KEEP | Sub-1 MiB targeted probe evidence. |
| 37 | 370,893 | `stp-petclinic-spike/spring-data-lifecycle/normalized-lifecycle.json` | KEEP | Compact normalized lifecycle evidence. |
| 38 | 356,432 | `stp-petclinic-spike/round5/evidence/probe-org-springframework-samples-petclinic-owner-PetControllerTests-ProcessCreationFormHasErrors.log` | KEEP | Sub-1 MiB targeted probe evidence. |
| 39 | 308,006 | `stp-spring-core-spike/round12/evidence/focused-map.json` | KEEP | Small targeted map for four selected tests. |
| 40 | 261,042 | `stp-petclinic-spike/round5/evidence/asm-full-inventory-failure-raw.json` | KEEP | Compact structured failure evidence. |
| 41 | 259,306 | `stp-petclinic-spike/round5/evidence/probe-org-springframework-samples-petclinic-owner-PetControllerTests-ProcessUpdateFormHasErrors.log` | KEEP | Sub-1 MiB targeted probe evidence. |
| 42 | 258,421 | `stp-spring-core-spike/round9/evidence/context-propagation-diagnostics.json` | KEEP | Compact derived diagnostics. |
| 43 | 258,279 | `stp-spring-core-spike/round9/evidence/reference-inventory.json` | KEEP | Canonical compact inventory. |
| 44 | 205,161 | `stp-petclinic-spike/round5/evidence/probe-org-springframework-samples-petclinic-PetClinicIntegrationTests.log` | KEEP | Sub-1 MiB targeted probe evidence. |
| 45 | 196,994 | `stp-spring-core-spike/round13/evidence/focused-run-1-map.json` | KEEP | Small targeted presence map. |
| 46 | 196,994 | `stp-spring-core-spike/round13/evidence/focused-run-3-map.json` | KEEP | Small targeted presence map. |
| 47 | 188,716 | `stp-spring-core-spike/round13/evidence/focused-run-4-map.json` | KEEP | Small targeted absence map. |
| 48 | 188,716 | `stp-spring-core-spike/round13/evidence/focused-run-2-map.json` | KEEP | Small targeted absence map. |
| 49 | 182,687 | `round9/evidence/asm-run-1-test-results/TEST-org.springframework.core.io.buffer.DataBufferTests.xml` | REMOVE | Generated report inside a duplicated test-result tree. |
| 50 | 182,669 | `round9/evidence/asm-run-1-test-results-final/TEST-org.springframework.core.io.buffer.DataBufferTests.xml` | REMOVE | Generated report inside a duplicated test-result tree. |

Paths abbreviated to `round9/evidence/...` in four rows have the prefix `stp-spring-core-spike/`. Full removed-path provenance and hashes are in `docs/removed-research-artifacts.md`.
