<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP Karate Client — integration guide

The STP Karate Client adds Remote STP identity to outgoing Karate HTTP requests using standard OpenTelemetry W3C Baggage. Install its request hook once on each Karate Runner; feature files do not need STP-specific steps, headers, or identity construction.

## Requirements and dependency

Prerequisites are **Java 17**, **Karate 1.5.1**, and the **STP Karate Client** library. This is the supported and tested combination, with OpenTelemetry API 1.66.0. The client is compiled with `--release 17`. Karate 2.x is not supported by this integration. No changes to existing `.feature` files are required.

The test project supplies Karate. The client compiles against Karate 1.5.1 using `compileOnlyApi`, so its runtime dependencies do not pull in or upgrade Karate. Keep the existing Karate 1.5.1 dependencies in your Maven or Gradle test project.

In this repository, add the module directly to the test runtime:

```groovy
dependencies {
    testImplementation project(':stp-karate-client')
}
```

For a separate Gradle project, the current supported development setup is a Gradle composite build. In `settings.gradle`:

```groovy
includeBuild('/path/to/smart-test-picker')
```

Then declare the included module's coordinates in `build.gradle`:

```groovy
dependencies {
    testImplementation 'com.sap.oss.smart-test-picker:stp-karate-client:0.3.0-SNAPSHOT'
}
```

Gradle substitutes the dependency with the included build. The module does not currently configure a standalone Maven publication, so this guide does not assume that the artifact is available from Maven Local or a remote repository.

## Configure the Java runner once

Register the hook on the existing Karate 1.x Runner:

```java
import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.sap.oss.smarttestpicker.karate.StpKarateHook;

Results results = Runner.path("classpath:api_ctof_order_karate/spec")
    .hook(StpKarateHook.fromSystemProperties())
    .outputJunitXml(true)
    .parallel(1);
```

Keep your existing paths, tags, parallelism and result assertions. The hook works with Maven or Gradle; installation is in the Java runner, not the build tool. If setup, tests and cleanup each use separate `Runner.path(...)` calls, attach the hook to every runner whose HTTP traffic should be attributed. Create a new hook for every `Runner.parallel(...)` execution. That one hook safely serves all parallel scenarios in its run; reusing it for another run fails explicitly.

For explicit Java configuration, use `new StpKarateHook(suiteId, Path.of("target/stp"))` or `new StpKarateHook(suiteId, outputDirectory, outputPrefix)` instead. No per-test identity or annotations are needed.

Karate invokes `RuntimeHook.beforeHttpCall` after applying configured/feature headers and before each Karate HTTP attempt, including every `retry until` attempt. The hook reads the current scenario metadata, generates fresh RequestID Baggage, and installs it on a detached copy of that attempt's headers. It never stores generated headers back into Karate's reusable request builder.

Your existing `karate-config.js` authentication/header configuration and `.feature` files stay unchanged. **Remove the previous STP `configure headers` callback** when migrating to this hook; do not install both integrations. The old `StpKarateClient.headers(HttpRequest, ...)` API is replaced by this Karate 1.x hook.

## Required runtime configuration

When using `StpKarateHook.fromSystemProperties()`, configure:

| Property | Requirement |
| --- | --- |
| `-Dstp.testSuiteId=<suite-id>` | Required unless `STP_TEST_SUITE_ID` supplies the suite |
| `-Dstp.outputDirectory=<directory>` | Required; directory for the client execution manifest |
| `-Dstp.outputPrefix=<prefix>` | Optional; default `stp-karate` |

`STP_TEST_SUITE_ID` is an alternative only for SuiteID, not for the output directory.

Set one explicit suite/group ID for the run. It may be reused across multiple executions. Use the JVM property (Maven example: `mvn test -Dstp.testSuiteId=commerce-rest-regression -Dstp.outputDirectory=target/stp`), or with Gradle:

```bash
./gradlew test -Dstp.testSuiteId=commerce-rest-regression -Dstp.outputDirectory=build/stp
```

When Gradle runs tests in a separate worker JVM, forward the Gradle JVM property to the test task in `build.gradle`:

