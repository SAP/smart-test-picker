[![REUSE status](https://api.reuse.software/badge/github.com/SAP/smart-test-picker)](https://api.reuse.software/info/github.com/SAP/smart-test-picker)
![Java](https://img.shields.io/badge/Java-17%2B-blue)
![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)

# Smart Test Picker

A build plugin for **regression test selection** in Java projects. It selects and runs only the tests affected by code changes, instead of the entire test suite. Selection is based on **per-test runtime coverage data** captured via JaCoCo, not static analysis, so it correctly handles reflection, dynamic proxies, and dependency injection.

## How It Works

```
Developer changes code
  -> Plugin detects changed classes/methods (git diff)
  -> Loads per-test coverage map (JSON)
  -> Matches changes against map -> list of impacted tests
  -> Adds unmapped/new tests (safety: always run unknown tests)
  -> Runs only impacted tests via build tool filter
  -> Generates HTML report showing what was selected and why
```

**Safety invariant:** worst case = full suite = same as without the plugin.

## Key Features

- **Per-test granularity** - each test method gets its own coverage profile
- **Dual-granularity selection** - method-level match first, class-level fallback when method info is unavailable
- **Both committed and uncommitted changes** - two git diffs per detection: `commitId..HEAD` + working tree
- **New/unknown tests always run** - tests not in the coverage map are always included
- **Fallback to full suite** - if the coverage map is missing or stale, runs all tests
- **No application code changes required** - everything via test instrumentation and build tooling
- **HTML report** - dashboard with stat cards, coverage matrix, changed code, and per-class source coverage pages

## Requirements

- **Java** 17 or later
- **Gradle** 8.x or **Maven** 3.9+ (depending on which plugin you use)
- **JaCoCo** 0.8.x (included as a dependency)
- **Git** repository with `*.java diff=java` in `.gitattributes` (for method-level detection)

## Quick Start

### Gradle

```groovy
// build.gradle
plugins {
    id 'io.github.ljubisap.smart-test-picker' version '0.1.0'
}

// Optional configuration
smartTestPicker {
    baseBranch = 'main'          // default: 'main'
    maxCommitDistance = 500       // default: 500
}
```

```bash
# Phase 1 (on base branch): generate coverage map
./gradlew test generateSmartReports generateTestCoverageJson

# Phase 2 (on feature branch): select and run impacted tests
./gradlew selectTests smartTest

# Phase 3: view report
./gradlew generateTestReport
open build/reports/smart-test-picker/index.html
```

### Maven

```xml
<plugin>
    <groupId>io.github.ljubisap</groupId>
    <artifactId>smart-test-picker-maven</artifactId>
    <version>0.1.0</version>
</plugin>
```

```bash
# Phase 1 (on base branch): generate coverage map
mvn verify -Psmart-test-picker

# Phase 2 (on feature branch): one-command smart test run
mvn io.github.ljubisap:smart-test-picker-maven:0.1.0:smart-test

# View report
open target/reports/smart-test-picker/index.html
```

### CLI

For environments without Gradle or Maven (e.g. custom platforms with Ant builds):

```bash
smart-test-picker select-tests --map coverage-map.json --output selected.json
smart-test-picker generate-report --map coverage-map.json --selected selected.json --output report/
```

## Project Structure

```
smart-test-picker-core/       JUnit 5 extension for per-test JaCoCo sessions
smart-test-picker-common/     Shared engine (no build-tool dependency)
smart-test-picker/            Gradle plugin
smart-test-picker-maven/      Maven plugin
smart-test-picker-cli/        Standalone CLI (picocli + shadowJar)
```

| Module | Description | README |
|--------|-------------|--------|
| [smart-test-picker-core](smart-test-picker-core/) | JUnit 5 extension that assigns a unique JaCoCo session per test method | [README](smart-test-picker-core/README.md) |
| [smart-test-picker-common](smart-test-picker-common/) | Shared engines: selection, coverage mapping, report generation, git change detection | [README](smart-test-picker-common/README.md) |
| [smart-test-picker](smart-test-picker/) | Gradle plugin with tasks for the full pipeline | [README](smart-test-picker/README.md) |
| [smart-test-picker-maven](smart-test-picker-maven/) | Maven plugin with mojos for multi-module projects | [README](smart-test-picker-maven/README.md) |
| [smart-test-picker-cli](smart-test-picker-cli/) | Standalone CLI for non-Gradle/Maven environments | [README](smart-test-picker-cli/README.md) |

## Selection Algorithm

The selector uses a dual-granularity approach:

1. **Method-level match (precise):** if changed methods are known, select only tests whose coverage includes those specific methods
2. **Class-level fallback:** for classes without method-level info, select all tests touching that class

This avoids over-selection (running every test that touches a class) while maintaining safety (falling back when precise info isn't available).

### Selection Statuses

| Status | Meaning | Behavior |
|--------|---------|----------|
| `FULL_SUITE` | Map missing, stale, or invalid | No filter - run everything |
| `SELECTED` | Specific impacted tests identified | Run selected + unmapped tests |
| `NONE` | No production code changes | Run only unmapped tests; if none, skip all |

## Building from Source

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Publish to local Maven repository (for testing)
./gradlew publishToMavenLocal
```

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc. via [GitHub issues](https://github.com/SAP/smart-test-picker/issues). Contribution and feedback are encouraged and always welcome. For more information about how to contribute, the project structure, as well as additional contribution information, see our [Contribution Guidelines](CONTRIBUTING.md).

## Security / Disclosure

If you find any bug that may be a security problem, please follow our instructions at [in our security policy](https://github.com/SAP/smart-test-picker/security/policy) on how to report it. Please do not create GitHub issues for security-related doubts or problems.

## Code of Conduct

We as members, contributors, and leaders pledge to make participation in our community a harassment-free experience for everyone. By participating in this project, you agree to abide by its [Code of Conduct](https://github.com/SAP/.github/blob/main/CODE_OF_CONDUCT.md) at all times.

## Licensing

Copyright 2026 SAP SE or an SAP affiliate company and smart-test-picker contributors. Please see our [LICENSE](LICENSE) for copyright and license information. Detailed information including third-party components and their licensing/copyright information is available [via the REUSE tool](https://api.reuse.software/info/github.com/SAP/smart-test-picker).
