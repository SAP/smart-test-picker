# STP-UNMAPPED-01: preserve passing siblings

> Historical policy snapshot: the structural-fatal exceptions below were
> superseded by the [diagnostic-only health amendment](../stp-unmapped-01-amendment/REPORT.md).
> This report and its raw validation results describe the preceding implementation;
> they are not fresh validation of the amended policy.

## Scope and status

Implementation on `research/asm-codex`, starting from
`fa2d0f44fc074ba018a495b1b15bdafcd8a434f9`, inspected 2026-11-11.
No CI, Hybris, ANT, CLI-wrapper, propagation adapter, JaCoCo or performance work.
The pre-existing untracked `examples/`, `verification/maven-asm-shapes/`,
`verification/maven-asm-spike/`, and `verification/stp-integrity-02/` were preserved.

**COMPLETE.** The projector and Maven finalizer are implemented. All three fresh
subject gates and the full eight-suite rerun pass. Changes are not committed or
pushed.

## Implementation, in the requested order

### 1. Project only the broken identities as unmapped

[AsmCoverageFragmentProjector.java][projector], lines 42–81:

- A physical observation without a logical identity emits its existing
  `unsupported-identity:<platformUniqueId>` diagnostic and contributes no invented
  logical key. It no longer invalidates known siblings.
- Unfinished or result-less groups become `COLLECTION_FAILED`, without clearing
  completion. Explicitly owned errors also affect only the matching group.
- Failed groups remain `FAILED`; wholly aborted groups remain `SKIPPED`.
- An error whose owner is absent or belongs to another target retains the
  original diagnostic and `unresolved-error-ownership` diagnostic. It does not
  invalidate observations for unrelated identities.
- All error strings remain in the projection diagnostics (line 101) and the
  unchanged agent diagnostic writer. No severity is inferred by parsing text.

[CollectorIntegrity.java][integrity], lines 21–28, now derives shard-fatal health
only from structural counters/runtime state and obtains affected identities from
all structured errors. `CollectorError` retains its existing producer severity
labels for compatibility; `CollectorError.shardFatal()` describes that producer
classification, **not the projector's completion decision**. Its Javadoc now
states this distinction. A producer's SHARD_FATAL label with no owned identity
does not alone invalidate tests.

**What reaches the next stages after this change alone:** the validator/joiner
can accept a complete, exactly accounted fragment with unfinished identities in
unmapped. However, the old Maven outcome whitelist still rejects those entries,
and an unsupported logical identity still leaves a missing assignment member.

### 3. Accept complete identity-local collection failures in Maven

[GenerateCoverageFragmentMojo.java][finalizer], lines 204–218:

The assignment validator runs before the reason whitelist. The whitelist accepts
`SKIPPED`, `FAILED`, and `COLLECTION_FAILED`; it continues to reject `TIMEOUT`
and any future unapproved reason. Positive non-execution still comes only from
`SKIPPED`, never from assignment subtraction.

**What reaches the next stages after this second change:** complete, exactly
accounted failed/unfinished/explicitly affected identities survive finalization,
evidence v2, reactor aggregation and joining. Unsupported identities still leave
an accounting gap until absorption is applied.

### 2. Absorb the authoritative assignment complement

[GenerateCoverageFragmentMojo.java][finalizer], lines 180–202 and 225–281:

1. Decode the existing fragment, validate target, and reject incomplete fragments
   or revision/shard mismatches. Absorption never flips completion to repair an
   incomplete fragment.
2. Reject any reported identity outside the target's assignment.
3. Compute `assigned - mapped - unmapped` exactly, adding every member as
   `COLLECTION_FAILED`. Do not correlate these members with particular unsupported
   platform IDs. The known cause is **absence from the fragment**, not a guessed
   reason for the absence.
4. Run assignment validation and the outcome whitelist on the normalized result.
5. Only if absorption occurred, atomically replace the module fragment with the
   normalized fragment and its audit. Healthy fragment bytes are not rewritten.
   The existing evidence invalidation/write sequence ensures a failed write
   cannot leave old evidence advertising success.

