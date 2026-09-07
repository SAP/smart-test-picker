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
| `select-tests` | `SelectTestsMojo` | Runs test selection and writes `selected-tests.json` |
| `generate-report` | `GenerateReportMojo` | Generates HTML dashboard report |
| `generate-reports` | `GenerateReportsMojo` | Converts `.exec` files to XML reports |
| `merge-coverage-maps` | `MergeCoverageMapsMojo` | Merges coverage maps from multi-module builds |
| `merge-test-metrics` | `MergeTestMetricsMojo` | Merges per-test metrics from multi-module builds |

## Multi-Module Support

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

## Per-Test Coverage Collection (JaCoCo)

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

## Schema-v2 fragment production

The additive `generate-coverage-fragment` goal consumes the identity sidecars emitted by
`smart-test-picker-core` and the status/XML artifacts emitted by `generate-reports`. CI must supply
the exact revision and shard; the plugin never discovers Git revision for this goal.

```bash
mvn verify \
  -DsmartTestPicker.revision="$GIT_COMMIT" \
  -DsmartTestPicker.shardId="maven-1" \
  -DsmartTestPicker.fragmentOutput=target/coverage-fragment-v2.json
```

The default fragment path is `target/coverage-fragment-v2.json`; there are no defaults for revision
or shard. Maven preserves exact method descriptors from JaCoCo XML and emits no setup scopes because
the JaCoCo adapter has no affected-container setup ownership. A missing identity, exec, status, XML,
or a malformed artifact makes the local fragment incomplete. This is collector-local truth only;
expected-test and expected-shard completeness remain orchestration concerns.
