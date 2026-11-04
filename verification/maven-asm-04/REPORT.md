# MAVEN ASM 04 single output fork guard

ASM mapping preparation now rejects fork configurations that cannot safely write
one fragment per execution target. This is a preparation guard, not automatic ASM
agent attachment or a per-fork merge implementation. JaCoCo behavior is unchanged.

## Source and environment

- Branch: `research/asm-codex`.
- Starting commit: `7382cff4afe2ff03a1e167df0ad04b0159cd419a`, including the
  MAVEN-ASM-03 activation correction. The historical `fd5e57c` was not restored.
- Maven 3.9.15; Surefire and Failsafe 3.5.3; Gradle 8.14.
- SapMachine JDK 21.0.13.0.3+1-LTS, macOS aarch64; fixture bytecode targets Java 17.
- STP version remains `0.3.0-SNAPSHOT`.

## Failure mechanism and correction

Each ASM agent invalidates its fragment output on startup and replaces that file
when publishing. Atomic replacement does not combine records from different
JVMs. Both parallel forks and successive non-reused forks can lose identities.
An in-process run (`forkCount=0`) does not start the agent specified in `argLine`.

`PrepareReactorExecutableMappingMojo` validates every assigned ASM module before
changing provider configuration. `AsmMappingForkGuard` accepts a fixed
`forkCount=1` and `reuseForks=true`, with defaults 1 and true. It rejects zero,
multiple forks, CPU-relative counts, unresolved expressions, invalid values and
legacy `forkMode`. This is deliberately conservative: even a CPU expression that
happens to yield one fork on a particular machine must be replaced with `1`.
All legacy `forkMode` values are rejected rather than guessing version-dependent
interactions with the modern settings.

After validation, preparation writes literal `1` and `true` into plugin and
execution configuration. Later property changes cannot turn a validated
expression into multiple forks. No unsafe configured value is silently coerced
for an assigned module. Modules with no assignment retain the existing suppression
behavior. The entire guard and literal-setting path is conditional on ASM.

Example rejection:

```text
Cannot prepare schema-v3 Maven mapping: ASM mapping requires forkCount=1 and reuseForks=true without legacy forkMode; unsupported forkCount=2 in module-b / maven-surefire-plugin / default. Multiple test JVMs overwrite the single ASM fragment; per-fork merging is not supported.
```

## Configuration sources and boundaries

| Source | Handling |
| --- | --- |
| Plugin configuration | Read from the active effective Maven project model |
| Parent inheritance and plugin management | Effective model plus managed-plugin lookup if not present in build plugins |
| Active profiles | Already incorporated into the effective model; integration-tested |
| Execution configuration | Merged over plugin configuration with Maven's `Xpp3Dom` merge rules |
| User properties, including `-DforkCount` and `-DreuseForks` | Evaluated through Maven's `PluginParameterExpressionEvaluator` |
| XML `default-value` attribute | Evaluated when the parameter expression is unset; an explicit default of `2` is rejected |
| Project and JVM properties, `${...}` expressions | Same Maven evaluator, bound to the owning module through a cloned session |
| Literal POM value versus user property | Literal wins; the integration test proves literal `1` with `-DforkCount=2` still executes safely |
| Missing settings | Surefire/Failsafe modern defaults: one reused fork |
| Unresolved or late expressions | Rejected, never interpreted as a safe default |
| CPU-relative counts and legacy fork mode | Rejected conservatively |

Every configuration of the chosen provider in an assigned module is checked,
including execution IDs that may not run in the subsequent lifecycle. This can
reject an unsafe unused execution; it avoids guessing lifecycle intent and does
not change target routing. The same check applies to Surefire and Failsafe.

The parameter names/defaults were checked against the local Surefire 3.5.3 plugin
descriptor. The guard does not resolve arbitrary future provider versions or
custom providers to discover alternate defaults. It does not inspect test-JVM
system properties as Maven fork settings: those reach the fork too late to choose
how many JVMs to start. User, project and JVM property lookup is delegated to Maven;
the retained runtime cases exercise user and model properties, not a separate
`MAVEN_OPTS` environment experiment.

Preparation must run **before** the test lifecycle. A raw manual `-javaagent` run
that bypasses `prepare-reactor-executable-mapping`, including the original P5 spike
command unchanged, still bypasses this Maven guard. The P5-equivalent prepared
two-class scenario is rejected before it can publish a partial fragment. Direct
configuration mutation by another extension after preparation, multiple separate
Maven invocations sharing one file, and agent attachment remain outside this task.
This is a per-target fork guard, not output-path ownership validation between
modules or provider executions; independently attached agents must not share an
output path across different execution targets.

## Regression evidence

The existing `schema-v3-reactor` fixture is copied to fresh temporary directories;
the original fixture is unchanged. The new tests live in
`ReactorHeadTestInventoryMojoIntegrationTest`.

`asmMappingRejectsUnsafeForksBeforeAnyTestJvm` exercises 13 cases: literal and
command-line `2`, `${mapping.forks}` resolved to `2`, execution override, active
profile, plugin management, XML default value, `2C`, `0`, `reuseForks=false`, unresolved expression,
legacy `forkMode=always`, and Failsafe configuration. Each asserts a nonzero exit,
the offending setting/value and module/provider diagnostic, no Surefire execution,
no test compilation, no test reports, no agent diagnostics and no fragment.

