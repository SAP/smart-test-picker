# smart-test-picker-maven

Maven plugin providing mojo implementations for the coverage pipeline.

## Collector isolation

Core's SPI components are inactive unless the test JVM receives
`smartTestPicker.collectionEnabled=true`. Plain `mvn test` with consumer-declared
core works without any agent, with extension autodetection on or off. Inactive
components ignore even an invalid `smartTestPicker.coverageCollector` value.

For active collection, `smartTestPicker.coverageCollector=ASM|JACOCO` selects the
collector; absent/blank selection defaults to JaCoCo. Schema-v3 mapping preparation
sets activation and selection in Surefire/Failsafe. Legacy/manual mapping profiles
must explicitly enable collection; an output directory alone does not activate it.
For prepared ASM mapping, select `-DsmartTestPicker.coverageCollector=ASM` and
provide explicit `-DsmartTestPicker.asm.includes=your.package.,another.package.`.
Preparation supplies activation and attaches the agent automatically after fork
validation. Core remains silent in active ASM mode.

Managed Maven bootstrap propagates the selected value (user property, then project
property, then JVM property) and injects core only for JaCoCo. Automatic ASM
attachment belongs exclusively to mapping preparation, not managed selection.
ASM fragment finalization and reactor aggregation use the evidence contract below.
Managed selected execution also explicitly activates its core evidence collection.
Active JaCoCo without its runtime still fails closed with the existing error
message. See [the activation correction and validation](../verification/maven-asm-03-fix/REPORT.md).

### ASM mapping fork restriction

When ASM is selected, `prepare-reactor-executable-mapping` rejects unsafe fork
settings before test execution. Single-output ASM mapping requires `forkCount=1`
and `reuseForks=true`. Concurrent forks and successive non-reused forks overwrite
the same fragment; `forkCount=0` does not start the required agent fork.
CPU-relative counts, unresolved expressions, and legacy `forkMode` are rejected
conservatively. JaCoCo preparation is unchanged.

The check uses the effective reactor model (including inherited configuration,
plugin management and active profiles), execution-level configuration and Maven's
parameter expression evaluator for user, project and JVM properties. Validated
settings are frozen as literal values for the subsequent provider execution.
All configurations of the chosen provider in assigned modules are checked, even
if a particular execution will not run. Run preparation before the test lifecycle.
Attaching the agent manually without this goal bypasses the guard and is not the
supported prepared path. Per-fork merging remains deferred. See the
[fork guard evidence and limitations](../verification/maven-asm-04/REPORT.md).

### Prepared ASM attachment

With an existing schema-v3 shard assignment, matching revision/shard inputs and
compiled production outputs from the exact-head preparation build:

```sh
mvn com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:prepare-reactor-executable-mapping \
  test -DsmartTestPicker.testsFile="$ASSIGNMENT" \
  -DsmartTestPicker.revision="$REVISION" -DsmartTestPicker.shardId="$SHARD" \
  -DsmartTestPicker.coverageCollector=ASM
```

Do not add a manual STP `-javaagent` argument. With no includes property, preparation
derives package prefixes from compiled reactor production classes. Maven's collected
project model also identifies reactor dependencies omitted by `-pl`: their installed
main JARs supply packages and allowed code sources. Install the exact head first;
third-party dependencies and test JARs are not scanned. No scope is inferred from
groupId. Default-package classes cannot be represented and fail preparation.

An explicit nonblank `smartTestPicker.asm.includes` overrides derived prefixes;
blank/malformed overrides fail. Optional
`smartTestPicker.asm.excludes` accepts the same comma-separated package-prefix
syntax. Prefixes may use dots or slashes and are normalized to trailing dots.
Both paths enforce an exact production code-source allowlist, excluding custom
test outputs, test JARs and third-party classes even when they share packages.
The installed artifacts must correspond to the prepared head; this is not artifact
provenance verification. A module omitted from Maven's collected model cannot be
identified as reactor-owned. See [scope evidence and boundaries](../verification/maven-asm-08/REPORT.md).

