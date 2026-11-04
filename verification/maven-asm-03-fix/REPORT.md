# MAVEN-ASM-03-FIX collection activation

Ordinary consumers of `smart-test-picker-core` no longer request collection merely
by having core on their test classpath. Collection requires explicit activation;
collector selection is a separate decision. This corrects the ordinary-test
regression introduced by `bd889ab05b16f1966698e93cfc73156122859dc5` on
`research/asm-codex`. STP versions remain `0.3.0-SNAPSHOT`.

## Activation contract

The test-JVM system property `smartTestPicker.collectionEnabled` is the activation
signal. Missing, blank or `false` is inactive; `true` is active (trimmed and
case-insensitive). Other activation values are configuration errors.

| Activation | Collector selection | Core JaCoCo listener and extension |
| --- | --- | --- |
| Inactive | Any value, including invalid | Silent no-op; selection is not parsed and runtime is not accessed |
| Active | `ASM` | Silent no-op; no JaCoCo runtime access |
| Active | `JACOCO`, absent or blank | Require the runtime at construction; fail closed if unavailable |
| Active | Invalid | Reject at construction |

Both components freeze these decisions in their constructors. The extension also
resolves its selected runtime there, not in a callback. The listener preserves the
startup diagnostic `Active JaCoCo runtime is unavailable for STP mapping`, so an
active but unusable collector cannot be reduced to a swallowed JUnit callback
warning. Activation has no relationship to `stp.exec.dir`: its existing default
directories and managed-evidence meaning are unchanged.

## Production entry point audit

| Entry point | Activation | Selection and transport |
| --- | --- | --- |
| Gradle `AsmCoverageCollectorBackend` | `true` | `ASM`, through `Test.systemProperty` |
| Gradle `JacocoCoverageCollectorBackend` | `true` | `JACOCO`, through `Test.systemProperty` |
| Gradle schema-v2 cloned mapping task | Via the selected backend | Both backend calls above cover this path |
| Gradle schema-v3 assigned `Test` tasks | Via the selected backend | Both backend calls above cover assigned tasks; unassigned tasks execute no tests |
| Maven `PrepareReactorExecutableMappingMojo` | `true` | Resolves user, project, then JVM collector configuration; defaults to JaCoCo. Sets module properties and provider/plugin-execution `systemPropertyVariables` for Surefire or Failsafe |
| Maven `StpMavenLifecycleParticipant` managed SELECT execution | `true` | Existing selected-collector resolution; shared provider configuration supplies both signals, including qualified executions. This is execution-evidence collection, not mapping preparation |
| Maven legacy `SmartTestMojo.forkMavenVerify` | `true` | Explicit `JACOCO` in nested Maven invocation properties; this legacy workflow refreshes JaCoCo coverage |
| Manually configured Maven mapping profiles, including legacy schema-v2 | Project must set `true` | Project selects collector and attaches its agent. README examples and legacy mapping fixtures now make activation explicit |
| CLI `refreshed-report` delegation to external Ant `unittests` | Project-owned test-JVM configuration | The command does not configure a core collection fork or its JVM arguments. An external Ant build using core must explicitly activate it; this task does not add an Ant integration |
| Ordinary tests, inventory discovery, report/fragment generation and join | No automatic activation | These are not collection-fork preparation. Postprocessors cannot retroactively activate an already-finished test JVM |

Managed RUN_ALL retains ordinary project configuration; NONE does not run a test
body. No new selection policy, schema, identity, agent attachment, ASM listener or
projector behavior was introduced. Gradle's default collector remains ASM.
The legacy nested Maven fork was source-audited; it is not a new legacy-workflow
runtime validation claim.

## Permanent ordinary consumer regressions

`ReactorHeadTestInventoryMojoIntegrationTest.ordinaryCoreConsumerRunsBothTestsWithoutCollection`
and the same-named Gradle TestKit test use the checked-in `ordinary-core` consumer
fixture. It declares core as a test dependency and has two Jupiter methods. It
does not apply an STP or JaCoCo plugin, attach an agent, or set activation,
selection, or `stp.exec.dir`. The methods assert those properties are absent and
that the JaCoCo runtime class is unavailable. Autodetection is tested both ways.

| Build | Extension autodetection | Exit/result | Application tests executed |
| --- | --- | --- | ---: |
| Maven plain `test` | Off | 0 | 2 |
| Maven plain `test` | On | 0 | 2 |
| Gradle plain `test` | Off | SUCCESS | 2 |
| Gradle plain `test` | On | SUCCESS | 2 |

