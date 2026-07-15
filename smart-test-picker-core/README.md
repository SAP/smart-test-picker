# smart-test-picker-core

JUnit 5 extension that enables per-test JaCoCo coverage collection.

## What It Does

Per-test coverage is collected via two complementary mechanisms:

1. **TestExecutionListener** (`JacocoPerTestListener`) -- registered via JUnit Platform ServiceLoader. Works on Gradle and Maven with JUnit Platform 1.9.x.
2. **Jupiter Extension** (`TestLifecycleExtension`) -- registered via JUnit Jupiter extension auto-detection. Works on all platforms including Maven Surefire 3.x with JUnit Platform 6.x.

Both mechanisms do the same thing:
1. Before each test: set a unique JaCoCo session ID via reflection on `org.jacoco.agent.rt.RT`
2. After each test: dump coverage data and save to a per-test `.exec` file
3. Optionally collect per-test execution metrics (duration, status)

A coordination flag prevents duplicate processing when both are active.

## Setup

### Gradle (with Smart Test Picker plugin)

No additional configuration needed. The plugin applies `jacoco`, adds the core dependency, and sets the exec directory automatically:

```groovy
plugins {
    id 'jacoco'
    id 'com.sap.oss.smart-test-picker' version '0.1.0'
}
```

### Maven

Add the core module as a test dependency and enable Jupiter extension auto-detection:

```xml
<dependency>
    <groupId>com.sap.oss.smart-test-picker</groupId>
    <artifactId>smart-test-picker-core</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

In your surefire configuration:

```xml
<configuration>
    <properties>
        <configurationParameters>
            junit.jupiter.extensions.autodetection.enabled = true
        </configurationParameters>
    </properties>
</configuration>
```

### Gradle (without the plugin)

Add the dependency and enable auto-detection:

```groovy
testRuntimeOnly 'com.sap.oss.smart-test-picker:smart-test-picker-core:0.1.0'

tasks.named('test') {
    systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true'
    systemProperty 'stp.exec.dir', "${buildDir}/jacoco/"
}
```

## Session ID Format

Each test gets a unique session ID: `SimpleClassName#methodName_<hash>` where the 7-char hex hash is derived from the fully qualified class name. This prevents collisions when test classes with the same simple name exist in different packages.

For parameterized tests, all invocations of the same test method share one session ID. Coverage data is appended (not overwritten) across invocations, producing the union of coverage from all parameter combinations.

## JaCoCo Agent Interaction

The extension communicates with the JaCoCo runtime agent via reflection:
- `org.jacoco.agent.rt.RT.getAgent()` -- obtains the agent instance
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
