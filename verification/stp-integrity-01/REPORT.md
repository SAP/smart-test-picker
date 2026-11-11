# STP-INTEGRITY-01

Initial source: `3884d49`, branch `research/asm-codex`. No CI or Hybris run.

Status: **PASS — structured grading implemented; all four mandatory local gates
passed.** Unresolved sites and unsupported identities remain fatal, not silently
downgraded. This does not qualify a Hybris/ANT run or claim its failures are fixed.

## Decision record (before implementation)

Use structured collector errors carrying classification, diagnostic text, optional
method/site identity and an explicit set of affected executable test identities.
No severity or identity is inferred from text. SITE_LOCAL without a proven set of
affected identities is effectively SHARD_FATAL. A method identity alone cannot
establish that set: missing propagation can remove precisely the evidence needed
to discover ownership. Class loading does not establish test ownership.

The current transformation-time producers have no proven affected test set.
Consequently none is newly downgraded by this task. The new identity-local path
is available to producers with explicit ownership and is tested permanently; this
is not a claim that the reported Hybris errors can now be ignored.

| Producer / diagnostic | Classification and justification |
|---|---|
| MethodEntryClassFileTransformer: `transformation-error:<class>:<exception>` | SHARD_FATAL. Included production code may be unobserved for any test. Separate transformation counter remains fatal. |
| MethodCatalog: `method-id-collision:<hash>` | SHARD_FATAL. Separate collision counter remains fatal; retain the colliding method identity, never infer a test. |
| ExecutorCallSiteTransformer: `executor-attribution-incomplete:` | SITE_LOCAL, effectively SHARD_FATAL: unresolved executor hierarchy at a transformation site, no bounded set of affected tests. |
| ExecutorCallSiteTransformer: `forkjoin-attribution-incomplete:` | SITE_LOCAL, effectively SHARD_FATAL for the same ownership reason. |
| ExecutorCallSiteTransformer: `scheduled-executor-attribution-incomplete:` | SITE_LOCAL, effectively SHARD_FATAL for the same ownership reason. |
| ExecutorCallSiteTransformer: `executor-propagation-error:<class>:<exception>` | SHARD_FATAL: transformation failed, no trustworthy affected identity set. |
| ForkJoinExecutionTransformer: `forkjoin-execution-incomplete:<class>:<exception>` | SHARD_FATAL: execution-boundary instrumentation failed, no trustworthy identity set. |
| AgentRuntime: `runtime-serialization-failure:<exception>` | SHARD_FATAL by default: serialization failed without a proven local owner. |
| AgentRuntime: `ASM scope matched zero instrumented classes: ...` | SHARD_FATAL; no fragment published. |
| AgentRuntime: `fragment-serialization-failure:<exception>` | SHARD_FATAL; no valid new fragment can be relied upon. |
| AgentRuntime: `fragment-atomic-move-unsupported:platform-fallback` | SHARD_FATAL by default: publication failure has no identity owner. This post-publication diagnostic is discussed separately in final findings. |
| AgentRuntime: `<IOException class>: <message>` from diagnostic output writing | SHARD_FATAL by default; cannot persist the output diagnostic itself, stderr remains the fallback. |
| Projector: `unsupported-identity:<platform id>` | SHARD_FATAL: no logical/executable identity can key an unmapped entry; no expected inventory is available here. No S7 fix or invented identity. |
| Projector: `unresolved-error-ownership:<executable id>` (new) | SHARD_FATAL: a producer supplied a target or identity not bound to this projection. Preserve that structured identity; do not invent observations or silently drop the error. |
| Projector: `setup:<canonical diagnostic>` | Existing separate setup severity contract: ERROR keeps collection incomplete; non-ERROR is diagnostic only. It is projection output, not a new collector-error producer. Never reclassify these by parsing text. |
| Runtime initialization absent | SHARD_FATAL via boolean, not a string. Premain exceptions abort initialization. |
| NoOpClassFileTransformer parse error | SHARD_FATAL via transformation counter; no sink string. |

All direct sink writes and the projection-to-agent diagnostic feedback were
audited. Runtime setup diagnostics and unattributed events have their own typed
contracts and remain unchanged. No existing producer emits a safely bounded
IDENTITY_LOCAL error; accepting synthetic ownership inferred from a class name
would violate the task's safety rule.

