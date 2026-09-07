# smart-test-picker (Gradle Plugin)

Gradle plugin for regression test selection. Registers tasks that automate the full pipeline: coverage map generation, test selection, filtered test execution, and HTML reporting.

## Plugin Coordinates

```groovy
plugins {
    id 'com.sap.oss.smart-test-picker' version '0.1.0'
}
```

Implementation class: `com.sap.oss.smarttestpicker.SmartTestPickerPlugin`

## Configuration DSL

```groovy
smartTestPicker {
    coverageCollector = 'ASM'       // ASM (default) or JACOCO
    revision = providers.environmentVariable('GIT_COMMIT') // fragment revision
    shardId = 'ci-shard-1'          // optional; default: gradle:<mapping task path>
    coverageIncludes = ['com.acme.'] // optional instrumentation package prefixes
    coverageExcludes = ['com.acme.generated.']
    baseBranch = 'main'              // Git branch used as diff baseline (default: 'main')
    maxCommitDistance = 500           // Max commits before map is considered stale (default: 500)
    classLevelSelection = false      // Enable class-level expansion in report (default: false)
    fullSuiteTriggers = [            // Glob patterns that force full suite when matched
        'build.gradle',
        'gradle.properties',
        'src/main/resources/**'
    ]
}
```

## Tasks

The plugin registers the following tasks:

| Task | Type | Description |
|------|------|-------------|
| `generateSmartReports` | inline doLast | Converts `session_*.exec` to `session_*.xml` via JaCoCo |
| `generateTestCoverageJson` | `GenerateTestCoverageJsonTask` | Parses XML reports into `build/test-coverage-map.json` |
| `generateHeadTestInventory` | `GenerateHeadTestInventoryTask` | Discovers exact JUnit identities into `build/head-test-inventory.json` without executing tests |
| `selectTests` | `SelectTestsTask` | Reads map + git diff, writes `build/selected-tests.json` |
| `smartTest` | Gradle `Test` | Runs only selected + unmapped tests via Gradle filter |
| `generateTestReport` | `GenerateTestReportTask` | Produces HTML dashboard + source coverage pages |
| `generateSmartTestCoverage` | Gradle `Test` | Dedicated STP mapping execution; receives exactly one selected collector |
| `generateSmartTestMapping` | lifecycle | Runs `generateSmartTestCoverage`; in JACOCO mode also runs the legacy report/map stages |

## Typical Workflow

### Phase 1: Generate coverage map (on base branch, CI after merge)

```bash
./gradlew generateSmartTestMapping
```

### Phase 2: Select and run impacted tests (on feature branch)

```bash
./gradlew selectTests
./gradlew smartTest
```

### Phase 3: View results

```bash
./gradlew generateTestReport
open build/reports/smart-test-picker/index.html
```

## Task Details

### Coverage collectors

`ASM` is the production default and emits a schema-v2 fragment directly at
`build/stp/coverage/_generateSmartTestCoverage/<sanitized-shardId>/fragment.json`. The plugin resolves
the version-aligned `com.sap.oss.smart-test-picker:stp-agent` artifact through its internal resolvable
`stpAgent` configuration and supplies `-javaagent`; users do not locate the JAR or add JVM arguments.
In a source multi-project build this selects `:stp-agent`'s consumable shaded variant. Published usage
resolves the agent coordinate at exactly the Gradle plugin version. The publication contains the
runtime, JUnit adapter, and relocated ASM; consumers do not assemble those internals.
The default revision is `-Dstp.revision`, then `GIT_COMMIT`, then the explicit diagnostic value
`UNKNOWN`; CI should set `revision` or one of those inputs. `-Dstp.shardId` can override the normal
`gradle::generateSmartTestCoverage` identity.

Set `coverageCollector = 'JACOCO'` to restore the pre-TASK-27 per-test exec/XML/legacy-map flow. There
is deliberately no `BOTH`, `AUTO`, or union mode. JaCoCo supplies class coverage and legacy method
strings, but not exact descriptor-aware methods, bounded setup scopes, or direct schema-v2 fragments.
The legacy collector runtime and JaCoCo `test.exec` destination are configured only on the dedicated
mapping task, which then orders per-test exec conversion, XML generation, and legacy JSON generation.
ASM supplies class coverage, exact descriptor-aware method coverage, bounded supported setup scopes,
and direct schema-v2 fragments.

The collector is attached only to `generateSmartTestCoverage`, not to normal `test` or `smartTest`.
An independently applied project JaCoCo plugin may still attach its own agent to that mapping JVM;
this preserves the ordinary JaCoCo exec/report while ASM emits the STP fragment.

### generateSmartReports

Converts per-test `.exec` files (produced by the JUnit 5 extension in `smart-test-picker-core`) into JaCoCo XML reports. Each test method gets its own XML file.

Input: `build/jacoco/session_*.exec`
Output: `build/smart-reports/session_*.xml`

### generateTestCoverageJson

Parses the XML reports and builds a unified JSON coverage map. The map records which classes and methods each test covers, plus git metadata (commitId, baseBranch, timestamp).

Input: `build/smart-reports/*.xml`
Output: `build/test-coverage-map.json`

### selectTests

`selectTests` automatically depends on `generateHeadTestInventory`; users do not create the inventory
file. Discovery mirrors the standard `test` task's test class directories and runtime classpath.

Runs the 8-step selection flow:
1. Load coverage map
2. Validate metadata and commitId
3. Check commit distance
4. Check fullSuiteTriggers
5. Ensure `.gitattributes` has `*.java diff=java`
6. Detect changed classes and methods via `git diff`
7. Detect unmapped/new tests
8. Run dual-granularity matching

Input: `build/test-coverage-map.json`
Output: `build/selected-tests.json`

### smartTest

Standard Gradle `Test` task with filters applied from `selected-tests.json`. Runs only impacted tests plus unmapped/new tests.

### generateTestReport

Produces a self-contained HTML report with stat cards, donut charts, coverage matrix, changed code listing, unmapped tests, and per-class source coverage pages.

Output: `build/reports/smart-test-picker/index.html`
