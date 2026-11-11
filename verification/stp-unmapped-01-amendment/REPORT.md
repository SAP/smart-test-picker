# STP-UNMAPPED-01 amendment: diagnostic-only collector health

**COMPLETE: implementation and all four correctness gates pass.** Work started on
`research/asm-codex` at `fa2d0f44fc074ba018a495b1b15bdafcd8a434f9`, on top of the
uncommitted STP-UNMAPPED-01 implementation. This amendment supersedes the three
structural-fatal exceptions in that report. Previous raw evidence is retained
unchanged; fresh evidence belongs under this directory. Validation was completed
before committing or pushing this amendment; delivery is recorded in Git history.

Collector errors and integrity counters do not clear fragment completion or
invalidate passing siblings. Explicitly owned attribution failures remain local
unmapped entries. ERROR setup diagnostics retain their separate rejection
contract. Missing artifacts, zero instrumentation scope, binding/assignment
contradictions and missing whole shards are not relaxed by this amendment.

No CI, Hybris, performance, selector or propagation-adapter work is authorized
or performed here. All validation is local.

## Implementation

- [AsmCoverageFragmentProjector.java](../../stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/AsmCoverageFragmentProjector.java),
  lines 35–39: completion starts true. The **only** assignment of false is the
  existing ERROR setup-diagnostic branch. The integrity-wide branch that replaced
  every observation with COLLECTION_FAILED is removed entirely.
- [CollectorIntegrity.java](../../stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/CollectorIntegrity.java),
  lines 7–25: remove `shardFatal()`. Keep runtime state, counters, diagnostics and
  explicit identity ownership; do not reinterpret or reset their values.
- Projector lines 62–76: unfinished/result-less or explicitly affected identities
  become COLLECTION_FAILED; wholly aborted identities remain SKIPPED; failures
  remain FAILED. Passing siblings keep their measured coverage. Unsupported
  physical identities are not invented: the existing Maven assignment complement
  supplies the absent logical entries at finalization.
- All strings remain in projection diagnostics (line 95) and agent diagnostic
  output. `CollectorError`'s legacy SHARD_FATAL label/helper are retained solely
  as producer metadata for compatibility; **neither has any production consumer
  deciding completion or test outcomes**. Explicit owners, not message text or
  severity labels, determine local attribution failure.
- Maven finalization behavior is unchanged from STP-UNMAPPED-01. Its comment now
  describes incomplete fragments rather than the removed health-based rejection.
  Complete COLLECTION_FAILED entries survive assignment validation, evidence v2,
  reactor aggregation, joining, publication alignment and mandatory selection.

## Permanent regressions

All tests below are in production module test source sets, not just verification.

1. `CollectorIntegrityTest.healthCountersAndRuntimeStateDoNotChangeCoverageOrCompletion`
   compares entire fragments with a healthy control for missing runtime,
   transformation failure, ID collision, and all three together.
2. `CollectorIntegrityTest.explicitOwnerStillBecomesUnmappedWhenAllHealthSignalsAreUnhealthy`
   checks that explicit local attribution failure still affects only its owner,
   with both owned and unowned diagnostic strings retained.
3. `AsmCoverageFragmentProjectorTest.transformationFailureRetainsMeasuredCoverageCompletionAndDiagnostic`
   replaces the former whole-fragment-failure expectation with the amended policy.
4. `AgentIntegrityTest.structuredLocalErrorSurvivesAgentShutdownAndKeepsSibling`
   now runs with healthy and unhealthy metrics. Real agent output retains the
   transformation-error and collision counts (one each in the unhealthy case),
   the diagnostic text and a complete fragment with the passing sibling mapped.
5. `AsmUnmappedEndToEndTest.onlyBrokenIdentitiesBecomeMandatory` gains four cases,
   each with 3,000 assigned identities: runtime uninitialized, transformation
   failure, ID collision, and explicitly owned failures with all health signals
   unhealthy. These use real projection, Maven finalization/evidence/aggregation,
   assignment validator, codecs, joiner, publication alignment and
   `ExplicitPrSelectorFlow`. No-change selection is empty for the first three;
   exactly the two explicitly affected tests are mandatory in the fourth.
   Existing failed, unfinished, missing-result, unsupported and unowned-error
   cases are rerun as well. Observations are synthetic; this is not a claim of
   3,000 executed application JUnit bodies per case or controller publication.

The permanent `AsmCoverageFragmentProjectorTest.unsupportedSetupIsObservableAndPreventsLocalCompletionWithoutInventingEdges`
continues to assert ERROR setup rejection. Fresh real shared-context and parallel
fixtures additionally check the two mandatory end-to-end rejection gates.

## Validation record

Focused regression tests and isolated artifact publication passed in
[focused-final.log](focused-final.log). Earlier attempts are retained:
[focused.log](focused.log) caught the obsolete whole-fragment-failure expectation;
[focused-rerun.log](focused-rerun.log) caught a missing test-only Gson dependency.
The assertion was updated to the requested policy, and the agent test now reads
the two numeric diagnostic fields without adding a dependency. No product fix
was made to satisfy the latter test compilation error.

Fresh subject gates and the full eight-suite rerun **PASS**. These
are new measurements, not results borrowed from earlier tasks. Assertions and
exact diagnostics: [correctness-gates.json](raw/implementation/correctness-gates.json).