`asmMappingSingleReusedForkRetainsBothTestClasses` exercises defaults, explicit
`-DforkCount=1`, and literal `1` overriding user property `2`. It installs fixture
dependencies with tests skipped, then invokes preparation and the real ASM agent
on two test classes in one reused fork. Every fragment contains exactly:

```text
maven:module-b::b.BTests#b2
  PASS / COLLECTED_WITH_COVERAGE
  a.AValue, b.BValue
  a.AValue#<init>()V, a.AValue#value()I
  b.BValue#<init>()V, b.BValue#value()I
maven:module-b::shared.SharedTest#same
  PASS / COLLECTED_EMPTY
  no class or method edges
unmapped = []
collection.completed = true
```

`jacocoMappingStillAllowsTwoForks` exercises the same preparation goal with the
default JaCoCo collector and `forkCount=2`, asserting two real successful test
reports. The existing `schemaV3FailsClosedWhenTestForkHasNoActiveJacocoRuntime`
test is unchanged.

The first development pass found an unresolved-expression gap in the new guard
and an unsupported fixture invocation that omitted the root from `-pl`. Both were
corrected before acceptance. The latter is an existing preparation constraint,
not changed here: the safe test uses `-pl .,module-b` and installed module-a
artifacts. `focused.log` records the first pass; `focused-corrected.log` records
all 15 initial focused ASM cases passing. Final review added the XML default-value
case, included in the final full-suite run. The preceding full-suite run passed
81 tests with no failures or skips (`full-maven-suite.log`).

## Commands and retained outputs

From the repository root, with Maven 3.9.15 on `PATH`:

```sh
./gradlew :smart-test-picker-maven:test --tests '*asmMapping*' --console=plain
./gradlew :smart-test-picker-maven:test --rerun-tasks --console=plain
```

The integration harness publishes the current common/core/Maven artifacts into
the existing functional-test repository and uses its generated Maven settings
with a process-isolated local repository. It uses these actual Maven invocations
(paths substituted by the harness, not a new orchestration script):

```sh
# Negative case: POM has forkCount=2 in module-b. Other cases vary this source.
mvn -U --batch-mode --no-transfer-progress --settings SETTINGS \
  com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:prepare-reactor-executable-mapping \
  test -DsmartTestPicker.testsFile=ASSIGNMENT \
  -DsmartTestPicker.coverageCollector=ASM -Djacoco.skip=true \
  "-DargLine=-javaagent:AGENT=output=DIAGNOSTIC;fragmentOutput=FRAGMENT;schemaVersion=3;revision=reactor-revision;shardId=reactor-shard;executionTarget=maven:module-b;runId=asm-fork-guard;includes=a.,b."

# Safe case dependency preparation, then mapping with one reused fork.
mvn -U --batch-mode --no-transfer-progress --settings SETTINGS \
  -pl module-a -am install -DskipTests
mvn -U --batch-mode --no-transfer-progress --settings SETTINGS \
  -pl .,module-b \
  com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:prepare-reactor-executable-mapping \
  test -DsmartTestPicker.testsFile=ASSIGNMENT -DsmartTestPicker.reactorRoot=FIXTURE \
  -DsmartTestPicker.coverageCollector=ASM -Djacoco.skip=true -DforkCount=1 \
  "-DargLine=-javaagent:AGENT=output=DIAGNOSTIC;fragmentOutput=FRAGMENT;schemaVersion=3;revision=reactor-revision;shardId=reactor-shard;executionTarget=maven:module-b;runId=asm-fork-guard;includes=a.,b."
```

Final full Maven suite: **PASS, 82 tests, 0 skipped, 0 failures, 0 errors**.
`--rerun-tasks` executed all 29 Gradle tasks in 7m 19s. The baseline was 65 Maven
tests; 17 new cases are retained permanently. No baseline tests were removed.
The unchanged missing-JaCoCo-runtime regression passed with its exact diagnostic.

Retained evidence:

- [Final full-suite console](full-maven-suite-final.log).
- [JUnit integration results](raw/TEST-com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.xml).
- [Rejected literal two-fork run](raw/rejected-literal/maven.log).
- [Rejected XML default](raw/rejected-default-attribute/maven.log).
- [Single-fork fragment](raw/accepted-property/fragment.json) and
  [agent diagnostics](raw/accepted-property/diagnostic.json).
- [JaCoCo two-fork control](raw/jacoco-two-forks/maven.log).

Every rejected case has its Maven log under `raw/rejected-*`; all three accepted
cases retain Maven logs, fragments and diagnostics under `raw/accepted-*`.
All task evidence files are below 5 MiB. Raw console whitespace is preserved.
No Jenkins or Docker execution was needed or claimed for this Maven-only change.

## Separate backlog item

Implement invocation-bound per-fork fragment/diagnostic outputs and a controlled
merge with expected-identity accounting, incomplete/crashed-fork handling and
duplicate detection before publication. `MergeExecutableTargetFragmentsMojo`
joins execution targets, not JVM forks within one target; its accounting rules
are a precedent, not a reusable per-fork implementation. No such merge is added
by this task. Gradle, ASM agent/listener/projector, schemas, identities, selection,
Jenkins, storage and module-selection policy are unchanged.