Zero instrumented classes across a mapping fork fails closed with the effective
scope/count; no fragment/evidence is accepted. An individual test with no edges
remains valid when the fork did instrument production classes. This also means a
fork with only disabled tests, or no production classes loaded at all, fails this
guard rather than publishing an unverified empty map.

Preparation resolves `com.sap.oss.smart-test-picker:stp-agent:jar:<plugin-version>`
through the current Maven repository session and **project dependency repositories**.
Publish the shaded `stp-agent` artifact at the same version as the Maven plugin;
declaring it as a plugin or test dependency is unnecessary. Missing version metadata
or an unresolvable artifact fails preparation. The agent JAR supplies its bundled
runtime and JUnit adapter without adding them to the Maven plugin realm.

Existing provider `argLine` content stays first and STP is appended last. Use
`@{argLine}` to retain JaCoCo `prepare-agent` late expansion with literal options,
for example `@{argLine} -Xmx2g -Dproject.option=value`. An absent `argLine` uses
that late-expansion property automatically; `${argLine}` is preserved as late
expansion as well. The proven combined order is JaCoCo, project options, STP.
No reversed-agent-order claim is made. Quoted STP arguments support paths with
spaces; values containing agent-protocol delimiters or unresolved placeholders
fail preparation.

Each assigned target gets its own canonical target, revision, shard, deterministic
run ID, fragment output and diagnostic output. Unassigned modules receive no agent.
The fragment uses `smartTestPicker.moduleFragmentOutput`; diagnostics use
`smartTestPicker.moduleDiagnosticOutput`. `smartTestPicker.moduleEvidenceOutput`
is reserved for real execution evidence v2, written by `generate-coverage-fragment`.
See [attachment validation](../verification/maven-asm-05/REPORT.md) for the earlier
attachment-only stage and [the current evidence decision](../verification/maven-asm-06/DECISION.md).
The [Maven ASM fragment/evidence validation](../verification/maven-asm-06/REPORT.md)
records real Surefire/Failsafe outcomes and aggregation checks.

### Project JaCoCo report coexistence

The prepared ASM path preserves the project's JaCoCo XML counters in the tested
Maven/Surefire configuration, with both a literal JaCoCo agent argLine and
`prepare-agent` plus `@{argLine}`. The permanent regression compares every class
and method counter, including enum/record/bridge filters and an implicit exception,
and checks the actual fork command for JaCoCo-before-STP ordering. Core can remain
on the test classpath: the measured ASM runs make no reset/dump/snapshot calls
against the project's JaCoCo runtime.

Keep the project JaCoCo agent before STP; do not manually reverse the order.
ASM-first causes execution-data/class mismatches in the ordinary report and changes
generated-code filtering when analyzing the transformed bytes. This bounded Maven
proof does not establish Gradle report preservation or arbitrary agent order/JVM
compatibility. See [versions, exact counters and inverse-order evidence](../verification/maven-asm-07/REPORT.md).

### ASM fragment finalization and aggregation

Keep the existing mapping profile's `generate-coverage-fragment` binding at
`verify`. `generate-reports`, if bound, skips JaCoCo XML conversion in ASM mode.
With that project configuration (as in the `schema-v3-reactor` fixture), the full
Maven collection command is:

```sh
mvn com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:prepare-reactor-executable-mapping \
  verify com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:aggregate-reactor-coverage-fragment \
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.coverageCollector=ASM \
  -DsmartTestPicker.testsFile="$ASSIGNMENT" \
  -DsmartTestPicker.revision="$REVISION" -DsmartTestPicker.shardId="$SHARD" \
  -DsmartTestPicker.fragmentOutput="$SHARD_FRAGMENT" -DsmartTestPicker.evidenceOutput="$SHARD_EVIDENCE"
```

Preparation sets per-module paths under the configured build directory's `stp/`:

