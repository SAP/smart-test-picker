<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP Karate Client — integration guide

The STP Karate Client adds Remote STP identity to outgoing Karate HTTP requests using standard OpenTelemetry W3C Baggage. Install its request hook once on each Karate Runner; feature files do not need STP-specific steps, headers, or identity construction.

## Requirements and dependency

The supported and tested combination is **Java 17 + Karate 1.5.1**, with OpenTelemetry API 1.66.0. The client is compiled with `--release 17`. Karate 2.x is not supported by this integration.

The test project supplies Karate. The client compiles against Karate 1.5.1 using `compileOnlyApi`, so its runtime dependencies do not pull in or upgrade Karate. Keep the existing Karate 1.5.1 dependencies in your Maven or Gradle test project.

In this repository, add the module directly to the test runtime:

```groovy
testImplementation project(':stp-karate-client')
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

Keep your existing paths, tags, parallelism and result assertions. The hook works with Maven or Gradle; installation is in the Java runner, not the build tool. If setup, tests and cleanup each use separate `Runner.path(...)` calls, attach the hook to every runner whose HTTP traffic should be attributed. A hook can be reused across parallel scenarios: its only stored identity value is the immutable SuiteID.

For a suite identity already resolved by run-level Java configuration, use `new StpKarateHook(suiteId)` instead. No per-test identity or annotations are needed.

Karate invokes `RuntimeHook.beforeHttpCall` after applying configured/feature headers and before each Karate HTTP attempt, including every `retry until` attempt. The hook reads the current scenario metadata, generates fresh RequestID Baggage, and installs it on a detached copy of that attempt's headers. It never stores generated headers back into Karate's reusable request builder.

Your existing `karate-config.js` authentication/header configuration and `.feature` files stay unchanged. **Remove the previous STP `configure headers` callback** when migrating to this hook; do not install both integrations. The old `StpKarateClient.headers(HttpRequest, ...)` API is replaced by this Karate 1.x hook.

## Provide TestSuiteID

Set one explicit suite/group ID for the run. It may be reused across multiple executions. Use the JVM property (Maven example: `mvn test -Dstp.testSuiteId=commerce-rest-regression`), or with Gradle:

```bash
./gradlew test -Dstp.testSuiteId=commerce-rest-regression
```

When Gradle runs tests in a separate worker JVM, forward the Gradle JVM property to the test task in `build.gradle`:

```groovy
tasks.withType(Test).configureEach {
    def suiteId = providers.systemProperty('stp.testSuiteId').orNull
    if (suiteId != null) systemProperty 'stp.testSuiteId', suiteId
}
```

or the environment variable:

```bash
STP_TEST_SUITE_ID=commerce-rest-regression ./gradlew test
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

## Parallel execution

The client does not keep a shared mutable “current test” value. It derives TestID from `ScenarioRuntime` at the request hook and generates RequestID for that attempt. The module's parallel fixture verifies that concurrent Karate Scenarios keep their identities separate.

## Server-side use

The target application needs the OpenTelemetry Java agent and the STP Remote Agent, with the OpenTelemetry agent attached first. The OTel agent extracts W3C Baggage into the active OpenTelemetry context; the Remote STP Agent reads the STP identity there and records application method entries. The server-side agent has no Karate dependency. See the [Remote STP Agent setup](../stp-remote-agent/README.md) for server configuration and output details.

## Validation

Run the client tests from the repository root:

```bash
./gradlew :stp-karate-client:test --no-daemon --console=plain
```

The tests run native Karate features against a local HTTP fixture and verify multiple requests within one Scenario, different SuiteIDs, parallel Scenarios, different feature paths, Scenario Outline rows, existing Baggage validation, header preservation, successful/exhausted retries and concurrent retries. All tests run on JDK 17 with Karate 1.5.1.