One premise needs precision: `unsupported-identity` originated inside the
projector and was appended to AgentRuntime's text list only after projection.
On that first projection it made completion false but could leave identifiable
siblings mapped. It was not necessarily an input to `criticalFailure()` in that
same call. Structural rejection was already correct; the new explicit fatal
classification also puts identifiable siblings into COLLECTION_FAILED, consistent
with the requested fatal branch. No identity is invented for the unsupported leaf.

For explicit local errors the projector must validate the target and that each
affected identity exists in this observation. Wrong-target, absent, or schema-v2
unbound executable ownership stays fatal. Diagnostic text is retained in
FragmentProjectionResult and agent diagnostic output; coverage schemas themselves
do not contain a diagnostics field, and this task does not introduce one.

## Implementation

`CollectorError` carries `Classification`, message, an immutable set of
`ExecutableTestIdentity` owners, and an optional runtime `MethodIdentity` site.
An identity-local error without identities is rejected at construction. A
site-local error requires a method identity; without proven affected identities
its effective severity is fatal. No call-site ownership is guessed from observed
coverage either: the failed propagation may have prevented recording that edge.

`CollectorIntegrity.shardFatal()` replaces the old indiscriminate boolean;
`affectedIdentities()` exposes the explicit local set. The separate transformation
and collision counters retain their meanings. The javadoc now describes both
shard health and local ownership, and leaves inventory completeness to orchestration.

`AsmCoverageFragmentProjector` binds local executable owners against the configured
target and observed logical identities. A local error removes only that identity's
coverage, emits `COLLECTION_FAILED`, and does not itself clear completion. Fatal
errors remove every identifiable test's coverage and clear completion. Unfinished
tests and ERROR setup diagnostics retain their existing completion behavior.
`unsupported-identity` stays fatal; no S7 identity fallback is implemented.

`FragmentProjectionResult` carries typed collector errors as well as the existing
diagnostic text. `AgentRuntime` uses the actual v3 target when projecting and keeps
setup/projection diagnostics separate from collector grading. It emits all text
through the existing `agentErrors` diagnostic array, without reinterpreting those
strings or feeding setup INFO messages back as fatal errors. No fragment/map wire
schema changed. Internal Java callers of the integrity/result records must use the
new typed contracts; the source tree has been migrated together.

Code locations: `CollectorError.java:10`, `CollectorIntegrity.java:20` and `:25`,
`AsmCoverageFragmentProjector.java:35` (fatal), `:53` (ownership binding), `:65`
(fatal projection) and `:72` (local unmapped). These files are under
`stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/`.

### Output-boundary errors

Errors discovered while writing an artifact are not identity-local attribution
errors. Atomic-move fallback is conservatively classified fatal; AgentRuntime
now invalidates the fallback fragment rather than leaving a complete candidate
next to that fatal diagnostic. This filesystem fallback was not fault-injected
in the local gates, whose filesystem supports atomic move. Serialization failure
and diagnostic-output IOException retain their existing stderr reporting. An
IOException writing the diagnostic itself cannot reliably be persisted into that
same failed output file; this pre-existing output boundary is explicitly not a
claim of guaranteed diagnostic delivery. Such late errors occur after projection
and must not be mistaken for projector-owned per-test grading. No storage platform,
CLI publication or orchestration code is changed.

### Downstream verdicts (production paths unchanged)

| Stage | Verdict | Deciding code |
|---|---|---|
| ExecutableFragmentAssignmentValidator | PASS: a complete fragment with COLLECTION_FAILED accounts for that executable identity; no mapped/unmapped overlap or missing identity is allowed. | `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/validation/ExecutableFragmentAssignmentValidator.java:39` adds unmapped; line 49 checks exact accounting. |
| ExecutableCompleteness.from | PASS: the joiner's reported set already includes unmapped owners; completeness subtracts that set, not mapped-only coverage. | `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/model/ExecutableCompleteness.java:20` and joiner line 93. |
| ExecutableCoverageFragmentJoiner | PASS: COLLECTION_FAILED entries join as unmapped owners without error, with exact assignment completeness. | `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/ExecutableCoverageFragmentJoiner.java:79` and line 95. |
| SchemaV2TestSelector | PASS: every unmapped identity still present at head is mandatory, even with no changed classes. | `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/selector/SchemaV2TestSelector.java:63`; real v3-to-logical ingress is `ExplicitPrSelectorFlow.java:91`. |