```groovy
tasks.withType(Test).configureEach {
    ['stp.testSuiteId', 'stp.outputDirectory', 'stp.outputPrefix'].each { key ->
        def value = providers.systemProperty(key).orNull
        if (value != null) systemProperty key, value
    }
}
```

or the environment variable:

```bash
STP_TEST_SUITE_ID=commerce-rest-regression ./gradlew test -Dstp.outputDirectory=build/stp
```

If both are set to the same value, the client accepts them. If they differ, the value is blank, or neither is set, configuration fails clearly. IDs must be nonblank, no longer than 256 characters, and contain no ISO control characters.

## Identity sent on each request

The client injects one W3C `baggage` header with these entries:

```text
stp.test.suite.id=<suite ID>
stp.test.id=<test ID>
stp.request.id=<request ID>
```

It does not use legacy `X-STP-*` headers. TestSuiteID is the run-level value supplied above. TestID and RequestID are derived/generated centrally by the client.

### SuiteID and RunID

**SuiteID** identifies a logical suite/group supplied by the caller, for example `commerce-checkout-e2e`. It is not a unique run identifier: the same suite can execute repeatedly.

**RunID** is a UUID generated once for each new hook/Runner execution. It distinguishes client manifest files for repeated suite runs. It is stored locally and is **not propagated to the server**.

### TestID

TestID is `karate-` followed by the lowercase hexadecimal SHA-256 of this UTF-8 input:

```text
feature.prefixedPath + "\n" + scenario.sectionIndex + "\n" + scenario.line + "\n" + scenario.exampleIndex
```

This distinguishes feature resources, Scenarios, and Scenario Outline example executions. The same feature and scenario metadata produces the same TestID under different SuiteIDs, so a test can be compared across suites. The ID is stable only while those metadata fields remain unchanged; moving or renaming a feature, or changing the relevant scenario positions, can change it.

### RequestID

The hook generates a new random UUID on every Karate HTTP attempt. Multiple requests from one Scenario and retries of the same request share SuiteID and TestID but receive different RequestIDs. Repeating the same Scenario also produces new RequestIDs. For example, responses `503 → 503 → 200` produce three separate request identities.

RequestID is exclusively client-generated. A preexisting `stp.request.id` in outgoing Baggage is rejected before sending, including an otherwise valid UUID; it is never reused or silently overwritten. This detects manually supplied IDs, the old STP header callback, and duplicate hook registration. Existing SuiteID or TestID Baggage entries are accepted only when they match the derived values. Conflicts and duplicate W3C Baggage header keys are rejected. Unrelated Baggage and application headers are preserved.

The supported retry boundary is Karate `retry until`. HTTP-client-internal redirects or transparent transport retries do not necessarily invoke Karate hooks and are not claimed as independently identified attempts here.

The client also injects W3C Trace Context when the current OpenTelemetry context contains an active span. STP test attribution itself uses the three Baggage entries above.

### Correlation with server observations

```text
Karate Scenario
    ↓ TestID
HTTP attempt
    ↓ RequestID
W3C Baggage (SuiteID + TestID + RequestID)
    ↓
Remote STP server observation
```

The correlation key is **SuiteID + TestID + RequestID**. The exact IDs in the client manifest are the IDs injected into Baggage. Match manifest `source.suiteId` to server `testSuiteId`, and request `testId` / `requestId` to the corresponding server fields. RunID and filenames are not part of this key. An attempted request that never reaches the server can exist only in the client manifest.

## Parallel execution

The client does not keep a shared mutable “current test” value. It derives TestID from `ScenarioRuntime` at the request hook and generates RequestID for that attempt. The module's parallel fixture verifies that concurrent Karate Scenarios keep their identities separate.

## Client execution manifest

Every hook/run writes one local manifest when Karate invokes `afterSuite`, after the scenarios have completed. This includes runs with failed assertions, exhausted retries and runs with no HTTP requests (`requests: []`). Records describe attempted sends, not successful responses. A network failure after injection may therefore have a manifest entry and no corresponding server observation.

Configure through Java constructor arguments `outputDirectory` and optional `outputPrefix`, or the system properties used by `fromSystemProperties()`:

| Setting | Required | Default |
| --- | --- | --- |
| `-Dstp.outputDirectory=target/stp` | Yes | None |
| `-Dstp.outputPrefix=stp-karate` | No | `stp-karate` |