Each regression inspects the report for `fixture.OrdinaryTest`, verifies two
testcases and no failures/errors/skips, and rejects the JaCoCo diagnostic. A
synthetic Gradle executor failure cannot satisfy these assertions. Gradle runs
without `withPluginClasspath()` for these consumer cases. Fresh temporary
projects prevent stale test reports from satisfying them.

Core tests additionally cover inactive invalid selection without any Java agent,
inactive components with an explicit output directory, explicit inactive state,
invalid active selection, active blank selection, and constructor-time decisions
that do not change when system properties are modified before callbacks.

## Omitted activation and downstream rejection

`omittedActivationCannotSatisfyMappingEvidenceOrJoin` deliberately bypasses mapping
preparation. It runs `a.ATests#a1` in the existing schema-v3 reactor with JaCoCo
attached but without STP activation. The production method runs and the test
passes. Neither core component writes per-test `session_*.exec` or `.identity`
files. JaCoCo's independent aggregate `test.exec` can still exist; it is not STP
per-test evidence.

The actual generated fragment is:

```json
{"schemaVersion":3,"revision":"reactor-revision","shardId":"reactor-shard","tests":{},"unmapped":[],"setupScopes":[],"collection":{"completed":false}}
```

Fragment generation itself returns exit 0, **but explicitly marks collection
incomplete**, not valid empty successful collection. The test also creates the
report directory beforehand, proving directory presence alone cannot yield
`completed=true` when runtime evidence is absent.

- `MavenExecutionEvidence.verify` returns `valid=false` and
  `missing=[maven:module-a::a.ATests#a1]`. Its process-completed flag can be true;
  identity completeness still prevents verification success.
- The real reactor aggregation goal exits nonzero with
  `Incomplete module coverage fragment for maven:module-a` and creates no final map.
- The production joiner rejects the actual fragment with
  `Fragment collection is not completed; incomplete executable collection`,
  including the exact missing identity above.

No change to fragment generation, evidence verification or join was needed.
Omission therefore remains visible downstream; a caller that runs only the
fragment-generation goal must not interpret its zero process exit as publication.

## Validation and evidence

Executed on 2026-11-04 with Maven 3.9.15, Surefire 3.5.3, Gradle 8.14 and
SapMachine JDK 21.0.13.0.3+1-LTS. Fixtures use Jupiter 5.9.3.

```sh
./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test --rerun-tasks --console=plain
```

The unchanged `schemaV3FailsClosedWhenTestForkHasNoActiveJacocoRuntime` still
passes with its exact diagnostic. The focused run passed before the full rerun;
see [focused.log](focused.log). Full rerun results are recorded in
[full-suites.log](full-suites.log): **BUILD SUCCESSFUL in 6m 56s**, all 52 tasks
executed with `--rerun-tasks`.

| Suite | Baseline | Current tests | Skipped | Failures / errors |
| --- | ---: | ---: | ---: | ---: |
| common | 333 | 333 | 11 | 0 / 0 |
| core | 27 | 29 | 5 | 0 / 0 |
| Maven | 62 | 65 | 0 | 0 / 0 |
| Gradle plugin | 44 | 46 | 0 | 0 / 0 |
| Total | 466 | 473 | 16 | 0 / 0 |

Counts include skipped tests. The seven additions are two core regression tests,
two Maven ordinary-consumer cases, one Maven omitted-activation case, and two
Gradle ordinary-consumer cases. No baseline tests were removed or newly skipped.

Permanent evidence copied from the completed rerun:

- [Maven autodetection off](evidence/maven/ordinary-false/tests.xml) and
  [on](evidence/maven/ordinary-true/tests.xml), with adjacent Maven console logs.
- [Gradle autodetection off](evidence/gradle/ordinary-false/tests.xml) and
  [on](evidence/gradle/ordinary-true/tests.xml), with adjacent Gradle console logs.
- [Omitted-activation fragment](evidence/maven/omitted-activation/fragment.json),
  [collection log](evidence/maven/omitted-activation/collection.log),
  [aggregation failure](evidence/maven/omitted-activation/aggregation.log), and
  [evidence and join rejection](evidence/maven/omitted-activation/verification.txt).

Runtime artifacts are also regenerated under each tested module's
`build/maven-asm-03-fix/`. The report, console log and copied evidence stay in the repository.
Existing untracked spike/example directories are preserved. No push is performed.