The permanent runtime test
`CollectorIntegrityTest.unmappedSurvivesCodecValidatorCompletenessJoinAndRealSelector`
starts with the real projector, round-trips the v3 fragment codec, validates the
assignment, joins through the production joiner/completeness implementation,
serializes a published map and invokes `ExplicitPrSelectorFlow` in a disposable
Git repository. Exact assertions: affected is unmapped/mandatory, sibling remains
mapped and is not selected, both are reported, missing is empty, result SELECTED.
This is not a reimplemented selector or a count-only assertion.

### Permanent regressions

All new tests live in normal production-module test source sets:

- `stp-runtime/src/test/java/com/sap/oss/smarttestpicker/runtime/CollectorIntegrityTest.java`
  (11 tests, run by `:stp-runtime:test`): fatal whole-fragment rejection; exact local
  sibling preservation; identical message with different structured severity;
  unresolved and resolved site cases; wrong-target/absent-owner rejection; fatal
  counters/runtime absence; unsupported identity; unbound schema-v2 ownership;
  rejected identity-free local error; full downstream chain described above.
- `stp-agent/src/test/java/com/sap/oss/smarttestpicker/agent/AgentIntegrityTest.java`
  (2 tests, run by `:stp-agent:test`): typed local error survives actual agent
  shutdown/codec serialization with its sibling and diagnostic intact; all three
  unresolved executor/ForkJoin/scheduled-site producers emit method/site metadata
  and remain fatal, with no guessed tests.
- Existing collision tests retain their original ordinal/hit assertions and now
  also check typed collision diagnostics. The existing projector fatal-error test
  supplies a structured fatal error rather than a bare string.

### Retained development attempts

`raw/focused.log` records the sandbox cache-access denial before any tests ran.
`raw/focused-retry.log` records one old assertion that compared typed errors to
bare strings. `raw/focused-final.log` records the new agent test fixture using an
incorrect run/JVM binding. Both test fixtures were corrected without weakening
their assertions; `raw/focused-pass.log` records the successful focused rerun and
publication only into the existing local functional-test Maven repository.

## Fresh correctness gates

### PetClinic: PASS

Pinned subject `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, Maven 3.9.15,
SapMachine 21.0.13, unchanged H2 69-test scope. All 69 tests passed; real production
codecs accepted assignment=69, mapped=69, executable-unmapped=0,
positive-non-execution=0. Fresh fragment SHA-256:

```text
0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9
```

See [accounting](raw/implementation/petclinic-after/accounting.log),
[fragment](raw/implementation/petclinic-after/stp/coverage-fragment-v3.json),
[agent diagnostic](raw/implementation/petclinic-after/stp/asm-agent-diagnostic.json)
and [mapping log](raw/implementation/petclinic-after/mapping.log). No baseline or
subject edits were needed. This is a fresh local Maven run, not reused historical
PetClinic results or Jenkins evidence.

### ASM-12 and ASM-13: PASS (rejection preserved)

Both mapping and aggregation exit 1 in both cases. The production assignment
validator and joiner reject with exactly:

```text
IllegalArgumentException: Fragment collection is not completed
```

ASM-12 proves the same cached Spring context/server is reused. Its diagnostic is
`ASYNC_SETUP_UNSUPPORTED:ERROR` for `example.shared.PingController#ping()Ljava/lang/String;`,
`work captured by closed JUnit container [engine:junit-jupiter]/[class:example.shared.AFirstTests]`.
It records one `LATE_EVENT`, and collection completion is false.

ASM-13 retains `ASYNC_SETUP_UNSUPPORTED:ERROR:unknown:ForkJoinTask: execution without observed submission:
org.junit.platform.engine.support.hierarchical.ForkJoinPoolHierarchicalTestExecutorService$ExclusiveTask`,
and completion is false. No parallel/shared-context guard or support was added.

See [ASM-12 validation](raw/implementation/asm12/validation.json),
[ASM-13 validation](raw/implementation/asm13/validation.json), and the complete
[gate assertions](raw/implementation/correctness-gates.json). These are expected
negative outcomes, not failed acceptance gates. Publication/storage operations
were not run; local production-codec validation and candidate joining were run.

### STP-PERF-01 behavior: PASS