Relative directories resolve against the test JVM working directory. Missing parent directories are created. Prefixes must start with an ASCII letter/digit, contain only ASCII letters/digits, `.`, `_`, `-`, and be at most 64 characters. Blank directories/prefixes fail before execution. The prefix is a filename component, not a path.

The filename is:

```text
<outputPrefix>-<sanitizedSuiteId>-<runId>.json
```

For example:

```text
stp-karate-commerce-checkout-e2e-4b614f6c-42cf-4d97-92c7-8b2ed54b5adc.json
```

Safe SuiteIDs up to 64 characters stay readable. Unsafe characters are replaced with `_`; changed/truncated names receive a short hash suffix so different SuiteIDs that sanitize similarly remain distinguishable. The original SuiteID is stored inside the JSON. `runId` is a random UUID generated once when the hook is constructed. Repeated executions of the same suite have different run IDs and files.

**SuiteID identifies the logical suite/group; RunID identifies this client execution.** RunID is local manifest metadata only: it is not added to W3C Baggage and does not replace SuiteID or RequestID. The future client/server correlation key remains `(source.suiteId, request.testId, request.requestId)`; consumers must read JSON rather than parse filenames.

```json
{
  "schemaVersion": 1,
  "source": {
    "suiteId": "commerce-checkout-e2e",
    "runId": "6d8f9b04-4f80-42f3-9178-2ab0f37c0b19"
  },
  "requests": [
    {
      "testId": "karate-<existing SHA-256 identity>",
      "requestId": "45d24458-2b39-4e99-850c-9a1a6e985eff",
      "featurePath": "classpath:features/orders.feature",
      "sectionIndex": 1,
      "scenarioLine": 42,
      "exampleIndex": -1,
      "httpMethod": "POST",
      "requestUri": "https://application.example/orders"
    }
  ]
}
```

`requestUri` is a sanitized **absolute HTTP(S) URL**, including scheme, host, optional port and path; it is not just `/orders`. The TestID above is illustrative. Ordinary scenarios use `exampleIndex: -1`; Outline executions retain the runtime's example index.

The hook records the same generated identity used for outgoing Baggage. Entries are held concurrently in memory, then sorted by TestID/RequestID for the final write. HTTP calls do not write the whole file individually. `manifestPath()` and `runId()` expose the chosen artifact location and execution ID.

The writer serializes to a unique sibling temp file, closes and forces it, then atomically publishes the complete file without replacement. It uses an atomic hard-link creation followed by temp-name deletion: Java `ATOMIC_MOVE` alone may overwrite an existing target even without `REPLACE_EXISTING`. An exact filename collision fails, including one introduced after startup. Filesystems without hard-link support fail visibly; there is no unsafe overwrite fallback. Write/serialization failures include the output path and never publish partial JSON. Karate 1.5.1 propagates finalization failures from `afterSuite` to the caller. A hard JVM crash before finalization may lose the in-memory manifest; no shutdown recovery or incremental durability is claimed.

### Privacy

Only the fields shown above are recorded. No request/response bodies, authentication headers, cookies, arbitrary HTTP headers, or response content are captured. URI user-info, all query parameters, fragments, and semicolon path parameters (including URL session IDs) are removed. Retained scheme/host/path can still identify application resources, so keep secrets out of URL path segments and feature paths and apply normal access controls to the artifact. Expanded scenario names are deliberately omitted because Outline values may contain sensitive data. These omissions affect metadata only, never the outgoing request or its STP identities.

The manifest does not capture credentials or authentication tokens from headers, cookies, request/response bodies or URL query strings. It cannot recognize secrets embedded directly in a URI path or feature path. Treat these paths and the manifest as test evidence that may contain business-sensitive identifiers.

### Current durability limitation

The execution manifest is finalized from memory in Karate `afterSuite`. A hard JVM/process crash before `afterSuite` can lose the current run's client manifest.

## Server-side use

The target application needs the OpenTelemetry Java agent and the STP Remote Agent, with the OpenTelemetry agent attached first. The OTel agent extracts W3C Baggage into the active OpenTelemetry context; the Remote STP Agent reads the STP identity there and records application method entries. The server-side agent has no Karate dependency. See the [Remote STP Agent setup](../stp-remote-agent/README.md) for server configuration and output details.

