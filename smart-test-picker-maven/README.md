# smart-test-picker-maven

Maven plugin providing mojo implementations for the coverage pipeline.

## Coordinates

```xml
<plugin>
    <groupId>com.sap.oss.smart-test-picker</groupId>
    <artifactId>smart-test-picker-maven</artifactId>
    <version>0.1.0</version>
</plugin>
```

## Goals

| Goal | Mojo Class | Description |
|------|-----------|-------------|
| `generate-coverage-map` | `GenerateCoverageMapMojo` | Generates JSON coverage map from per-test XML reports |
| `generate-coverage-fragment` | `GenerateCoverageFragmentMojo` | Generates a revision/shard-bound schema-v2 fragment |
| `generate-head-test-inventory` | `GenerateHeadTestInventoryMojo` | Discovers exact JUnit identities for the current module without running tests |
| `generate-reactor-head-test-inventory` | `GenerateReactorHeadTestInventoryMojo` | Writes one authoritative inventory for the effective reactor |
| `select-tests` | `SelectTestsMojo` | Runs test selection and writes `selected-tests.json` |
| `generate-report` | `GenerateReportMojo` | Generates HTML dashboard report |
| `generate-reports` | `GenerateReportsMojo` | Converts `.exec` files to XML reports |
| `merge-coverage-maps` | `MergeCoverageMapsMojo` | Merges coverage maps from multi-module builds |
| `merge-test-metrics` | `MergeTestMetricsMojo` | Merges per-test metrics from multi-module builds |

## Multi-Module Support

Use the aggregator inventory goal after test compilation to write exactly one inventory under the
Maven execution root (by default, `target/head-test-inventory.json`):

```bash
mvn process-test-classes com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0:generate-reactor-head-test-inventory \
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
    <version>0.1.0</version>
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
        <version>0.1.0</version>
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
or shard. Maven preserves exact method descriptors from JaCoCo XML and emits no setup scopes because
the JaCoCo adapter has no affected-container setup ownership. A missing identity, exec, status, XML,
or a malformed artifact makes the local fragment incomplete. This is collector-local truth only;
expected-test and expected-shard completeness remain orchestration concerns.
