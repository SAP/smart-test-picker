# MAVEN ASM 05 prepared agent attachment

`prepare-reactor-executable-mapping` now resolves and attaches the shaded ASM
agent for assigned targets after validating the assignment, fork settings and
explicit instrumentation scope. No manual STP `-javaagent` is needed. There is no
new mojo or lifecycle-extension attachment route.

## Source and artifact resolution

Starting point: `research/asm-codex`, commit
`e21189c77eea15248962bb5a01715f451acc18a8` (MAVEN-ASM-04).
STP component versions remain `0.3.0-SNAPSHOT`.

The implementation uses Maven's injected `RepositorySystem.resolveArtifact` with
the current repository session and the executing project's dependency repositories.
It requests `com.sap.oss.smart-test-picker:stp-agent:jar:<plugin-version>`, with no
classifier. The version comes from Maven's injected `PluginDescriptor`, backed by
the generated plugin descriptor. This is the Maven-native authoritative metadata
equivalent of Gradle's generated version resource: no fallback, guessed version,
or separately configurable agent version is introduced.

This resolves a **file**, not a dependency graph or classes. The shaded agent
already bundles `stp-runtime` and the JUnit adapter. Making it an ordinary plugin
dependency would unnecessarily expose those classes and service registrations to
the plugin realm. The existing shaded publication is reused; the Maven test task
now publishes it to the existing functional-test repository before running tests.

Real Maven `-X` output records the resolved file and plugin-realm URLs. Assertions
prove that `stp-agent`, `stp-runtime`, and `stp-junit-adapter` are absent from that
realm. Existing dependencies remain, including core/common, JaCoCo/ASM libraries,
JUnit Platform/Jupiter/Vintage, JAXB, Gson and Maven Invoker dependencies. Those
pre-existing libraries are not evidence of agent-realm injection.

The integration harness uses its own `build/functional-test-maven-local-<pid>`
repository and the published functional-test file repository, not the developer's
global `~/.m2`. Each argLine case checks that the resolved agent bytes equal the
current shaded build. Resolution failure is tested through a failing repository
system at the real preparation entry point; that unit test does not claim a remote
repository outage was exercised. Missing plugin-version metadata fails before any
resolution request, and a synthetic `7.8-test` plugin version requests exactly that
agent version, proving there is no `0.3.0-SNAPSHOT` fallback in production code.

## Preparation and target configuration

The call sequence is:

```text
decode and validate assignment -> resolve canonical targets -> partition
  -> validate ALL assigned ASM targets with AsmMappingForkGuard
  -> validate explicit includes/excludes -> resolve aligned agent JAR
  -> configure existing mapping activation/filter/output properties
  -> append per-target agent argument -> Maven test lifecycle
```

An unsafe fork setting fails before scope validation, artifact resolution, or
attachment. No tests start. Modules without assigned tests receive no ASM agent.
JaCoCo selection never enters the attachment path.

| Agent field | Source |
| --- | --- |
| `output` | Prepared `smartTestPicker.moduleEvidenceOutput` |
| `fragmentOutput` | Prepared `smartTestPicker.moduleFragmentOutput` |
| `schemaVersion` | `3` |
| `revision`, `shardId` | Validated assignment |
| `executionTarget` | Existing canonical reactor target resolver, including qualified Failsafe target |
| `runId` | Name UUID over UTF-8 `revision + newline + shardId + newline + target` |
| `includes` | Required `smartTestPicker.asm.includes` |
| `excludes` | Optional `smartTestPicker.asm.excludes`, plus agent-owned mandatory exclusions |

Run IDs are stable for the same assignment and distinct across module/target
identities, independent of workspace location. Scope is explicit for this mapping
invocation: comma-separated package prefixes, normalized to dotted trailing-dot
form and sorted. Empty/absent includes, empty entries and malformed prefixes fail
preparation. Scope is never inferred from `groupId` or reactor classes.

Both `smartTestPicker.collectionEnabled=true` and
`smartTestPicker.coverageCollector=ASM` reach the fork through the existing
collector configuration. Tests assert these values inside the fork.