## Complete minimal Gradle example

This example uses a separate project, a JUnit Jupiter entry point and existing Karate features under `src/test/resources/features`. Keep your application's existing `karate-config.js` and its URL/authentication configuration. The test JVM needs no Java agent for this client integration.

`settings.gradle`:

```groovy
rootProject.name = 'commerce-rest-e2e'
includeBuild('/path/to/smart-test-picker')
```

`build.gradle`:

```groovy
plugins { id 'java' }

repositories { mavenCentral() }

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    testImplementation 'io.karatelabs:karate-core:1.5.1'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
    testImplementation 'com.sap.oss.smart-test-picker:stp-karate-client:0.3.0-SNAPSHOT'
}

tasks.withType(Test).configureEach {
    useJUnitPlatform()
    ['stp.testSuiteId', 'stp.outputDirectory', 'stp.outputPrefix'].each { key ->
        def value = providers.systemProperty(key).orNull
        if (value != null) systemProperty key, value
    }
}
```

`src/test/java/example/ApiTest.java`:

```java
package example;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.sap.oss.smarttestpicker.karate.StpKarateHook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiTest {
    @Test
    void runApiSuite() {
        Results results = Runner.path("classpath:features")
            .hook(StpKarateHook.fromSystemProperties())
            .outputJunitXml(true)
            .parallel(2);
        assertEquals(0, results.getFailCount(), results.getErrorMessages());
    }
}
```

Run with the project's Gradle wrapper:

```bash
./gradlew test \
  -Dstp.testSuiteId=commerce-rest-e2e \
  -Dstp.outputDirectory=build/stp
```

On completion, expect one file such as:

```text
build/stp/stp-karate-commerce-rest-e2e-4b614f6c-42cf-4d97-92c7-8b2ed54b5adc.json
```

The UUID is generated, not configured. For a deliberate repeated execution, add `--rerun-tasks` if Gradle would otherwise reuse an up-to-date/cached test result; a new manifest requires the Runner to actually execute. Maven projects attach the same Java hook and must put the built client and its runtime dependencies on the test classpath; this guide does not imply a published Maven artifact exists.

## Troubleshooting

| Symptom | Behavior / action |
| --- | --- |
| Missing or blank SuiteID | Hook initialization fails before attributed HTTP requests are sent. Set `stp.testSuiteId` or `STP_TEST_SUITE_ID`. |
| Missing `outputDirectory` | Initialization fails. Set `stp.outputDirectory` and forward it to the Gradle test worker. |
| Conflicting SuiteID property/environment | Initialization fails. Remove one source or give both exactly the same value. |
| Exact generated manifest filename already exists | Initialization/finalization fails rather than overwriting or merging the file. Use a fresh hook for each run; preserve the existing artifact. |
| Manifest write fails | The error identifies the output path. Check directory access and filesystem hard-link support; the run must not be treated as successfully persisted. |
| No new manifest, Gradle says `UP-TO-DATE` / `FROM-CACHE` | The Runner did not execute. Rerun the task when a new test execution is intended. |
| Existing RequestID rejected | Remove the old STP `configure headers` callback or duplicate hook registration. Do not manually supply STP RequestIDs. |
| Client manifest exists but no server observations | Check that OTel is attached **before** STP Remote Agent, server `includes` matches application packages, and the outgoing `baggage` header reaches the server unchanged. Check the server's checkpoint/finalization timing or enabled debug snapshot as described in its README. |

No proprietary `X-STP-*` header configuration is needed to fix missing server attribution.

## Validation

Run the client tests from the repository root:

```bash
./gradlew :stp-karate-client:test --no-daemon --console=plain
```

The tests run native Karate features against a local HTTP fixture and verify multiple requests within one Scenario, different SuiteIDs, parallel Scenarios, different feature paths, Scenario Outline rows, existing Baggage validation, header preservation, successful/exhausted retries and concurrent retries. Validation uses JDK 17 with Karate 1.5.1. Manifest tests additionally cover exact wire-ID matching, empty/failed runs, concurrent recording, filename collisions, URI sanitization and failed final writes.