The module fragment and evidence-v2 JSON carry this Maven-owned audit extension:

```json
"assignmentAbsorption": {
  "reason": "ASSIGNED_TEST_NOT_REPORTED",
  "executionObserved": false,
  "count": 1,
  "tests": ["maven:module::fixture.Tests#test0000"]
}
```

Repeated finalization validates and preserves the audit (including exact count
and COLLECTION_FAILED membership). It does not lose the provenance simply because
the second invocation sees normalized bytes. The agent diagnostic remains an
unmodified record of agent observations. No inventory enters the agent and
`FragmentProjectionConfig` is unchanged.

**Evidence-v2 qualification:** its existing `EXECUTED` bucket is constructed from
mapped plus non-SKIPPED unmapped coverage owners. Absorbed identities therefore
appear there too, but are explicitly marked `executionObserved=false` in the
audit. This is **not proof that an unreported test physically executed**.
`NON_EXECUTED` is not populated by subtraction. Generic codecs and reactor
aggregation preserve the unmapped identities but do not carry this Maven audit
extension into the published map; retain the module artifacts for that provenance.
No map schema, logical/executable identity format, selector or joiner was changed.

**What now reaches the next stages:** all assigned identities are accounted for,
including unsupported observations' missing logical members. Healthy siblings
retain exactly their original TestCoverage. The validator, finalizer, reactor
aggregator, joiner and actual selector accept the result. An absent fragment,
incomplete fragment, unexpected identity, duplicate or wrong binding still fails.
Missing whole shards remain outside this task.

The complement detects absent **logical assignment members**, not an exact
association between unsupported physical observations and those members. If an
unsupported physical invocation belonged to a logical identity already reported
by another invocation, this arithmetic alone would not detect that relationship.
This task does not solve the platform-ID binding or listener identity gap.

## Fatal conditions and why they remain fatal

| Condition | Decision and justification |
| --- | --- |
| Runtime uninitialized | Fatal. There is no working observation service behind apparent empty coverage; accepting those sets as measured passing-test coverage would be false. |
| Transformation failure counter > 0 | Fatal. Failed instrumentation may leave executing included production methods invisible. Recorded positive hits can be real, but the passing test's set is not trustworthy as a basis for excluding tests for changes to the uninstrumented code. This is structural measurement failure, not an unknown owner's message. |
| Method-ID collision counter > 0 | Fatal. A recorded ID can denote multiple method identities; the measurement cannot unambiguously establish the covered method set. The counter and its meaning are unchanged. |
| ERROR setup diagnostic | Still incomplete, by the unchanged projector branch at lines 37–39. Shared-context and unobserved ForkJoin execution remain rejected. |
| Ordinary structured agent error with explicit owners | Only matching owned groups become unmapped. |
| Unowned unsupported/executor/ForkJoin/scheduled or other error message | Retained diagnostically; does not discard owned tests. No class-name or text-prefix ownership inference. |
| Missing/failed artifact production, zero instrumented scope | Existing outer guards remain: a missing fragment/diagnostic or zero transformed classes is rejected; serialization and file-publication code was not relaxed. |

The user-requested unowned-error policy is **not proof that propagation errors
cannot omit edges from a passing sibling**. It preserves that sibling's measured
observations; it adds no causal ownership evidence for work the collector missed.
Known broken and assignment-absent identities are conservative because they are
mandatory. Broader attribution soundness is not established by this task, nor is
Hybris runtime suitability. The [current boundary documentation][boundary] records
this distinction explicitly.

## Permanent regression evidence

[AsmUnmappedEndToEndTest.java][e2e] is in the Maven production test source set.
It constructs runtime observations, invokes the real projector, qualifier, codecs,
Maven finalization and reactor aggregation, assignment validator, joiner,
`ExecutableCoverageMapPublication.alignExpectedInventory`, and
`ExplicitPrSelectorFlow`. Publication alignment and a serialized PUBLISHED map
are local; no controller/storage operation is claimed.

