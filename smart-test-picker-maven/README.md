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