| Gate | Fresh result |
| --- | --- |
| PetClinic Maven ASM, revision `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, 69-test H2 scope | PASS, fragment SHA-256 `0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9` |
| ASM-12 shared cached web context | Expected rejection retained: mapping and aggregation exit 1; fragment incomplete; validator and join reject `Fragment collection is not completed`; closed-container ASYNC_SETUP_UNSUPPORTED ERROR and LATE_EVENT=1 |
| ASM-13 parallel | Expected rejection retained: mapping and aggregation exit 1; fragment incomplete; validator and join reject `Fragment collection is not completed`; ForkJoin execution-without-observed-submission ASYNC_SETUP_UNSUPPORTED ERROR |
| STP-PERF-01 hot path | Unchanged against `fa2d0f4`: six whole runtime files and the method-hit, ordinal registration/lookup/count snapshot and injection method bodies compare equal. PetClinic retains 3,816 raw hits and 3,079 leaf raw hits. No new benchmark claimed. |

Neither negative gate produced a candidate map. No Jenkins/storage publication
was invoked: local candidate joining was rejected. See their individual
`validation.json`, `exit-codes.txt` and agent diagnostics under `raw/implementation`.

Full rerun: [console log](raw/full-suites.log), **BUILD SUCCESSFUL in 9m 31s**,
68 tasks executed, all with `--rerun-tasks`. Results: **756 tests, 739 passed,
17 skipped, zero failures/errors**. Compared with the preceding uncommitted
STP-UNMAPPED-01 run (750/733/17), six additional cases pass and no count drops.

| Module | Tests | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: | ---: |
| smart-test-picker-common | 334 | 323 | 11 | 0 |
| smart-test-picker-core | 29 | 24 | 5 | 0 |
| smart-test-picker-maven | 161 | 161 | 0 | 0 |
| smart-test-picker | 58 | 58 | 0 | 0 |
| stp-runtime | 67 | 67 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| stp-agent | 50 | 49 | 1 | 0 |
| smart-test-picker-cli | 36 | 36 | 0 | 0 |

XML results are retained under [raw/test-results](raw/test-results/), with
[machine-readable counts](raw/suite-counts.json). Final agent provenance verifies
all 2,013 JAR entry contents equal in the three subject gates and the full suite;
Maven plugin provenance verifies all 55 entries equal. PetClinic's archive hashes
differ only at the archive metadata level; there are no differing entry contents.
See [agent provenance](raw/agent-provenance.json) and
[Maven plugin provenance](raw/maven-plugin-provenance.json).

Fresh [regression artifacts](raw/regressions/) and
[exact selected/unmapped identities](raw/regression-results.json) show:

| Case | Mapped | Unmapped | Complete | No-change selection |
| --- | ---: | ---: | --- | --- |
| Runtime uninitialized | 3,000 | 0 | true | None |
| Transformation failure | 3,000 | 0 | true | None |
| Method-ID collision | 3,000 | 0 | true | None |
| Explicitly owned errors plus all unhealthy signals | 2,998 | 2 COLLECTION_FAILED | true | Exactly `fixture.Tests#test0000` and `fixture.Tests#test0001` |

The five preceding per-identity cases also pass unchanged. `git diff --check`
passes. The repository large-file check passes, and all newly retained files
outside ignored `.work` caches are individually below 5 MiB.

Commands (from the repository root; existing third-party caches reused, current
STP artifacts snapshotted separately for each subject gate):

```sh
GRADLE_USER_HOME="$PWD/verification/stp-perf-01/.work/gradle-home" \
  ./gradlew publishStpToIsolatedRepository --console=plain
python3 verification/stp-unmapped-01-amendment/validate.py petclinic-after
python3 verification/stp-unmapped-01-amendment/validate.py asm12
python3 verification/stp-unmapped-01-amendment/validate.py asm13
python3 verification/stp-unmapped-01-amendment/check-gates.py
PATH="/Users/D061177/Programs/apache-maven-3.9.15/bin:$PATH" \
GRADLE_USER_HOME="$PWD/verification/stp-perf-01/.work/gradle-home" \
  ./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-runtime:test \
  :stp-junit-adapter:test :stp-agent:test :smart-test-picker-cli:test \
  --rerun-tasks --continue --console=plain
python3 verification/stp-unmapped-01-amendment/finish.py
```

The gate harness refuses existing work directories. Repeating it requires fresh
destinations; retained results are not overwritten. Final suite output is
`raw/full-suites.log`; subject commands, diagnostics, fragments and validation
results are under `raw/implementation/`. Each gate's agent and Maven plugin JAR
entry contents are compared against the final tested artifacts by `finish.py`.

## Limits

This implements the requested policy, not a proof that ignored collector errors
cannot hide relevant edges. A positive measured edge is retained; absence of an
edge after a transformation failure is not proven absence of execution. Known
broken/absent identities become mandatory; unnamed affected work cannot be
assigned to guessed owners. Diagnostic counters remain the visibility signal.
No Hybris suitability, propagation repair or reduction of real-suite failures is
claimed. The hot-path guard, occurrence counters and collector scope policy are
unchanged. “Complete despite agent health errors” does not override ERROR setup
diagnostics, contradicting assignments, invalid artifacts or missing whole shards.