| Case, 3,000 assigned identities | Mapped | Unmapped | Selected with no changes |
| --- | ---: | ---: | --- |
| Two failed tests | 2,998 | 2 FAILED | Exactly `fixture.Tests#test0000` and `fixture.Tests#test0001` |
| Two unfinished tests | 2,998 | 2 COLLECTION_FAILED | Exactly the same two identities |
| Two finished observations with no result | 2,998 | 2 COLLECTION_FAILED | Exactly the same two identities |
| One unsupported physical identity | 2,999 | 1 absorbed COLLECTION_FAILED | Exactly `fixture.Tests#test0000` |
| Unowned agent error | 3,000 | 0 | None; status NONE |

Every case asserts exact identity sets, sibling TestCoverage equality before and
after finalization, evidence ownership, equality of finalized and aggregated
fragments, complete joining and exact selection. These are synthetic observation
regressions, not a claim that 3,000 application JUnit bodies ran.
The actual normalized fragments, published-map bytes and selected identities are
retained under [raw/regressions](raw/regressions/); the exact outcomes are also
listed in [regression-results.json](raw/regression-results.json).

[AsmMappingFinalizationTest.java][finalization-tests] additionally proves:
absorption of one or all missing assignment members; audit persistence on retry;
rejection of unexpected identities, duplicate reports, wrong target/revision/shard,
incomplete/missing fragments and TIMEOUT; stale evidence invalidation; and existing
reactor evidence contradiction rejection. Its old missing-observation rejection
cases became positive absorption cases; they were not silently removed.

[CollectorIntegrityTest.java][integrity-tests] verifies local ownership, unowned
diagnostic retention, unchanged structural failures, no invented unsupported
identity, and real downstream selection. Existing projector setup-diagnostic
regressions remain in the runtime suite. Maven adds only a **test** dependency on
stp-runtime to exercise the real projector; published plugin dependencies are
unchanged.

The first focused run failed only because the newly added unowned-error test
expected SELECTED instead of the correct NONE for an empty selection. The assertion
was corrected; the [initial log](raw/focused.log) and
[successful rerun](raw/focused-rerun.log) are retained. No product behavior was
changed to satisfy that mistaken assertion.

The [initial full rerun](raw/full-suites-initial.log) completed all eight suites
but failed two existing `AsmMappingParityIntegrationTest` assertions: the early
binding check used a combined message rather than the established distinct
revision/shard messages. The production check was corrected to preserve those
exact messages; the tests were not weakened. The [original diagnostics](raw/initial-diagnostic-failures/)
and XML are retained. All three subject gates were then repeated against the
final rebuilt artifacts; the first gate runs remain in [initial-gates](raw/initial-gates/).

## Mandatory gates

