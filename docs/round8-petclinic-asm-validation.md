<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# Round 8: PetClinic ASM/context-propagation validation

## Conclusion

The current ASM agent passes the pinned PetClinic comparison inventory twice and is semantically stable. Both
runs have the same 25 mapped tests, 239 test-to-method edges, 119 test-to-class edges, outcomes, setup semantics,
and unattributed-event counts. Compared with the canonical JaCoCo/STP reference, the only differences are the
same six initialization constructors already explained by JaCoCo's resetPreparing/reset-at-test-finish boundary.
No current result indicates an ASM collector defect.

PetClinic did reach context capture hooks, but all 41 captures in each run had `test=none`. There was no context
attach or restore, so this inventory did not exercise a Round 6/7 propagation path under an active test. Context
propagation neither added an edge nor removed a false attribution in this evaluation. No unsupported boundary is
claimed as supported, and Spring Core work was not started.

## Baseline

The STP branch is `research/asm-codex` at commit
`64c2636fe4f9224e7f3af4c9677c38fa38594115` (`Document Round 5 PetClinic ASM validation`), plus the pre-existing
uncommitted Round 6 and Round 7 working state. That state comprises the logical `TestExecutionContext`, runtime
capture/attach/restore wrappers, exact executor and CompletableFuture call-site support, scheduled-executor
bridges, common Runnable-based raw `Thread` constructors, JDK 21 virtual-thread start support, their tests and
fixtures, and the corresponding documentation. The historical `docs/round6b-thread-boundaries.md` filename is
treated as the Round 7 result, as directed. Round 8 did not alter those implementation files.

The evaluation subject is Spring PetClinic commit
`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`. Both runs verified the checkout HEAD before execution. The runtime
was OpenJDK `17.0.19`; therefore the already-supported JDK 21 virtual-thread path cannot be exercised here.

The inventory is the same Round 5 mapping subset for which the reference map exists: 27 tests across
`ValidatorTests`, `PetTypeFormatterTests`, `PetValidatorTests` and its nested error class,
`ClinicServiceTests`, `CrashControllerTests`, `I18nPropertiesSyncTest`, and `VetTests`. The two I18n tests execute
and pass but have no PetClinic production edges. The four Docker-dependent tests and the 42 tests that the old
Round 5 ASM verifier defect prevented from mapping are not silently added: doing so would create an inventory
with no canonical reference counterpart.

## Reference

The canonical artifact is
`stp-petclinic-spike/round5/evidence/jacoco-stp-map-original.json`, preserved for this round as
`stp-petclinic-spike/round8/evidence/reference-map.json`. It is canonical because the Round 5 runner copied it
unchanged from PetClinic's generated `target/test-coverage-map.json` after invoking the existing production
JaCoCo listener/report/coverage-map pipeline. The other PetClinic artifacts are ASM experiments or derived
views, not replacement reference maps.

`reference-method-map.json` is the retained Round 5 descriptor-enriched semantic view derived from the
per-test JaCoCo XML. It is used for method-level comparison because the production map stores method names
without JVM descriptors. It does not replace or modify the canonical map.

Reference metrics:

| Metric | Value |
| --- | ---: |
| revision | `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` |
| inventory | 27 |
| mapped / unmapped | 25 / 2 |
| class edges | 124 |
| method edges | 245 |
| outcome | 27 passed |
| explicit setup edges | 0 |

The map schema has no explicit setup relationship. JUnit `beforeEach`/`afterEach` activity is inside the leaf
test interval; `beforeAll`/`afterAll` is outside it. Accordingly, setup-edge comparison is an empty-set
comparison rather than an inferred relationship.

## ASM result

Both runs used the actual shaded agent through PetClinic Maven Surefire with identical configuration:
`includes=org.springframework.samples.petclinic.;debug=true;instrumentation=on`, alphabetical order, one fresh
forked JVM per Maven run, and the same eight selectors. Debug was enabled only to collect capture/attach evidence.

| Metric | ASM run 1 | ASM run 2 |
| --- | ---: | ---: |
| inventory | 27 | 27 |
| mapped / unmapped | 25 / 2 | 25 / 2 |
| passed / failed / skipped | 27 / 0 / 0 | 27 / 0 / 0 |
| class edges | 119 | 119 |
| method edges | 239 | 239 |
| explicit setup edges | 0 | 0 |
| raw / unique method hits | 584 / 47 | 584 / 47 |
| `LATE_EVENT` | 19 | 19 |
| `NO_ACTIVE_TEST` | 0 | 0 |
| `UNKNOWN_CONTEXT` | 0 | 0 |
| transformation errors / method-ID collisions | 0 / 0 | 0 / 0 |

The unmapped set is identical to the reference:

* `I18nPropertiesSyncTest#checkI18nPropertyFilesAreInSync`
* `I18nPropertiesSyncTest#checkNonInternationalizedStrings`

## Semantic comparison

Reference versus ASM run 1 changes two tests, or 8.0% of the 25 mapped tests. There are six reference-only
method edges, no ASM-only method edges, five reference-only class edges, no ASM-only class edges, and no setup
or outcome differences.

| Test | Reference-only methods | ASM-only methods | Classification |
| --- | --- | --- | --- |
| `ClinicServiceTests#shouldFindVets` | `PetClinicApplication#<init>()V`, `Owner#<init>()V`, `Pet#<init>()V`, `PetType#<init>()V`, `Visit#<init>()V` | none | `SETUP_OR_INITIALIZATION` |
| `CrashControllerTests#triggerException` | `CrashController#<init>()V` | none | `SETUP_OR_INITIALIZATION` |

