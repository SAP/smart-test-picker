# MAVEN-ASM-16: Gradle-to-Maven ASM parity

## Inspection and implementation decisions

Initial source: `e33b371eff16d4d1388e8e7cf09f353e2c1c7219`, branch
`research/asm-codex`, containing ASM-15 `4f73dc3` plus documentation consolidation.
Existing untracked examples and earlier spike directories are preserved.

1. **DONE already:** `PrepareReactorExecutableMappingMojo.configure` (lines 187–190) deletes
   the target-qualified fragment, evidence and diagnostic with `Files.deleteIfExists`.
   `execute` calls configure at line 107 before `AsmMappingAttachment.attach` at
   line 109; an IOException
   propagates to the preparation failure. No extra cleanup implementation is needed.
2. **DONE already:** `GenerateCoverageFragmentMojo.finalizeAsm` validates the
   expected revision/shard against the assignment, then passes the immutable
   fragment and local assignment to `ExecutableFragmentAssignmentValidator.validate`.
   The call is at line 191. That shared validator checks exact revision and shard
   equality at lines 34–35, not presence. Expected mojo values are checked against
   the assignment at finalization lines 158–159.
   No second binding validator is introduced.
3. **MISSING, implemented:** move `CoverageCollectorType`, `CollectorCapabilities`
   and the neutral `CoverageCollectorBackend` methods to common, retaining their
   Java package names. `GradleCoverageCollectorBackend` keeps Project/Test wiring
   in the Gradle module. Maven preparation and finalization use
   `MavenCoverageCollectorBackend`; selection parsing/defaults remain unchanged.
   Maven ASM declares class coverage, exact descriptor-aware method-entry coverage,
   and setup scopes, but not direct schema-v2 fragment finalization: its supported
   prepared path is schema 3 only. Capabilities are bounded by supported topology,
   not a claim of universal attribution soundness. Gradle capability values remain
   unchanged. Agent resolution and argument-provider classes remain Gradle-owned.

Git blame confirms both preparation cleanup and the finalization-validator call
were already introduced by MAVEN-ASM-06 (`b13cdba`). The new negative functional
coverage verifies those existing protections instead of duplicating them.

## Validation

**PASS.** The full rerun completed successfully in 14m 45s, with all 64 Gradle
tasks executed. The new `AsmMappingParityIntegrationTest` reuses the existing
schema-v3 reactor and Maven harness. It exercises preparation-only stale-output
invalidation, undeletable fragment/diagnostic hard failures, and independent
revision/shard mutations with a valid assignment and diagnostic. Existing fork
guard tests provide real Maven rejection output for forkCount=2 and reuseForks=false.

| Suite | Reported tests | Executed without failure | Skipped/aborted fixtures | Failures/errors |
| --- | ---: | ---: | ---: | ---: |
| common | 334 | 323 | 11 | 0 |
| core | 29 | 24 | 5 | 0 |
| Maven | 149 | 149 | 0 | 0 |
| Gradle plugin | **46** | **46** | **0** | **0** |
| agent | 45 | 44 | 1 | 0 |
| runtime | 47 | 47 | 0 | 0 |
| JUnit adapter | 21 | 21 | 0 | 0 |
| Total | 671 | 654 | 17 | 0 |

These are fresh `--rerun-tasks` results, not prior task counts. Common adds one
contract test (333 -> 334); Maven adds six parity cases (143 -> 149); Gradle
remains 46/46. The unchanged Gradle capability assertions, mapping TestKit and
ordinary-consumer regressions all pass. Existing disabled/programmatically
discovered and assumption-aborted fixtures explain the skips; they are not
counted as executed successes. The unchanged missing-JaCoCo-runtime fail-closed
regression also passes in the full Maven suite.

The complete [console log](full-suites.log) and the 88 fresh JUnit XML reports
under `raw/test-results/` retain the counts and individual outcomes. In
particular, [new parity tests](raw/test-results/smart-test-picker-maven/TEST-com.sap.oss.smarttestpicker.maven.AsmMappingParityIntegrationTest.xml)
report six cases with zero failures, and
[Gradle capability/binding tests](raw/test-results/smart-test-picker/TEST-com.sap.oss.smarttestpicker.CoverageCollectorBackendTest.xml)
remain unchanged and pass. The large-file check passes; every newly retained
file is below 5 MiB. Java/Markdown whitespace and 18 relative documentation
links/anchors were checked. Raw Maven logs retain Maven's original trailing
spaces rather than being reformatted to satisfy a source whitespace check.
No production behavior changes were needed for Steps 1 or 2.

### Fresh parity fixture evidence

These are real Maven invocations on copies of `schema-v3-reactor`, with one
assigned identity `maven:module-b::b.BTests#b2`. Assertions execute only after
checking Maven's exit status and actual files. Preparation-only execution is
intentional: it proves deletion is not merely agent startup invalidation.