| Artifact | Filename | Writer |
| --- | --- | --- |
| Schema-v3 fragment | `coverage-fragment-v3<suffix>.json` | ASM agent |
| Diagnostic (`agent-shell-1`) | `asm-agent-diagnostic<suffix>.json` | ASM agent |
| Execution evidence v2 | `execution-evidence-v2<suffix>.json` | Maven fragment goal |

Unqualified targets have no suffix. Qualified targets retain the preparation
suffix, for example `-module-b_failsafe_fixture-it`. Aggregation resolves all
assigned targets against the active reactor and reads their exact qualified files;
it does not scan for a latest file. Collect each provider with its matching
assignment and `executionType`/`executionId`, then aggregate their union assignment.
Preparation clears only the current target's prior outputs, preserving other
provider results. No per-fork merge is supported.

Finalization validates the immutable agent fragment and derives positive evidence:
mapped and FAILED identities are EXECUTED; SKIPPED (disabled or aborted-only)
identities are NON_EXECUTED. FAILED retains no partial coverage and dominates an
aborted invocation of the same logical test. SKIPPED remains in the fragment;
it is not an executed coverage owner. Missing observations are never inferred as
non-execution. Binding, exact assignment partition, duplicates and collection
completeness are validated before the final pair is accepted.

Maven test failures still fail the build. STP does not enable test-failure-ignore;
inspection of failed-test evidence must preserve the original test result. The
agent fragment is never rewritten by finalization. Missing or unsafe inputs leave
no successful evidence output. This does not enable ASM in Jenkins or add
per-fork merging or ClassSource-only Vintage support.

## Coordinates

```xml
<plugin>
    <groupId>com.sap.oss.smart-test-picker</groupId>
    <artifactId>smart-test-picker-maven</artifactId>
    <version>0.3.0-SNAPSHOT</version>
</plugin>
```

## Goals

| Goal | Mojo Class | Description |
|------|-----------|-------------|
| `generate-coverage-map` | `GenerateCoverageMapMojo` | Generates JSON coverage map from per-test XML reports |
| `generate-coverage-fragment` | `GenerateCoverageFragmentMojo` | Generates JaCoCo schema-v2/v3 fragments or validates immutable ASM schema-v3 fragments and writes execution evidence |
| `generate-head-test-inventory` | `GenerateHeadTestInventoryMojo` | Discovers exact JUnit identities for the current module without running tests |
| `generate-reactor-head-test-inventory` | `GenerateReactorHeadTestInventoryMojo` | Writes one authoritative inventory for the effective reactor |
| `prepare-reactor-executable-mapping` | `PrepareReactorExecutableMappingMojo` | Routes a schema-3 executable assignment to exact reactor targets before test execution |
| `aggregate-reactor-coverage-fragment` | `AggregateReactorCoverageFragmentMojo` | Validates and atomically publishes the reactor fragment/evidence pair |
| `merge-executable-head-test-inventories` | `MergeExecutableHeadTestInventoriesMojo` | Merges target-qualified inventory parts |
| `merge-executable-target-fragments` | `MergeExecutableTargetFragmentsMojo` | Merges target-owned executable fragments |
| `select-tests` | `SelectTestsMojo` | Runs test selection and writes `selected-tests.json` |
| `generate-report` | `GenerateReportMojo` | Generates HTML dashboard report |
| `generate-reports` | `GenerateReportsMojo` | Converts `.exec` files to XML reports |
| `merge-coverage-maps` | `MergeCoverageMapsMojo` | Merges coverage maps from multi-module builds |
| `merge-test-metrics` | `MergeTestMetricsMojo` | Merges per-test metrics from multi-module builds |

## Multi-Module Support

For executable schema 3, use the reactor inventory goal with `-DsmartTestPicker.schemaVersion=3`.
It records canonical Maven execution targets rather than inferring module ownership from package or
class names. Jenkins executable mapping supplies an assignment, invokes
`prepare-reactor-executable-mapping`, executes the ordinary configured test lifecycle, and invokes
`aggregate-reactor-coverage-fragment`. Logical schema-2 goals remain supported for their existing
contract; they are not a substitute for target-qualified ownership.