## ArgLine composition

The existing provider argLine is retained first; one quoted STP argument is
appended last. Execution-level argLines retain precedence over plugin configuration.
When plugin configuration is absent, a user/JVM `argLine` value is respected;
otherwise the default is `@{argLine}`. The project late-expansion property is
initialized to empty only if absent. User argLines containing `@{argLine}` are not
copied into that same property, avoiding recursive expansion. `${argLine}` is
preserved as late expansion rather than consumed before JaCoCo's initialize phase.
Other Maven expressions are evaluated in the owning module's context.

| Case | Project-owned content before appended STP agent |
| --- | --- |
| Literal only | `-Xmx256m -Dproject.literal=preserved` |
| Prepare-agent only | `@{argLine}` |
| Both | `@{argLine} -Xmx256m -Dproject.literal=preserved` |
| Command-line property | `-DargLine` overrides an existing POM argLine property |
| Command-line late property | `-DargLine=@{argLine} ...` keeps JaCoCo expansion and later literal options |

The combined real fork order is **JaCoCo agent, project JVM options, STP agent**.
No reversed-agent-order compatibility is claimed. Existing agents are retained,
not removed or reordered. As in P4, a project that combines a literal argLine with
JaCoCo uses its late-expansion placeholder; this is not an arbitrary conflicting
argLine-merging engine. Workspace paths containing spaces are exercised. Agent
configuration values containing semicolons, quotes, line breaks or unresolved
placeholders fail preparation because the agent protocol cannot encode them safely.

## Runtime regression evidence

The permanent tests extend the existing `schema-v3-reactor` copy-based harness.
The original fixture is unchanged. Prepared tests have no hand-written STP agent
argument and resolve the actual shaded agent from the isolated repository.

All argLine cases require exactly these two mapped identities and no unmapped tests:

```text
maven:module-b::b.BTests#b2
  PASS; classes = [a.AValue, b.BValue]
  methods include a.AValue#value()I and b.BValue#value()I
maven:module-b::shared.SharedTest#same
  PASS / COLLECTED_EMPTY; classes = []; methods = []
collection.completed = true
```

Both ordinary Surefire and qualified Failsafe runs assign tests in module-a and
module-b, check independent output files, exact canonical identities and distinct
deterministic run IDs. Failsafe targets are
`maven:module-a@failsafe@fixture-it` and `maven:module-b@failsafe@fixture-it`.
An optional exclusion is checked in actual agent diagnostics.

The 13 MAVEN-ASM-04 unsafe-fork cases now invoke the automatic path with explicit
includes and no manual agent argument. Each asserts that even agent resolution
has not happened. The former one-fork tests now use automatic attachment and keep
their identity/isolation assertions. Four missing/invalid-scope cases reject
before resolution, test compilation or fragment output.

`schemaV3FailsClosedWhenTestForkHasNoActiveJacocoRuntime` is unchanged. The
JaCoCo two-fork control also remains. Core, common, Gradle, the ASM listener and
projector, and agent attachment internals were not changed.

## Commands and environment

Maven 3.9.15, Surefire/Failsafe 3.5.3, Gradle 8.14 and SapMachine
21.0.13.0.3+1-LTS on macOS aarch64; fixture bytecode targets Java 17.

```sh
./gradlew :smart-test-picker-maven:test \
  --tests '*asmPreparedAttachment*' --tests '*asmMappingSingle*' --console=plain
./gradlew :smart-test-picker-maven:test \
  --tests '*PreparedAsmMappingTest' --tests '*asmPreparedAttachmentUsesDistinct*' --console=plain
./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test --rerun-tasks --console=plain
```

The functional harness supplies generated Maven settings and fixture paths. The
ordinary prepared invocation is:

```sh
mvn -U --batch-mode --no-transfer-progress --settings SETTINGS \
  com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:prepare-reactor-executable-mapping \
  test -DsmartTestPicker.testsFile=ASSIGNMENT \
  -DsmartTestPicker.coverageCollector=ASM -DsmartTestPicker.asm.includes=a.,b.
```

