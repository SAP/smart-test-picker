# smart-test-picker-core

JUnit 5 extension that enables per-test JaCoCo coverage collection.

## What It Does

Per-test coverage is collected via two complementary mechanisms:

1. **TestExecutionListener** (`JacocoPerTestListener`) -- registered via JUnit Platform ServiceLoader. Works on Gradle and Maven with JUnit Platform 1.9.x.
2. **Jupiter Extension** (`TestLifecycleExtension`) -- registered via JUnit Jupiter extension auto-detection. Works on all platforms including Maven Surefire 3.x with JUnit Platform 6.x.

Both mechanisms do the same thing:
1. Before each test: reuse the active JaCoCo runtime, set a unique session ID, and reset its probes
2. After each test (including failures): obtain standard JaCoCo exec bytes in memory with
   `IAgent.getExecutionData(true)` and append them to an STP-owned `session_*.exec` artifact
3. Optionally collect per-test execution metrics (duration, status)

A coordination flag prevents duplicate processing when both are active.

## Collection activation and selected collector

Both SPI components first read the test-JVM property
`smartTestPicker.collectionEnabled` when constructed. Absent, blank or `false`
means inactive: ordinary tests run without STP collection, even if core is a test
dependency and extension autodetection is enabled. Inactive components ignore
collector selection, including invalid selection values, without runtime access.

Only when activation is `true` do they read `smartTestPicker.coverageCollector`.
`ASM` disables JaCoCo components. `JACOCO`, absent or blank selection requires an
active JaCoCo runtime; invalid selection is rejected. Both decisions are made at
construction, before callbacks. A missing selected runtime fails closed with
`Active JaCoCo runtime is unavailable for STP mapping`.

Selection is fixed for the lifetime of each component; configure it before launcher
startup, not between test callbacks. This property selects collection components;
it does not attach an agent. `stp.exec.dir` remains only an output-directory
override, never an activation signal. See [the correction and evidence](../verification/maven-asm-03-fix/REPORT.md).

## Setup

### Gradle (with Smart Test Picker plugin)

For JaCoCo fallback, apply `jacoco` and explicitly select `JACOCO`. The STP plugin
adds the collector runtime and sets the exec directory for its mapping task.
The Gradle default remains ASM; both backends pass their selection to the test JVM.

```groovy
plugins {
    id 'jacoco'
    id 'com.sap.oss.smart-test-picker' version '0.3.0-SNAPSHOT'
}
smartTestPicker { coverageCollector = 'JACOCO' }
```

### Maven

Add core as a test dependency. It remains inactive during ordinary tests:

```xml
<dependency>
    <groupId>com.sap.oss.smart-test-picker</groupId>
    <artifactId>smart-test-picker-core</artifactId>
    <version>0.3.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

`prepare-reactor-executable-mapping` supplies activation for schema-v3 mapping.
For a manually configured JaCoCo mapping profile (including legacy schema-v2),
attach JaCoCo and use this Surefire/Failsafe configuration **only in that profile**:

```xml
<configuration>
    <systemPropertyVariables>
        <smartTestPicker.collectionEnabled>true</smartTestPicker.collectionEnabled>
        <smartTestPicker.coverageCollector>JACOCO</smartTestPicker.coverageCollector>
    </systemPropertyVariables>
    <properties>
        <configurationParameters>
            junit.jupiter.extensions.autodetection.enabled = true
        </configurationParameters>
    </properties>
</configuration>
```

### Gradle (without the plugin)

For a manually configured mapping task, add core, attach JaCoCo and explicitly
enable collection. Do not enable this on ordinary tests unless collection is intended:

```groovy
testRuntimeOnly 'com.sap.oss.smart-test-picker:smart-test-picker-core:0.3.0-SNAPSHOT'

tasks.named('test') {
    systemProperty 'smartTestPicker.collectionEnabled', 'true'
    systemProperty 'smartTestPicker.coverageCollector', 'JACOCO'
    systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true'
    systemProperty 'stp.exec.dir', "${buildDir}/jacoco/"
}
```

## Session ID Format

Each test gets a unique session ID: `SimpleClassName#methodName_<hash>` where the 7-char hex hash is derived from the fully qualified class name. This prevents collisions when test classes with the same simple name exist in different packages.

For parameterized tests, all invocations of the same test method share one session ID. Coverage data is appended (not overwritten) across invocations, producing the union of coverage from all parameter combinations.

## JaCoCo Agent Interaction

The extension communicates with the JaCoCo runtime agent via reflection:
- `org.jacoco.agent.rt.RT.getAgent()` -- obtains the single active agent instance
- `IAgent.getExecutionData(true)` -- snapshots and resets without depending on the agent `destfile`
- `setSessionId(String)` -- sets the session name for the current test
- `dump(boolean)` -- writes accumulated coverage data
- `reset()` -- clears coverage data between tests

This approach works with JaCoCo's on-the-fly instrumentation mode (the default for Gradle and Maven builds).

## Exec Directory Resolution

The directory where per-test `.exec` files are written is resolved in this order:
1. System property `stp.exec.dir` (set by the Gradle plugin automatically)
2. Auto-detect: `target/jacoco/` if `target/` directory exists (Maven convention)
3. Fallback: `build/jacoco/` (Gradle convention)

## Per-Test Metrics

When enabled via `-Dsmarttestpicker.metrics.enabled=true`, the extension records per-test timing and status to a JSON file. This data can be aggregated across multi-module builds using the `MergeTestMetricsMojo` in the Maven plugin.