Use the aggregator inventory goal after test compilation to write exactly one inventory under the
Maven execution root (by default, `.stp/head-test-inventory.json`):

```bash
mvn process-test-classes com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:generate-reactor-head-test-inventory \
  -DsmartTestPicker.prHeadRevision="$GIT_COMMIT"
```

`generate-head-test-inventory` remains a single-module goal. The reactor goal uses the effective
`${reactorProjects}` list, so Maven `-pl ... -am` limits the inventory to that effective reactor.

Selector goals generate inventory automatically. Root and `smart-test` flows discover every non-POM
reactor module using its test output and test runtime classpath; an exact cross-module identity
collision fails open.

For multi-module Maven projects, each module generates its own coverage map. The `merge-coverage-maps` goal combines them into a single map.

The merge is typically bound to the last module in the reactor using the `isLastModule()` pattern:

```xml
<plugin>
    <groupId>com.sap.oss.smart-test-picker</groupId>
    <artifactId>smart-test-picker-maven</artifactId>
    <version>0.3.0-SNAPSHOT</version>
    <executions>
        <execution>
            <id>generate-map</id>
            <goals>
                <goal>generate-coverage-map</goal>
            </goals>
        </execution>
        <execution>
            <id>merge-maps</id>
            <goals>
                <goal>merge-coverage-maps</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

## Test Filtering

`SmartTestFilter` integrates with Maven Surefire/Failsafe to filter tests based on the `selected-tests.json` output. It reads the selection result and includes only the selected and unmapped tests.

## Configuration

Common parameters across mojos:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `baseBranch` | `main` | Git branch used as diff baseline |
| `maxCommitDistance` | `500` | Maximum commits before map is stale |
| `coverageMapFile` | `${project.build.directory}/test-coverage-map.json` | Coverage map path |
| `selectedTestsFile` | `${project.build.directory}/selected-tests.json` | Selection output path |

The `select-tests`, `root-select-tests`, and `smart-test` goals also support additive explicit PR mode
through `smartTestPicker.integrationRevision`, `smartTestPicker.prBaseRevision`, and
`smartTestPicker.prHeadRevision`; setting any one requires all three frozen full commit IDs. Inventory
generation uses the target test runtime (`ResolutionScope.TEST`) and stamps `prHeadRevision` only after
workspace HEAD verification. The goals delegate revision policy and committed
`mapRevision..prHeadRevision` analysis to common core. `BASE_OUT_OF_DATE` prevents selective execution,
`ERROR` fails the goal, and normal `FULL_SUITE`, `NONE`, and `SELECTED` retain their established
Surefire/filter behavior. With no explicit revisions the local/worktree flow is unchanged.

## Per-Test Coverage Collection (JaCoCo)

Collection reuses the JaCoCo agent active inside the Surefire/Failsafe fork. Per-test execution
data is obtained from the runtime API and written to `session_*.exec` under `stp.exec.dir`; the
project agent's `destfile` is neither rewritten nor used as scratch storage. A project-owned literal
`argLine`, JaCoCo `prepare-agent` (including `@{argLine}` late evaluation), and STP's existing
agent-supplied setup therefore share the same single-agent capture path. If no active runtime is
visible in the test fork, mapping fails immediately with a focused diagnostic. Reset/snapshot is
sequential and does not add support for parallel per-test mapping.

The supported runtime API exposes one active agent but does not enumerate agents. STP never attaches
a second agent; JVMs independently configured with multiple JaCoCo agents remain unsupported and
cannot be diagnosed more precisely through that API.

The Maven plugin requires `smart-test-picker-core` as a test dependency for per-test coverage. On projects using JUnit Platform 6.x or Surefire 3.x, enable Jupiter extension auto-detection in surefire:

```xml
<dependencies>
    <dependency>
        <groupId>com.sap.oss.smart-test-picker</groupId>
        <artifactId>smart-test-picker-core</artifactId>
        <version>0.3.0-SNAPSHOT</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
                <properties>
                    <configurationParameters>
                        junit.jupiter.extensions.autodetection.enabled = true
                    </configurationParameters>
                </properties>
            </configuration>
        </plugin>
    </plugins>
