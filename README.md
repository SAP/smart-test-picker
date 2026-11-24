[![CI](https://github.com/SAP/smart-test-picker/actions/workflows/ci.yml/badge.svg)](https://github.com/SAP/smart-test-picker/actions/workflows/ci.yml)
[![REUSE status](https://api.reuse.software/badge/github.com/SAP/smart-test-picker)](https://api.reuse.software/info/github.com/SAP/smart-test-picker)

# Smart Test Picker

STP records which production classes and methods each test exercises, then uses that map and a Git
diff to select relevant tests. New, changed and unmapped tests are included conservatively. Runtime
coverage describes the observed executions, not every possible path through the application.

The schema-v3 workflow keeps build execution targets separate: inventory, assignment, mapping,
aggregation, join and selection. A fragment is one shard's collection result; the published map is
the validated join of the planned shards. Maps use a compact streaming gzip representation.

## Requirements and boundaries

- Java 17 or later. All modules compile against the Java 17 API.
- Gradle 8.0 to below 10.0; inventory/filter differentials cover 8.14, 9.5.1, 9.7.1 and 9.9.1.
- Maven 3.9; preparation is qualified on Maven 3.9.15. Maven 4 is untested.
  Surefire/Failsafe 3.2.5, 3.5.6 and 3.6.1 are qualified; 3.0.0 and 3.1.2 have only a defaults probe.
  2.22.2 is rejected.
- JUnit Platform test execution and a Git checkout at the declared full revision. Configure test
  filters in the build, not Gradle `--tests` or Maven `-Dtest`/`-Dit.test` mapping invocations.
- Supply dependency repositories and prepare production dependencies at the same revision.

Both tools default to **ASM** for schema-v3 mapping; the examples also name it explicitly.
Set `smartTestPicker.coverageCollector=JACOCO` to retain Maven's previous collector.
Maven's legacy schema-v1/v2 flows still default to JaCoCo.
ASM attaches STP's version-aligned agent automatically and derives scope from production outputs.
A build that instruments no production class is rejected; an individual test with no edges is valid.

JACOCO uses the project's active agent. For v3 mapping, build-tool-managed attachment writes to an
STP-owned file and project JaCoCo reports/checks are skipped. Run normal coverage separately.
A literal project-owned `-javaagent` is not rewritten and may receive partial data. ASM can coexist
with project JaCoCo without its per-test resets. Both collectors retain cross-module production edges.

## Quick start: Gradle

These examples use a single Java project with an existing JUnit Platform `test` task. For multiple
tasks/projects see the [Gradle reference](smart-test-picker/README.md). The current development
version is `0.3.0-SNAPSHOT`; this is not a claim that it is available from a public release registry.
For a local source build, run `./gradlew publishToMavenLocal` in this repository and configure
`mavenLocal()` in the consumer's plugin management and dependency repositories.

Consumer `settings.gradle`:

```groovy
pluginManagement {
    repositories { mavenLocal(); gradlePluginPortal(); mavenCentral() }
}
```

Add to the consumer's `build.gradle` (keep its existing JUnit dependencies):

```groovy
plugins {
    id 'java'
    id 'com.sap.oss.smart-test-picker' version '0.3.0-SNAPSHOT'
}
repositories { mavenLocal(); mavenCentral() }
test { useJUnitPlatform() }
smartTestPicker {
    runtimeSchemaVersion = 3
    mappingTestTasks = [':test']
    coverageCollector = 'ASM'
}
```

Generate the inventory at the checked-out revision:

```sh
REVISION="$(git rev-parse HEAD)"
mkdir -p stp-run/shards
./gradlew generateGradleExecutableHeadTestInventory \
  -Dstp.revision="$REVISION" -Dstp.executableInventoryDiscovery=true
cp build/executable-head-test-inventory.json stp-run/head-inventory.json
```

Create the one-shard assignment using the next section, then collect and aggregate:

```sh
./gradlew generateSmartTestMapping \
  -Dstp.revision="$REVISION" -Dstp.shardId=local \
  -Dstp.executableAssignment=stp-run/shards/tests-local.json
cp build/stp/executable-fragment.json stp-run/shards/fragment-local.json
```

Inventory and mapped tests always execute in v3 mapping mode, including with the configuration cache.
Ordinary test builds retain their normal up-to-date/cache behavior.

## Quick start: Maven

Use a single-module JUnit Platform project for this example. Keep its existing test dependencies;
configure Surefire with a qualified version, such as `3.5.6`. Add the STP plugin and its finalization
binding to the consumer POM:

```xml
<plugin>
  <groupId>com.sap.oss.smart-test-picker</groupId>
  <artifactId>smart-test-picker-maven</artifactId>
  <version>0.3.0-SNAPSHOT</version>
  <executions>
    <execution>
      <id>stp-fragment</id>
      <phase>verify</phase>
      <goals><goal>generate-coverage-fragment</goal></goals>
    </execution>
  </executions>
</plugin>
```

Use this binding in the mapping profile rather than normal verification if you do not want ordinary
`verify` invocations to collect. The [Maven README](smart-test-picker-maven/README.md) covers reactors,
execution-qualified targets and Failsafe. With the plugin installed locally as above:

```sh
STP_VERSION=0.3.0-SNAPSHOT
STP_GOAL="com.sap.oss.smart-test-picker:smart-test-picker-maven:$STP_VERSION"
REVISION="$(git rev-parse HEAD)"
mkdir -p stp-run/shards
mvn test-compile "$STP_GOAL:generate-reactor-head-test-inventory" \
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.prHeadRevision="$REVISION" \
  -DsmartTestPicker.outputFile=stp-run/head-inventory.json
```

Create the assignment below, then map and aggregate at the same revision:

```sh
mvn "$STP_GOAL:prepare-reactor-executable-mapping" verify \
  "$STP_GOAL:aggregate-reactor-coverage-fragment" \
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.coverageCollector=ASM \
  -DsmartTestPicker.testsFile=stp-run/shards/tests-local.json \
  -DsmartTestPicker.revision="$REVISION" -DsmartTestPicker.shardId=local \
  -DsmartTestPicker.fragmentOutput=stp-run/shards/fragment-local.json \
  -DsmartTestPicker.evidenceOutput=stp-run/evidence.json
```

Preparation executes the lifecycle through test-compile. A following test/verify phase runs those
early phases again; tests run once. Plugins with early-phase side effects must tolerate repetition.

## One-shard assignment

Run this after either inventory command, before mapping. It copies the authoritative inventory,
without guessing identities. Python 3 is needed only for this small example, not by the plugins:

```sh
python3 - <<'PY'
import json
from pathlib import Path
inventory = json.loads(Path("stp-run/head-inventory.json").read_text())
assert inventory["version"] == 1
assert len({test.split("::", 1)[0] for test in inventory["tests"]}) == 1
assignment = dict(version=1, revision=inventory["revision"],
                  shardId="local", tests=inventory["tests"])
Path("stp-run/shards/tests-local.json").write_text(json.dumps(assignment) + "\n")
PY
```

For several shards, use a plan that partitions the inventory exactly and keeps a class's overloaded
methods together. Inventory and assignment formats are distinct from the coverage schema.

## Quick start: CLI join and selection

The CLI is a shaded executable artifact at
`com.sap.oss.smart-test-picker:smart-test-picker-cli:0.3.0-SNAPSHOT`.
Resolve it with your build tool, or build `:smart-test-picker-cli:shadowJar` from source.
Set `STP_CLI` to that JAR's path. It needs no extra runtime dependency classpath.

After either mapping example:

```sh
java -jar "$STP_CLI" join-fragments --dir stp-run/shards --expected-shards 1 \
  --output stp-run/coverage-map.json.gz
```

Joining supports **one execution target per invocation**. Different Gradle tasks or Maven
execution targets must not be mixed in this CLI command. Missing whole fragments, contradictory
assignments and incomplete fragments reject publication; an existing output is left untouched.
Paired-directory joining accounts assigned-but-unreported identities as `ASSIGNED_NOT_REPORTED`.
It skips dot-prefixed files with a log entry, but rejects other stray files.

For selection, keep the baseline map, check out the PR head and regenerate the appropriate inventory
there using the same build selection. Save it as `stp-run/head-inventory.json`. Then:

```sh
INTEGRATION_REVISION="$(git rev-parse main)"
PR_HEAD_REVISION="$(git rev-parse HEAD)"
PR_BASE_REVISION="$(git merge-base "$INTEGRATION_REVISION" "$PR_HEAD_REVISION")"
java -jar "$STP_CLI" select-tests --map stp-run/coverage-map.json.gz \
  --head-inventory stp-run/head-inventory.json --project-dir . \
  --integration-revision "$INTEGRATION_REVISION" \
  --pr-base-revision "$PR_BASE_REVISION" --pr-head-revision "$PR_HEAD_REVISION" \
  --output stp-run/selection.json
```

This writes a selection decision; it does not execute tests. Honor `FULL_SUITE`, `BASE_OUT_OF_DATE`
and `ERROR` instead of treating every response as a filtered test list.

## Safety and known limitations

Inventory follows the effective provider/Test task selection. An assignment may only narrow it.
Because provider method filters cannot distinguish parameter types, partial overload assignments
are rejected before tests. Disabled observations count as positive non-execution; absence alone does not.

Both ASM finalizers absorb missing assigned identities in an otherwise valid completed fragment as
`COLLECTION_FAILED`, with an audit and warning. Those identities remain mandatory while present at
head. Absorption does not repair invalid bindings, unsafe setup evidence or incomplete fragments.
A complete map proves accounting, not perfect causal attribution of asynchronous work.

Mapping requires supported single-JVM execution settings. In-JVM parallel tests and shared cached
web contexts are not generally supported. JaCoCo's process-global resets do not isolate concurrent
work. Gradle Kotlin JVM main outputs are supported; Kotlin Multiplatform scope is not. Maven provider
classpath/testClassesDirectory customizations and repeated early lifecycle phases remain limitations.
See [collector boundaries](docs/asm-coverage-mapping-current-state.md).

## Legacy workflows

The older `generateSmartReports generateTestCoverageJson`, Maven `generate-reports` /
`generate-coverage-map`, and CLI `generate-map` / `merge-maps` commands operate on the legacy
JaCoCo format. Logical schema-2 flows also remain available. They are not substitutes for a v3
inventory/assignment/join pipeline. See the module READMEs before mixing formats.

## Reference and development

- [Configuration reference](docs/configuration-reference.md): every goal, task, property, command and option.
- [Compatibility](docs/compatibility.md), [current development state](docs/current-development-state.md).
- [Gradle](smart-test-picker/README.md), [Maven](smart-test-picker-maven/README.md), [CLI](smart-test-picker-cli/README.md).
- [Core](smart-test-picker-core/README.md), [common](smart-test-picker-common/README.md), [agent](stp-agent/README.md).
- [Map model](docs/coverage-map-model.md), [v3 encoding](docs/v3-map-format.md), [join contract](docs/join-fragments-cli.md).
- [Artifact checks](docs/artifact-versioning.md), [historical records](docs/history/README.md), [changelog](CHANGELOG.md).

Build with `./gradlew build`; run suites with `./gradlew test --rerun-tasks`.
For contribution and security reporting, see [CONTRIBUTING](CONTRIBUTING.md) and the
[security policy](https://github.com/SAP/smart-test-picker/security/policy).

Copyright 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors.
[Apache-2.0 license](LICENSE). Third-party license texts and notices accompany shaded artifacts.