PetClinic still records exactly 3,816 raw method hits and 3,079 leaf raw hits, with
no agent errors. `evidence.py` compares six hot-path source files byte-for-byte
with `3884d49` and separately compares the method-hook body, ordinal registration,
ordinal lookup, counter snapshot and injected-instrumentation bodies: all unchanged.
Only the transformation-time collision error payload in MethodCatalog changed,
not ordinal assignment, raw counters or repeat-hit behavior. No new performance
claim or benchmark was made. Existing MethodProbeTest and collision/ordinal
regressions are included in the full suite gate.

### Eight-module rerun: PASS

Fresh `--rerun-tasks --continue`: **BUILD SUCCESSFUL in 8m 32s**, 68 tasks executed.
**744 tests: 727 passed, 17 skipped, zero failures/errors.** The previous 731-test
baseline is preserved plus 11 runtime and 2 agent regressions; skipped counts are
unchanged in each module.

| Module | Tests | Passed | Skipped | Failures/errors |
|---|---:|---:|---:|---:|
| smart-test-picker-common | 334 | 323 | 11 | 0 |
| smart-test-picker-core | 29 | 24 | 5 | 0 |
| smart-test-picker-maven | 151 | 151 | 0 | 0 |
| smart-test-picker | 58 | 58 | 0 | 0 |
| stp-runtime | 66 | 66 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| stp-agent | 49 | 48 | 1 | 0 |
| smart-test-picker-cli | 36 | 36 | 0 | 0 |

[Full console log](raw/full-suites.log), [machine-readable counts](raw/suite-counts.json)
and all module JUnit XML under `raw/test-results/` are retained. The
[artifact comparison](raw/agent-provenance.json) verifies all 2,013 JAR entries
from each of the three gate agents against the final tested agent: zero differing
contents. The PetClinic archive differs only in ZIP metadata.

Final tested agent SHA-256:
`ecebd09585ced1e48263d5dbc5e73ca0b85113b40065708d446e353fb6c3337d`.
PetClinic gate agent SHA-256:
`507b36d5ca558bb5ba7bb5f6e6de6676f4ed98db6627dae3b9b44d7c9ea0e440`.
ASM-12/13 use the final tested archive checksum. These are standard packaged
agents, not Hybris-stripped artifacts. This task does not prepare or validate a
stripped distribution.

### Reproduction

```bash
GRADLE_USER_HOME="$PWD/verification/stp-perf-01/.work/gradle-home" \
  ./gradlew :stp-runtime:test :stp-agent:test publishStpToIsolatedRepository --console=plain
python3 verification/stp-integrity-01/validate.py petclinic-after
python3 verification/stp-integrity-01/validate.py asm12
python3 verification/stp-integrity-01/validate.py asm13
python3 verification/stp-integrity-01/evidence.py
PATH="/Users/D061177/Programs/apache-maven-3.9.15/bin:$PATH" \
GRADLE_USER_HOME="$PWD/verification/stp-perf-01/.work/gradle-home" \
  ./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-runtime:test \
  :stp-junit-adapter:test :stp-agent:test :smart-test-picker-cli:test \
  --rerun-tasks --continue --console=plain
python3 verification/stp-integrity-01/finish.py
```

The gate wrapper reuses the PERF harness and existing pinned subject/ASM-12/13
fixtures in **fresh** task directories, refuses existing outputs, copies only
downloaded third-party cache content, and resolves STP from a per-run snapshot of
the current functional-test repository. The exact executed scripts/commands and
toolchain are retained with each run. Ignored `.work/` contains subject checkouts,
isolated repositories and binaries. Nothing in the prior evidence is overwritten.

## Delivery limits

No selector, JaCoCo, CLI wrapper, head-inventory wrapper, build-tool integration,
ANT/Hybris, scope policy, context propagation, ordinal guard or occurrence-counter
behavior was changed. No CI, Hybris or Jenkins run was performed. All task evidence
is below 5 MiB per file. The unrelated pre-existing untracked `examples/`,
`verification/maven-asm-shapes/` and `verification/maven-asm-spike/` remain untouched.

Remaining blocker for this grading task: **none**. Remaining limitation: current
unresolved propagation sites and missing logical identities still cannot safely
be assigned to individual tests. A future producer must supply proven ownership;
this task does not manufacture it or implement a new propagation adapter.