ASM observed all six constructors, but as `LATE_EVENT` initialization after an earlier test, not as coverage of
the reference-assigned next test. JaCoCo's per-test probe reset at test finish carries such between-test probes
into the next completed session. The two new ASM runs reproduce the quarantine, and there is no active-context
capture/attach evidence for these events; therefore they are not classified as async propagation.

At global-union level, 12 classes and 45 methods occur in both maps. `PetClinicApplication` is the sole class
missing from ASM's test buckets. `PetClinicApplication#<init>()V` and `CrashController#<init>()V` are the two
global methods missing from ASM's test buckets; both remain visible in ASM's global observed facts and late-event
diagnostics. There are no global classes or methods only in ASM.

## Repeatability

ASM run 1 and run 2 have zero changed tests, zero directional class-edge differences, zero directional
method-edge differences, zero setup differences, and zero outcome/status differences. Raw JSON is not used as
the equivalence criterion. Normal JVM timing and object-identity text in debug logs is intentionally ignored.

## Context-propagation findings

Each run contains 41 `[stp-context] capture` records. Every record has `test=none`; each run has zero `attach`
and zero `restore/cleanup` records. This is direct runtime evidence that capture hooks were invoked during
bootstrap, pool maintenance, and shutdown, but no task captured an active `TestIdentity`. Task class names and
dependencies are not used to infer which propagation mechanism executed.

Consequently:

1. PetClinic exercised no newly supported propagation path under an active test in this inventory.
2. No supported executor, CompletableFuture, scheduled, raw-thread, or virtual-thread mechanism can be claimed
   as an observed propagated mechanism here.
3. No production method moved from global/unattributed ownership to a concrete test because of propagation.
4. No previous false attribution disappeared because of propagation.
5. None of the 19 late hits has matching propagated-work attach evidence.
6. There are zero events with no safe logical owner (`NO_ACTIVE_TEST=0`, `UNKNOWN_CONTEXT=0`). The 19 late hits
   retain a historical owner but are deliberately unsafe to publish as normal test coverage after test finish.

This negative PetClinic observation does not weaken the positive Round 6/7 fixture evidence; it only limits the
claim for this subject. Unsupported direct `ForkJoinTask`, overriding `Thread` subclass, reflection/MethodHandle,
and preloaded-caller boundaries were neither inferred nor extended.

## Remaining uncertainty

The 27-test canonical-reference inventory does not execute active-test async propagation, so PetClinic provides
instrumentation compatibility and map-stability evidence but no subject-specific positive propagation evidence.
Running the other 42 environment-executable tests might exercise different behavior, but it would not be a valid
comparison to this canonical reference and is outside this validation's fixed inventory. There is no unresolved
changed edge: all six are backed by observed initialization/late-event evidence.

## Regression safety

After both PetClinic runs, `./gradlew test --rerun-tasks` completed successfully: 57 actionable tasks executed.
The 39 generated JUnit suite files contain 369 tests, zero failures, zero errors, and zero skips. This includes
the existing STP tests, Round 6 propagation tests, and Round 7 scheduled/raw-thread/boundary tests. Both PetClinic
runs also passed all 27 selected tests. `git diff --check` passes.

## Preserved artifacts and reproduction

All paths below are under `stp-petclinic-spike/round8/evidence/`:

* `reference-map.json`, `reference-method-map.json`, `reference-inventory.json`
* `asm-run-1-map.json`, `asm-run-2-map.json`
* `asm-run-1-runtime.log`, `asm-run-2-runtime.log` (removed during ROUND 18 as duplicated generated console output; see `docs/removed-research-artifacts.md`)
* `asm-run-1-surefire-reports/`, `asm-run-2-surefire-reports/` (removed during ROUND 18 as reproducible generated reports; the runner recreates them)
* `semantic-diff-reference-vs-asm.json`
* `semantic-diff-asm-run-1-vs-run-2.json`
* `semantic-comparison-full.json`
* `runtime-attribution-diagnostics.json`
* `changed-edge-classification.json`
* `run-manifest.json`

Reproduction entry points are `stp-petclinic-spike/round8/reproduce.sh`, `run_round8.rb`, and
`compare_round8.rb`. Mockito's inline mock maker needs an environment in which JVM agent self-attachment is
permitted.

```text
REFERENCE
revision: 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
inventory: 27
mapped: 25
class edges: 124
method edges: 245

ASM RUN 1
inventory: 27
mapped: 25
class edges: 119
method edges: 239

ASM RUN 2
inventory: 27
mapped: 25
class edges: 119
method edges: 239

REFERENCE VS ASM
changed tests: 2 (8.0% of mapped tests)
class edge diff: 5 reference-only, 0 ASM-only
method edge diff: 6 reference-only, 0 ASM-only
global missing classes: 1
global missing methods: 2

ASM REPEATABILITY
changed tests: 0
class edge diff: 0 / 0
method edge diff: 0 / 0

CONTEXT PROPAGATION
observed mechanisms: none under an active TestIdentity
attribution improvements: 0
remaining unattributed: 19 LATE_EVENT; 0 NO_ACTIVE_TEST; 0 UNKNOWN_CONTEXT
late events: 19 in each run; 0 with propagated-work attach evidence

CONCLUSION
The current ASM implementation is stable and correct for the canonical PetClinic comparison inventory. Its six
reference-only edges are setup/initialization attribution artifacts, not propagation regressions or collector
loss. PetClinic supplied no active-context propagation exercise, so no broader propagation claim is made.
```