</build>
```

## Managed selected-test execution

The packaged Jenkins Maven adapter registers `StpMavenLifecycleParticipant`. It is inactive unless
the invocation-scoped `stp.maven.execution.management=true` property is supplied by
`smartTestPicker(manageMavenExecution: true)`. In managed mode it:

1. decodes and validates the common execution-plan contract;
2. resolves canonical Maven targets against `MavenSession.getAllProjects()`;
3. rejects unresolved targets and conflicting caller reactor selectors;
4. restricts `SELECT` to the distinct owning modules before their lifecycles run;
5. installs target-qualified Surefire/Failsafe method filters; and
6. validates invocation-scoped identity sidecars against the exact plan.

`RUN_ALL` leaves the reactor unrestricted, while an empty `SELECT` is handled by Jenkins as an
explicit managed-body skip. The participant never adds `-am`. A preceding exact-head install must
provide dependencies required by narrowed modules. Final evidence is written even after a normal
Maven failure when the session callback is reached; it cannot make the failed Maven invocation
successful.

## Schema-v2 fragment production and reactor aggregation

The additive `generate-coverage-fragment` goal consumes the identity sidecars emitted by
`smart-test-picker-core` and the status/XML artifacts emitted by `generate-reports`. CI must supply
the exact revision and shard; the plugin never discovers Git revision for this goal.

```bash
mvn verify \
  com.sap.oss.smart-test-picker:smart-test-picker-maven:aggregate-reactor-coverage-fragment \
  -DsmartTestPicker.revision="$GIT_COMMIT" \
  -DsmartTestPicker.shardId="maven-1" \
  -DsmartTestPicker.fragmentOutput=target/coverage-fragment-v2.json \
  -DsmartTestPicker.evidenceOutput=target/execution-evidence-v1.json
```

By default, `generate-coverage-fragment` preserves the single-module contract and writes the paths
selected by `smartTestPicker.fragmentOutput` and `smartTestPicker.evidenceOutput` (defaulting to
`target/coverage-fragment-v2.json` and `target/execution-evidence-v1.json`). A reactor build opts in
to module-local intermediates with `smartTestPicker.moduleFragmentOutput` and
`smartTestPicker.moduleEvidenceOutput`; both must be configured together and conventionally point
to `target/stp/coverage-fragment-v2.json` and `target/stp/execution-evidence-v1.json`. The public,
`aggregator=true` `aggregate-reactor-coverage-fragment` goal consumes only those conventional files
after the reactor's `verify` lifecycle and atomically publishes the final paths supplied by Jenkins.
Invoke it after `verify` in the same Maven command; a root project's `verify` runs before child
modules reach that phase.

The aggregator uses `STP_MAPPING_TESTS_FILE` as its authoritative shard assignment
(`smartTestPicker.testsFile` is available for focused invocations). It rejects missing assigned
module outputs, binding mismatches, global identity duplication, evidence overlap, and identities
outside the shard. Non-POM modules with no assigned JUnit tests and no sidecars do not participate.
There are no defaults for revision
or shard. Maven preserves JVM method descriptors from JaCoCo XML and converts positive
`setup:<className>` sessions into `FRAMEWORK_SETUP` scopes owned by that test container.
Descriptor preservation does not guarantee method-entry completeness or temporal attribution
soundness. See the [capability verification](../verification/stp-capabilities-01/REPORT.md).
A missing identity, exec, status, XML,
or a malformed artifact makes the local fragment incomplete. This is collector-local truth only;
expected-test and expected-shard completeness remain orchestration concerns.
