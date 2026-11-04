# MAVEN-ASM-06: Maven ASM fragment and execution evidence

## Status and provenance

**PASS** for this task's Maven implementation and local runtime acceptance.
Completed on 2026-11-04, including the full four-suite `--rerun-tasks` run.
This is not Jenkins enablement or publication/selection validation.

- Repository: `SAP/smart-test-picker`, branch `research/asm-codex`.
- Starting revision (MAVEN-ASM-05): `4cd9b00b0374a84585fd5d7bdfb6900a69100dd4`.
- Jenkins plugin inspected read-only: `main`,
  `8a0b793c3229eeb862f4a006788cf7fb76ef0b1d`.
- STP components remain `0.3.0-SNAPSHOT`; no schema versions changed.
- Maven 3.9.15, Gradle 8.14, SapMachine JDK 21.0.13.0.3+1-LTS,
  Surefire/Failsafe 3.5.3, fixture Jupiter 5.9.3.

The [scope and decision record](DECISION.md) was written before implementation.
The original fixture and the earlier spike evidence were not modified. New test
shapes are created in disposable copies of `schema-v3-reactor`.

## Step 0: production route, not test-only misuse

The plugin's `MavenExecutableMappingContract.MERGE_GOAL` names
`aggregate-reactor-coverage-fragment`. `StpCoverageMapStep` exports that goal;
the canonical Maven mapping Pipeline invokes it after `verify`. Neither the plugin
nor that Pipeline enumerates qualified module fragment paths or invokes
`merge-executable-target-fragments`.

The aggregator's unqualified-only lookup was therefore a shipped contract gap
for qualified inputs. It now resolves each assigned target against the active
reactor and reads preparation's corresponding qualified filenames. The canonical
Jenkins example itself remains unqualified; this task makes no claim of qualified
Jenkins runtime validation and changes no Jenkins code.

## Step 1: evidence decision and invariants

Chosen option **C**: exclude `unmapped/SKIPPED` from Maven executed coverage owners.
Keep the immutable ASM fragment unchanged. A Maven-local evidence writer derives
positive observations from that fragment:

| Fragment observation | Evidence v2 | Coverage treatment |
| --- | --- | --- |
| Mapped PASS, including genuinely empty coverage | EXECUTED | Preserve its own coverage |
| Unmapped FAILED | EXECUTED | No partial coverage |
| Unmapped SKIPPED: disabled or aborted-only | NON_EXECUTED | Preserve the SKIPPED entry, but not as an executed owner |
| Missing observation or incomplete collection | Rejected | No accepted evidence |

NON_EXECUTED is never computed as assignment minus observed tests. The production
fragment codec, target qualifier and assignment validator run before evidence is
written. At aggregation, EXECUTED and NON_EXECUTED are disjoint, their union is
exactly the assignment, and EXECUTED equals the executed coverage owners.

The shared assignment validator already supported positive SKIPPED non-execution;
the old Maven owner comparison contradicted it. The small ownership correction
also applies to the advanced explicit target merge, tested with the same module
files. It does not change the shared ASM projector or Gradle semantics.

JaCoCo is unaffected by the owner correction: its listener/reconciliation writes
positive `.non-executed` sidecars for aborted/skipped identities, not SKIPPED
unmapped entries. The existing skipped-report test now also asserts that its
generated schema-v3 fragment has neither mapped nor unmapped entries. Failed
JaCoCo tests and the missing-active-runtime regression retain their existing
behavior.

## Step 2: artifact and production call chain

Under each module's configured build directory, in `stp/`:

| Artifact | Filename | Writer |
| --- | --- | --- |
| Immutable coverage fragment, schema v3 | `coverage-fragment-v3<suffix>.json` | ASM agent |
| Diagnostic, `agent-shell-1` | `asm-agent-diagnostic<suffix>.json` | ASM agent |
| Execution evidence, version 2 | `execution-evidence-v2<suffix>.json` | `generate-coverage-fragment` |

The diagnostic now uses `smartTestPicker.moduleDiagnosticOutput`. It is not read
as execution evidence. Existing `moduleFragmentOutput` and `moduleEvidenceOutput`
retain their intended meanings. For unqualified targets the suffix is empty;
for `maven:module-b@failsafe@fixture-it` it is `-module-b_failsafe_fixture-it`.
Preparation and aggregation share the suffix implementation. Colliding qualified
paths are rejected rather than consumed twice.

Production sequence:

1. Preparation validates the assignment, scope and fork configuration, resolves
   the agent, and clears only the current target's previous outputs.
2. Surefire/Failsafe runs the assigned tests with the attached ASM agent. Other
   targets' outputs survive, allowing subsequent qualified aggregation.
3. At `verify`, the existing project binding of `generate-coverage-fragment`
   validates the agent fragment and atomically writes evidence v2. It neither
   deletes nor rewrites the agent fragment. `generate-reports` skips JaCoCo XML
   generation in ASM mode.