- **PetClinic: PASS**, fresh pinned revision
  `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, unchanged 69-test H2 scope.
  Fragment SHA-256:
  `0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9`.
  [Fragment](raw/implementation/petclinic-after/stp/coverage-fragment-v3.json),
  [diagnostic](raw/implementation/petclinic-after/stp/asm-agent-diagnostic.json).
- **ASM-12: PASS (still rejected)**. Fresh shared cached-context run has
  `completed=false`; finalization and aggregation exit 1. Validator and join both
  report `IllegalArgumentException: Fragment collection is not completed`.
  The closed-container ASYNC_SETUP_UNSUPPORTED/ERROR and LATE_EVENT remain.
  [Validation](raw/implementation/asm12/validation.json).
- **ASM-13: PASS (still rejected)**. Fresh parallel run has `completed=false`;
  finalization and aggregation exit 1. The validator/join rejection is identical;
  the unobserved ForkJoin execution ERROR setup diagnostic remains.
  [Validation](raw/implementation/asm13/validation.json). Neither negative run
  produces an accepted candidate map. No gate was changed to accept one.
- **Eight-module full suites: PASS**, fresh `--rerun-tasks --continue`:
  **750 tests, 733 passed, 17 skipped, zero failures/errors**. BUILD SUCCESSFUL
  in 8m 53s, 68 tasks executed. This exceeds the historical 744 / 727 / 17 baseline
  by six passing cases, with no additional skips.
- **PERF hot path: PASS (unchanged)**. Whole-file comparison against fa2d0f4 for
  RuntimeHooks, RuntimeContextService, MethodHitOwner, MethodProbe,
  RuntimeEventAggregator and TestExecutionContext; body comparison for agent
  methodHit, catalog ordinal/probe/counter operations and bytecode instrumentation.
  PetClinic still records 3,816 total raw hits and 3,079 leaf raw hits. No performance
  rerun or new optimization was performed.

[Machine-readable gate assertions](raw/implementation/correctness-gates.json)
are generated by [check-gates.py](check-gates.py). The [gate runner](validate.py)
reuses existing pinned-subject commands in fresh directories and resolves current
STP artifacts from per-run snapshots of the isolated functional repository.
Maven 3.9.15, SapMachine JDK 21.0.13.0.3, macOS aarch64; exact toolchain output
is retained with each subject run.

| Module | Tests | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: | ---: |
| smart-test-picker-common | 334 | 323 | 11 | 0 |
| smart-test-picker-core | 29 | 24 | 5 | 0 |
| smart-test-picker-maven | 157 | 157 | 0 | 0 |
| smart-test-picker | 58 | 58 | 0 | 0 |
| stp-runtime | 66 | 66 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| stp-agent | 49 | 48 | 1 | 0 |
| smart-test-picker-cli | 36 | 36 | 0 | 0 |

[Full console log](raw/full-suites.log), [counts](raw/suite-counts.json), and
[all fresh JUnit XML](raw/test-results/) are retained. [finish.py](finish.py)
asserts per-module baseline counts and unchanged skip counts. It also verifies
identical JAR entry contents between the tested agent/plugin and each subject
gate's isolated artifacts: [agent provenance](raw/agent-provenance.json) and
[Maven plugin provenance](raw/maven-plugin-provenance.json). Archive metadata
may differ; no entry contents differ.

## Reproduction and delivery

```sh
GRADLE_USER_HOME="$PWD/verification/stp-perf-01/.work/gradle-home" \
  ./gradlew publishStpToIsolatedRepository --console=plain
python3 verification/stp-unmapped-01/validate.py petclinic-after
python3 verification/stp-unmapped-01/validate.py asm12
python3 verification/stp-unmapped-01/validate.py asm13
python3 verification/stp-unmapped-01/check-gates.py
PATH="/Users/D061177/Programs/apache-maven-3.9.15/bin:$PATH" \
GRADLE_USER_HOME="$PWD/verification/stp-perf-01/.work/gradle-home" \
  ./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-runtime:test \
  :stp-junit-adapter:test :stp-agent:test :smart-test-picker-cli:test \
  --rerun-tasks --continue --console=plain
python3 verification/stp-unmapped-01/finish.py
```

The gate runner deliberately refuses existing output directories; retain them
and use a new task directory for a later rerun. Console logs stay under `raw/`.
No commit or push has been made for this task.

[projector]: ../../stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/AsmCoverageFragmentProjector.java
[integrity]: ../../stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/CollectorIntegrity.java
[finalizer]: ../../smart-test-picker-maven/src/main/java/com/sap/oss/smarttestpicker/maven/GenerateCoverageFragmentMojo.java
[e2e]: ../../smart-test-picker-maven/src/test/java/com/sap/oss/smarttestpicker/maven/AsmUnmappedEndToEndTest.java
[finalization-tests]: ../../smart-test-picker-maven/src/test/java/com/sap/oss/smarttestpicker/maven/AsmMappingFinalizationTest.java
[integrity-tests]: ../../stp-runtime/src/test/java/com/sap/oss/smarttestpicker/runtime/CollectorIntegrityTest.java
[boundary]: ../../docs/asm-coverage-mapping-current-state.md
