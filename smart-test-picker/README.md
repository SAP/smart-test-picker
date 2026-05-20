# smart-test-picker (Gradle Plugin)

Gradle plugin for regression test selection. Registers tasks that automate the full pipeline: coverage map generation, test selection, filtered test execution, and HTML reporting.

## Plugin Coordinates

```groovy
plugins {
    id 'io.github.ljubisap.smart-test-picker' version '0.1.11'
}
```

Implementation class: `io.github.ljubisap.smarttestpicker.SmartTestPickerPlugin`

## Configuration DSL

```groovy
smartTestPicker {
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
| `selectTests` | `SelectTestsTask` | Reads map + git diff, writes `build/selected-tests.json` |
| `smartTest` | Gradle `Test` | Runs only selected + unmapped tests via Gradle filter |
| `generateTestReport` | `GenerateTestReportTask` | Produces HTML dashboard + source coverage pages |
| `generateSmartTestMapping` | lifecycle | Chains: `test` -> `generateSmartReports` -> `generateTestCoverageJson` |

## Typical Workflow

### Phase 1: Generate coverage map (on base branch, CI after merge)

```bash
./gradlew test generateSmartReports generateTestCoverageJson
# Or all-in-one:
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

### generateSmartReports

Converts per-test `.exec` files (produced by the JUnit 5 extension in `smart-test-picker-core`) into JaCoCo XML reports. Each test method gets its own XML file.

Input: `build/jacoco/session_*.exec`
Output: `build/smart-reports/session_*.xml`

### generateTestCoverageJson

Parses the XML reports and builds a unified JSON coverage map. The map records which classes and methods each test covers, plus git metadata (commitId, baseBranch, timestamp).

Input: `build/smart-reports/*.xml`
Output: `build/test-coverage-map.json`

### selectTests

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