4. Reactor aggregation resolves assigned module/target files, checks exact
   ownership and binding, and writes a separate final fragment/evidence pair.

Unassigned modules do not fabricate empty completed fragments. Missing or invalid
assigned input fails closed. Preparation removes previous current-target outputs;
failed finalization removes old evidence, and failed aggregation removes its old
final pair. No fallback file search was introduced.

## Runtime evidence and exact identities

The permanent integration tests execute real Maven processes using the existing
functional-test repository and a process-isolated local Maven repository. They
do not rely on STP artifacts in the developer's global `.m2`. The consumer fixture
still declares core and enables Jupiter extension autodetection; ASM runs use
`-Djacoco.skip=true`.

### Unqualified full pipeline and cross-module isolation

`asmMappingCompletesFragmentAndReactorEvidenceWithoutJacoco` runs preparation,
collection, finalization and reactor aggregation in one Maven invocation.
The production codecs assert exactly these EXECUTED identities:

```text
maven:module-b::b.BTests#b2
maven:module-b::shared.SharedTest#same
```

The first owns both `a.AValue` and `b.BValue`. The second is PASS/COLLECTED_EMPTY
with neither edge. NON_EXECUTED and unmapped are empty. This proves that merely
instrumenting both classes does not give their coverage to an unrelated test.
The first test's exact method edges are `a.AValue#<init>()V`, `a.AValue#value()I`,
`b.BValue#<init>()V` and `b.BValue#value()I`.
See [the aggregate fragment](raw/unqualified/target/asm-shard.json),
[evidence v2](raw/unqualified/target/asm-evidence.json) and
[Maven log](raw/unqualified/maven.log).

### S1, S8, S9, S10, S11 and S12 across both providers

`asmOutcomeShapesAggregateAcrossSurefireAndFailsafeWithoutRewritingFragments`
collects the same logical identities under both canonical targets:

```text
maven:module-b@surefire@default-test
maven:module-b@failsafe@fixture-it
```

Each table entry is a complete identity when prefixed with either target and `::`.
Tests compare the full typed identities, not only totals.

| Shape / logical identity | Fragment placement | Evidence |
| --- | --- | --- |
| S1 `b.OutcomeTests#plain` | mapped PASS, `b.BValue` only | EXECUTED |
| Cross-module `b.BTests#b2` | mapped PASS, `a.AValue` and `b.BValue` | EXECUTED |
| Empty control `shared.SharedTest#same` | mapped PASS, no edges | EXECUTED |
| S8 `b.OutcomeTests#disabled` | unmapped SKIPPED | NON_EXECUTED |
| S8 `b.DisabledTests#disabledClass` | unmapped SKIPPED | NON_EXECUTED |
| S9 `b.OutcomeTests#failure` | unmapped FAILED, no partial coverage | EXECUTED |
| S10 `b.OutcomeTests#aborted` | unmapped SKIPPED | NON_EXECUTED |
| Failure dominance `b.OutcomeTests#mixed(org.junit.jupiter.api.RepetitionInfo)` | unmapped FAILED after one failed and one aborted invocation | EXECUTED |

S11 is the Failsafe collection; S12 is the combined qualified assignment. The
same logical tests remain distinct by provider. Aggregation produces 6 mapped
and 10 unmapped identities; evidence partitions all 16 into 10 EXECUTED and 6
NON_EXECUTED. Those totals supplement exact set equality assertions.

Input fragment bytes are compared before and after the second provider run and
after aggregation. The advanced explicit merge is also decoded and compared with
the reactor aggregate and its evidence. See:

- [Surefire collection log](raw/surefire/maven.log).
- [Failsafe collection log](raw/failsafe/maven.log).
- [Combined fragment](raw/both-providers/target/asm-shard.json).
- [Combined evidence](raw/both-providers/target/asm-evidence.json).
- [Production aggregation log](raw/both-providers/maven.log).

The outcome-inspection runs explicitly use Maven's `maven.test.failure.ignore=true`
to reach `verify` and inspect failure evidence. STP does not set that option.
`asmFailedTestStillFailsMavenBeforeVerify` separately omits it and asserts a nonzero
exit, FAILED-only agent fragment, and absent execution evidence because Maven
never reached `verify`. Its [expected failure log](raw/failure-exit/maven.log)
is not a failed regression. This distinction preserves the original test failure.

## Commands and reproducibility

The checked-in integration test constructs assignments and test shapes and calls:

```sh
mvn -U --batch-mode --no-transfer-progress --settings functional-test-settings.xml \
  com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:prepare-reactor-executable-mapping \
  verify \
  com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:aggregate-reactor-coverage-fragment \
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.coverageCollector=ASM \
  -DsmartTestPicker.asm.includes=a.,b. -Djacoco.skip=true \
  -DsmartTestPicker.testsFile="$ASSIGNMENT" \
  -DsmartTestPicker.fragmentOutput="$FRAGMENT" \
  -DsmartTestPicker.evidenceOutput="$EVIDENCE"
```

