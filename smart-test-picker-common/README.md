# smart-test-picker-common

Shared engine classes used by the Gradle plugin, Maven plugin, and CLI. This module contains all pipeline logic and has no build-tool-specific dependencies.

## Engines

| Class | Purpose |
|-------|---------|
| `ExecToXmlEngine` | Converts per-test JaCoCo `.exec` files into XML reports |
| `CoverageMapEngine` | Generates the JSON coverage map from XML reports |
| `TestSelectionEngine` | Orchestrates the full 8-step test selection flow |
| `ReportEngine` | Generates the HTML dashboard report |
| `NewTestDetector` | Identifies unmapped/new test classes |

## Coverage Map (`mapper/`)

### Models

- `CoverageMap` -- top-level model: metadata + testMappings
- `CoverageMapMetadata` -- baseBranch, commitId, timestamp
- `IndexedCoverageMap` -- space-optimized format with integer references

### Reader and Writer

`CoverageMapReader` is the single entry point for loading coverage maps. It auto-detects the format:

```java
CoverageMap map = CoverageMapReader.load(new File("coverage-map.json.gz"));
```

Supported formats:
- Plain JSON (detected by standard structure)
- Indexed JSON (detected by presence of `classIndex` field)
- Gzip (detected by `.gz` file extension)

`CoverageMapEngine` writes the map in any of these formats via `indexed` and `gzip` flags.

### Indexed Format

The plain JSON format repeats class/method FQN strings for every test that covers them. With thousands of tests, this causes extreme duplication. The indexed format stores each unique string once:

```json
{
  "metadata": { "baseBranch": "main", "commitId": "abc123", "timestamp": "..." },
  "classIndex": ["com.example.Foo", "com.example.Bar"],
  "methodIndex": ["com.example.Foo#doWork", "com.example.Bar#init"],
  "testMappings": {
    "MyTest#testSomething": { "classes": [0, 1], "methods": [0, 1] }
  }
}
```

Typical reduction: 944 MB to 67 MB (indexed) or 12 MB (indexed + gzip) for 5755 tests.

### XML Parsing

`CoverageMapperJaxb` uses JAXB (`jakarta.xml.bind`) to parse JaCoCo XML reports. The JAXB model classes in `jacoco/` map to the JaCoCo XML schema:

| Class | XML Element |
|-------|-------------|
| `JacocoReport` | `<report>` (root) |
| `JacocoPackage` | `<package>` |
| `JacocoClass` | `<class>` |
| `JacocoMethod` | `<method>` |
| `JacocoCounter` | `<counter>` |
| `JacocoSessionInfo` | `<sessioninfo>` |
| `JacocoSourceFile` | `<sourcefile>` |
| `JacocoLine` | `<line>` |

## Test Selection (`selector/`)

`TestSelector` implements the dual-granularity selection algorithm:

1. Method-level match: if changed methods are known, select only tests covering those methods
2. Safety fallback: if method-level produces zero hits for a class (method not in map), escalates to class-level for that class
3. Class-level fallback: for classes without method-level info, select all covering tests

`SelectionResult` wraps the outcome (FULL_SUITE, SELECTED, or NONE).
`SelectionOutput` is the JSON model for `selected-tests.json`.

## Remote Store and Local Cache (`store/`)

`RemoteStoreClient` handles HTTP PUT/GET for pushing and pulling coverage maps to a remote store (Nexus, Artifactory, S3, etc.).

`CoverageMapResolver` manages the local file-system cache at `~/.gradle/smart-test-picker/PROJECT_NAME/`:

- `remote-coverage-map.json` -- pulled from CI
- `local-coverage-map.json` -- generated locally

Three selection modes (`PreferMode`):
- `NEAREST` -- picks the map closer to HEAD by commit distance, prefers remote when equal
- `REMOTE` -- always picks remote, falls back to local
- `LOCAL` -- always picks local, falls back to remote

## Change Detection (`change/`)

`GitChangeDetector` uses `git diff` to detect:
- Changed classes (`git diff --name-only`)
- Changed methods (`git diff -U0` with hunk header parsing, requires `*.java diff=java` in `.gitattributes`)
- Changed files and new test classes

## Report Generation (`report/`)

`HtmlReportGenerator` produces a self-contained HTML dashboard with:
- Stat cards (selected/total tests, reduction percentage, changed classes)
- Donut chart visualization
- Coverage matrix (which tests cover which classes)
- Changed code section
- Unmapped test listing with reasons

`SourceCoverageGenerator` creates per-class source coverage HTML pages with line-level highlighting.