| Case | Observed result | Retained output |
| --- | --- | --- |
| `forkCount=2` | Preparation rejects `unsupported forkCount=2 in module-b / maven-surefire-plugin / default`. No agent resolution, test compilation, test reports or fragment. | [Fresh fork guard output](raw/fork-guard/forkCount-2.log) |
| `reuseForks=false` | Preparation rejects `unsupported reuseForks=false in module-b / maven-surefire-plugin / default`, before the same execution boundaries. | [Fresh fork guard output](raw/fork-guard/reuseForks-false.log) |
| Valid previous mapping, then preparation only | Previous fragment decodes with the assigned identity. Preparation exits 0 without Surefire; fragment, diagnostic and evidence are absent. Finalization without a new fork exits 1: `Missing ASM agent diagnostic for maven:module-b`. | [Preparation](raw/parity/stale/prepare-only.log), [finalization](raw/parity/stale/finalize-without-fork.log), [file assertions](raw/parity/stale/assertions.txt) |
| Undeletable fragment | Nonempty directory at the exact fragment path makes preparation exit 1: `Cannot prepare schema-v3 Maven mapping: .../coverage-fragment-v3.json`. No test reports, no Surefire; nested sentinel retained. | [Maven output](raw/parity/undeletable-coverage-fragment-v3.json/maven.log) |
| Undeletable diagnostic | Same hard failure for `asm-agent-diagnostic.json`, before Surefire. | [Maven output](raw/parity/undeletable-asm-agent-diagnostic.json/maven.log) |
| Wrong fragment revision, unchanged valid assignment/diagnostic | Exit 1: `Cannot finalize ASM mapping evidence: Fragment revision does not match assignment revision`. No execution evidence survives. | [Finalization](raw/parity/wrong-revision/finalize.log) |
| Wrong fragment shard, unchanged valid assignment/diagnostic | Exit 1: `Cannot finalize ASM mapping evidence: Fragment shard does not match assignment shard`. No execution evidence survives. | [Finalization](raw/parity/wrong-shardId/finalize.log) |

The negative mutations are disposable fixture inputs, not a change to production
fragment immutability. Finalization leaves each supplied fragment untouched.

The first focused run (`focused.log`) ran six cases with two assertion failures.
Those were harness path aliases, not revision/shard validation failures: Maven
recorded `/private/var/...` while the standalone finalization arguments used
`/var/...`. The existing diagnostic path-binding check correctly rejected those
inputs first. The fixture now uses `toRealPath()` so only the intended fragment
field differs. The initial rejection logs are retained under `raw/initial/`.
Production path comparison was not loosened.

Environment: macOS aarch64; SapMachine JDK 21.0.13.0.3; Gradle wrapper 8.14;
fixture Surefire/Failsafe 3.5.3 and JaCoCo 0.8.13. The initial focused run used
the shell-default Maven 3.8.6. The full rerun explicitly uses installed Maven
3.9.15. STP versions remain `0.3.0-SNAPSHOT`.

Commands from the repository root:

```sh
./gradlew :smart-test-picker-maven:test \
  --tests '*AsmMappingParityIntegrationTest' --console=plain \
  > verification/maven-asm-16/focused.log 2>&1

PATH=/Users/D061177/Programs/apache-maven-3.9.15/bin:$PATH ./gradlew \
  :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-agent:test \
  :stp-runtime:test :stp-junit-adapter:test \
  --rerun-tasks --continue --console=plain \
  > verification/maven-asm-16/full-suites.log 2>&1
```

The existing harness publishes this working tree's artifacts to
`build/functional-test-maven-repository` and resolves them through its configured
per-test-worker local Maven repository. It does not use machine-global installed
STP artifacts. New fixture evidence is written to
`smart-test-picker-maven/build/maven-asm-16/` before retention here; fork-guard
evidence comes from `smart-test-picker-maven/build/maven-asm-04/` in the same rerun.

## Support matrix correction

`EI-15/current-state.md` now has a current matrix above the historical snapshot:
Maven ASM supports prepared schema-v3 Surefire/Failsafe mapping, reactor-derived
production scope, cross-module coverage, guarded attachment and evidence-v2
finalization. It invalidates stale outputs before attachment and validates exact
revision/shard/target bindings. Shared cached Spring web contexts **reject
collection**. Single reusable forks remain required. It does not claim Maven ASM
schema-v2 finalization, S7, per-fork merging or a general in-JVM parallel guard.
The two old blanket Maven+ASM rejection statements explicitly describe the
historical checkpoint, not current support. Historical counts/results remain.

No JaCoCo attribution, selector, agent attachment, fork policy, Jenkins or shared
context behavior is changed. No additional subject campaign is run.