Here the three file variables denote absolute paths generated by the test harness;
they are not inputs users must invent to reproduce the test. The existing fixture
supplies revision `reactor-revision` and shard `reactor-shard`. These are synthetic
contract bindings, not claims of a published map for a Git commit.

For each qualified collection, the harness adds `-Pfailsafe`,
`-DsmartTestPicker.executionType=surefire|failsafe`,
`-DsmartTestPicker.executionId=default-test|fixture-it` and the explicit
failure-ignore flag, using that provider's assignment and omitting the aggregate
goal. It then invokes the aggregate goal alone against the union assignment.
Exact argument construction is in
[ReactorHeadTestInventoryMojoIntegrationTest](../../smart-test-picker-maven/src/test/java/com/sap/oss/smarttestpicker/maven/ReactorHeadTestInventoryMojoIntegrationTest.java).

Reproduce the required full run from the repository root, with Maven on PATH:

```sh
./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test \
  --rerun-tasks --console=plain > verification/maven-asm-06/full-suites.log 2>&1
```

## Negative checks and regression results

`AsmMappingFinalizationTest` invokes the production mojos and codecs. It covers:

- Missing fragment and missing evidence.
- Missing assigned observations, including an empty fragment falsely marked complete.
- Duplicate, unexpected and wrong-target identities.
- Wrong revision or shard binding and wrong evidence target/version.
- EXECUTED/NON_EXECUTED overlap and duplicate evidence identities.
- Incomplete collection, missing execution/non-execution evidence, and incorrectly
  treating SKIPPED as EXECUTED.
- Removal of stale successful outputs on rejection without rewriting the input
  agent fragment.

Final full-suite results:

| Suite | Baseline | Tests | Skipped | Failures / errors |
| --- | ---: | ---: | ---: | ---: |
| common | 333 | 333 | 11 | 0 / 0 |
| core | 29 | 29 | 5 | 0 / 0 |
| Maven | 97 | 127 | 0 | 0 / 0 |
| Gradle plugin | 46 | 46 | 0 | 0 / 0 |

Overall: 535 tests, 519 passed, 16 existing skips, zero failures/errors.
All baselines are held. Maven gains 27 focused finalization/aggregation cases
and 3 real-process integration tests. The Gradle production code and its suite
are unchanged. The full command exited 0: `BUILD SUCCESSFUL in 10m 8s`,
`52 actionable tasks: 52 executed`.

Evidence:

- [Full console log](full-suites.log).
- [Suite totals extracted from JUnit XML](test-results/suite-summary.json).
- [Finalization and rejection tests](test-results/TEST-com.sap.oss.smarttestpicker.maven.AsmMappingFinalizationTest.xml).
- [Real Maven integration tests](test-results/TEST-com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.xml).
- [JaCoCo fragment-generation regressions](test-results/TEST-com.sap.oss.smarttestpicker.maven.GenerateCoverageFragmentMojoTest.xml).

The first focused run had one incorrect new-test expectation: it expected
`OutcomeTests#plain` to cover `a.AValue`, although its body calls only `b.BValue`.
The assertion was corrected; the existing cross-module `BTests#b2` still requires
both classes. [The first log](focused.log) is preserved, alongside the successful
[focused rerun](focused-final.log). No product behavior was weakened to satisfy
that assertion.

Relative document links and the staged Java/Markdown whitespace check pass.
Unfiltered `git diff --cached --check` reports Maven's original trailing spaces
in the retained raw logs; those logs were preserved rather than reformatted.
Every file added for this task is below 5 MiB. The repository-wide [large-file check](large-files.log)
still rejects two unchanged historical artifacts: `EI-10/artifacts/published-r0-map.json`
(30,045,626 bytes) and `EI-11/artifacts/deployed-smart-test-picker.hpi`
(10,450,073 bytes). They were not modified or reclassified by this task.

## Limits and unchanged behavior

- No shared ASM projector/listener, schema, identity, Gradle or selector code changed.
- JaCoCo regression coverage remains part of the full Maven run; its
  `schemaV3FailsClosedWhenTestForkHasNoActiveJacocoRuntime` method is unchanged.
- This is Maven mapping evidence, not a redesign of managed selected-execution
  evidence (`MavenExecutionEvidence` is unchanged).
- Reactor-derived instrumentation scope, per-fork output/merge, S7 ClassSource-only
  Vintage identity support, Jenkins ASM enablement, publication and selection
  remain out of scope. No Jenkins runtime claim is made.
- Preparation/finalization does not make a failed Maven test command successful.
  Failure handling must preserve that original result even when evidence is
  inspected separately.
