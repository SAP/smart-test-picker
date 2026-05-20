# smart-test-picker-cli

Standalone command-line tool for the coverage pipeline, built with [picocli](https://picocli.info/). Designed for environments where Gradle or Maven are not available (e.g. custom platforms with Ant builds).

## Subcommands

### exec-to-xml

Converts per-test JaCoCo `.exec` files into XML reports.

```bash
smart-test-picker exec-to-xml \
    --exec-dir /path/to/exec-files \
    --output-dir /path/to/xml-output \
    --platform-home /path/to/platform \
    --threads 8
```

**Options:**

| Option | Required | Description |
|--------|----------|-------------|
| `--exec-dir` | Yes | Directory containing `.exec` files |
| `--output-dir` | Yes | Output directory for XML reports |
| `--platform-home` | No | Platform home (auto-discovers classes directories) |
| `--classes-dir` | No | Compiled classes directory (repeatable) |
| `--source-dir` | No | Java source directory for report references |
| `--threads` | No | Parallel threads (default: CPU count) |

**Platform auto-discovery** (`--platform-home`): Scans three locations for compiled classes:
1. `<platformHome>/ext/<extension>/classes/` -- bundled extensions
2. Paths from `extensions.xml` `<path>` elements with `${PLATFORM_BIN_DIR}` resolution
3. `<platformHome>/bootstrap/classes/` -- bootstrap compilation output

### generate-map

Generates a JSON coverage map from per-test JaCoCo XML reports.

```bash
smart-test-picker generate-map \
    --xml-dir /path/to/xml-reports \
    --output coverage-map.json \
    --project-dir /path/to/project \
    --base-branch main \
    --indexed --gzip
```

**Options:**

| Option | Required | Description |
|--------|----------|-------------|
| `--xml-dir` | Yes | Directory containing per-test JaCoCo XML reports |
| `--output` | Yes | Output JSON file path |
| `--project-dir` | No | Project root for git metadata (default: current dir) |
| `--base-branch` | No | Base branch name for metadata (default: main) |
| `--indexed` | No | Use indexed format (integer references, much smaller) |
| `--gzip` | No | Compress output with gzip |

### select-tests

Selects tests impacted by code changes using the coverage map and git diff. Delegates to the 8-step selection flow in `TestSelectionEngine`.

```bash
smart-test-picker select-tests \
    --map /path/to/coverage-map.json.gz \
    --project-dir /path/to/project \
    --output /path/to/selected-tests.json \
    --format json \
    --full-suite-trigger "build.gradle" \
    --full-suite-trigger "gradle.properties"
```

**Options:**

| Option | Required | Description |
|--------|----------|-------------|
| `--map` | Yes | Coverage map file (JSON, indexed, or gzip) |
| `--project-dir` | No | Project root for git commands (default: current dir) |
| `--output` | Yes | Output file for selected tests |
| `--format` | No | Output format: `json` (default), `txt`, `ant` |
| `--max-commit-distance` | No | Max commits before map is stale (default: 500) |
| `--full-suite-trigger` | No | Glob pattern that forces full suite (repeatable) |
| `--test-classes-dir` | No | Compiled test classes directory for new test detection |

**Output formats:**

- `json` -- standard `SelectionOutput` JSON, compatible with Gradle/Maven plugins:
  ```json
  {
    "status": "SELECTED",
    "reason": "42 tests selected out of 5755 total",
    "selectedTests": ["TestClass#method1", "TestClass#method2"],
    "unmappedTests": {"NewTest": "New test -- added after coverage map"}
  }
  ```
- `txt` -- one test per line (selected + unmapped), for shell scripts
- `ant` -- Ant format: `TestClass#method1+method2,OtherTest#method3`

### generate-report

Generates an HTML dashboard report from a coverage map and selection results.

```bash
smart-test-picker generate-report \
    --map /path/to/coverage-map.json.gz \
    --selected-tests /path/to/selected-tests.json \
    --output /path/to/report/index.html \
    --project-dir /path/to/project
```

**Options:**

| Option | Required | Description |
|--------|----------|-------------|
| `--map` | Yes | Coverage map file (JSON, indexed, or gzip) |
| `--selected-tests` | Yes | Selected tests JSON file (output of `select-tests`) |
| `--output` | Yes | Output HTML report file |
| `--xml-dir` | No | Per-test JaCoCo XML reports directory (for source coverage pages) |
| `--source-dir` | No | Java source directory (for source coverage pages) |
| `--project-dir` | No | Project root for git commands (default: current dir) |
| `--max-commit-distance` | No | Max commit distance for display (default: 500) |
| `--class-level-selection` | No | Enable class-level expansion section in report |

The report includes stat cards, donut charts, coverage matrix, changed code listing, unmapped tests, and optionally per-class source coverage pages (when `--xml-dir` and `--source-dir` are provided).

### query

Searches the coverage map for debugging and exploration.

```bash
# Show map statistics
smart-test-picker query --map coverage-map.json.gz --stats

# Find coverage for a specific test
smart-test-picker query --map coverage-map.json.gz \
    --test "com.example.MyTest#testSomething"

# Find all tests covering a class
smart-test-picker query --map coverage-map.json.gz \
    --class com.example.MyService

# Find all tests covering a method
smart-test-picker query --map coverage-map.json.gz \
    --method "com.example.MyService#doWork"

# Substring search across tests, classes, and methods
smart-test-picker query --map coverage-map.json.gz --grep ItemModel
```

**Options:**

| Option | Required | Description |
|--------|----------|-------------|
| `--map` | Yes | Coverage map file (JSON, indexed, or gzip) |
| `--test` | One of these | Show coverage for a specific test |
| `--class` | One of these | Find tests covering a class |
| `--method` | One of these | Find tests covering a method |
| `--grep` | One of these | Substring search (case-insensitive) |
| `--stats` | One of these | Show aggregate statistics |

The query command reads all three map formats (plain, indexed, gzip) transparently.

### pull-map

Pulls a coverage map from a remote HTTP store.

```bash
smart-test-picker pull-map \
    --url https://my-server.example.com/coverage-store \
    --branch main \
    --output /tmp/coverage-map.json \
    --user admin --password secret
```

**Options:**

| Option | Required | Description |
|--------|----------|-------------|
| `--url` | Yes | Remote store base URL |
| `--branch` | No | Base branch name (default: main) |
| `--output` | Yes | Output file path |
| `--user` | No | HTTP Basic Auth username |
| `--password` | No | HTTP Basic Auth password |

### push-map

Pushes a coverage map to a remote HTTP store.

```bash
smart-test-picker push-map \
    --url https://my-server.example.com/coverage-store \
    --branch main \
    --input /tmp/coverage-map.json \
    --user admin --password secret
```

**Options:**

| Option | Required | Description |
|--------|----------|-------------|
| `--url` | Yes | Remote store base URL |
| `--branch` | No | Base branch name (default: main) |
| `--input` | Yes | Coverage map file to upload |
| `--user` | No | HTTP Basic Auth username |
| `--password` | No | HTTP Basic Auth password |

### refreshed-report

One-shot pipeline for custom platform developers: selects impacted tests, runs them via `ant unittests` with JaCoCo per-test splitting, converts `.exec` files to XML, and generates an HTML report with line-level source coverage -- all in a single command.

**Prerequisites:** JaCoCo per-test splitting and method-level test execution must be active on the target platform.

```bash
smart-test-picker refreshed-report \
    --map /tmp/test-coverage-map-indexed.json \
    --platform-home /path/to/platform \
    --exec-dir /tmp/jacoco-exec/ \
    --output /tmp/smart-report/index.html \
    --java-home /Library/Java/JavaVirtualMachines/sapmachine-jdk-17/Contents/Home
```

**Options:**

| Option | Required | Description |
|--------|----------|-------------|
| `--map` | Yes | Coverage map file (JSON, indexed, or gzip) |
| `--platform-home` | Yes | Platform home directory (e.g. `bin/platform/`) |
| `--exec-dir` | No | Directory for JaCoCo per-test `.exec` files (default: `/tmp/jacoco-exec/`) |
| `--output` | No | Output HTML report file (default: `build/reports/smart-test-picker/index.html`) |
| `--max-commit-distance` | No | Max commits before map is stale (default: 500) |
| `--threads` | No | Parallel threads for exec-to-xml conversion (default: CPU count) |
| `--skip-tests` | No | Skip test execution (use existing `.exec` files) |
| `--java-home` | No | Java home for ant (default: `JAVA_HOME` env var) |

**Pipeline steps:**
1. Select tests impacted by code changes (coverage map + git diff)
2. Discover platform extension directories (classes + sources)
3. Run selected tests via `ant unittests` with method-level filtering
4. Convert per-test `.exec` files to XML reports (only for selected tests)
5. Generate HTML dashboard with line-level source coverage for changed classes

## End-to-End Pipeline

The full pipeline on a non-Gradle project (e.g. custom platform with Ant):

```bash
# 1. Convert per-test .exec files to XML reports
smart-test-picker exec-to-xml \
    --exec-dir /path/to/exec-files \
    --output-dir /tmp/xml-reports \
    --platform-home /path/to/platform

# 2. Build coverage map from XML reports
smart-test-picker generate-map \
    --xml-dir /tmp/xml-reports \
    --output /tmp/coverage-map.json \
    --project-dir /path/to/project \
    --indexed --gzip

# 3. Select tests impacted by code changes
smart-test-picker select-tests \
    --map /tmp/coverage-map-indexed.json.gz \
    --project-dir /path/to/project \
    --output /tmp/selected-tests.json

# 4. Generate HTML report
smart-test-picker generate-report \
    --map /tmp/coverage-map-indexed.json.gz \
    --selected-tests /tmp/selected-tests.json \
    --output /tmp/report/index.html \
    --project-dir /path/to/project

# 5. Open report
open /tmp/report/index.html
```

## Building

```bash
./gradlew :smart-test-picker-cli:build

# Run via Gradle
./gradlew :smart-test-picker-cli:run --args="query --map map.json --stats"

# Or build a distribution
./gradlew :smart-test-picker-cli:installDist
./smart-test-picker-cli/build/install/smart-test-picker-cli/bin/smart-test-picker-cli --help
```