The fixture POM supplies `revision=reactor-revision` and `shardId=reactor-shard`.
Literal-only runs disable JaCoCo through `-Djacoco.skip=true`; other P4 runs retain
its existing prepare-agent binding. Failsafe adds `-Pfailsafe`, uses
`integration-test`, and supplies `smartTestPicker.executionType=failsafe` and
`smartTestPicker.executionId=fixture-it`. Raw logs retain the resulting fork command.

Final four-suite rerun: **BUILD SUCCESSFUL**, 9m 1s, 52 tasks executed.
All four test tasks ran with `--rerun-tasks` on the final production/test code.

| Suite | Tests | Skipped | Failures / errors | Required baseline |
| --- | ---: | ---: | ---: | ---: |
| common | 333 | 11 | 0 / 0 | 333 |
| core | 29 | 5 | 0 / 0 | 29 |
| Maven | 97 | 0 | 0 / 0 | 82 |
| Gradle plugin | 46 | 0 | 0 / 0 | 46 |

No baseline count dropped. The 16 skips remain in the existing common/core suites.
The Maven increase is 11 new real-Maven invocations (five argLine shapes, four
scope rejections and two target variants) plus four preparation unit tests. The
existing fork-guard and single-fork cases were strengthened, not replaced with
unit-only coverage. Per-class counts are retained in the
[JUnit-derived suite summary](raw/suite-summary.txt).

### Retained evidence

- [Final four-suite console log](full-suites-final.log); the earlier successful
  [four-suite run](full-suites.log) preceded the two command-line argLine cases.
- P4: [literal](raw/literal/maven.log), [prepare-agent](raw/prepare-agent/maven.log),
  and [combined](raw/both/maven.log) real Maven logs.
- Additional property cases: [command-line override](raw/user-property/maven.log)
  and [command-line late expansion](raw/user-late-property/maven.log).
- [Cross-module fragment](raw/both/fragment.json) and
  [agent diagnostics](raw/both/diagnostic.json).
- Independent targets: [Surefire](raw/targets-surefire/maven.log) and
  [Failsafe](raw/targets-failsafe/maven.log), with per-module JSON files alongside.
- Rejections: [forkCount=2](raw/fork-guard/rejected-literal/maven.log),
  [missing includes](raw/scope-absent/maven.log), and
  [empty includes](raw/scope-0/maven.log). Other fork/scope cases are retained in
  the same raw evidence tree, without replacing MAVEN-ASM-04 historical records.
- [Reactor integration JUnit results](raw/junit/TEST-com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.xml)
  and [preparation/resolution unit results](raw/junit/TEST-com.sap.oss.smarttestpicker.maven.PreparedAsmMappingTest.xml).

The integration XML includes the unchanged missing-JaCoCo-runtime regression and
the JaCoCo two-fork control. Resolution-version and missing-artifact unit cases
exercise the preparation entry point, not a separate attachment API.

Maven debug environment-variable values are redacted in the retained logs; fork
commands, artifact-resolution paths, realm contents and test results are preserved.
Every new evidence file is below 5 MiB. The repository-wide large-file check still
reports two pre-existing tracked artifacts: `EI-10/artifacts/published-r0-map.json`
(30,045,626 bytes) and `EI-11/artifacts/deployed-smart-test-picker.hpi`
(10,450,073 bytes). They were not modified by this task. Source/documentation
whitespace checks and relative file-link checks passed.

## Explicitly deferred

`moduleEvidenceOutput` currently holds `agent-shell-1` diagnostics, **not** Maven
execution evidence v2, despite its existing filename. These tests stop at `test`
or `integration-test`, before the fixture's verify-bound JaCoCo fragment goals.
`GenerateCoverageFragmentMojo` and aggregation are unchanged. This task does not
claim a complete ASM Maven publication/verification workflow; evidence adaptation
and fragment wiring are the next task.

Also deferred: reactor-derived scope, per-fork outputs and controlled merging,
ClassSource-only Vintage/S7 identity support, and Jenkins integration. The old
raw manual-agent spike remains outside the supported guarded preparation route.
