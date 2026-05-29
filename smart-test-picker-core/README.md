# smart-test-picker-core

JUnit 5 extension that enables per-test JaCoCo coverage collection.

## What It Does

`TestLifecycleExtension` intercepts the JUnit 5 test lifecycle to assign each test method its own JaCoCo session. This produces a separate `.exec` coverage file per test, which is the foundation for per-test coverage mapping.

The extension:
1. Before each test: sets a unique JaCoCo session ID via reflection on `org.jacoco.agent.rt.RT`
2. After each test: dumps the coverage data to a `.exec` file named after the test
3. Optionally collects per-test execution metrics (duration, memory usage)

## Auto-Registration

The extension is auto-registered via `META-INF/services/org.junit.jupiter.api.extension.Extension` and `junit-platform.properties`. No `@ExtendWith` annotation is needed in test classes.

To enable, add the core module as a test dependency:

```groovy
testRuntimeOnly 'io.github.ljubisap:smart-test-picker-core:0.1.0'
```

## JaCoCo Agent Interaction

The extension communicates with the JaCoCo runtime agent via reflection:
- `org.jacoco.agent.rt.RT.getAgent()` -- obtains the agent instance
- `setSessionId(String)` -- sets the session name for the current test
- `dump(boolean)` -- writes accumulated coverage data
- `reset()` -- clears coverage data between tests

This approach works with JaCoCo's on-the-fly instrumentation mode (the default for Gradle and Maven builds).

## Per-Test Metrics

When enabled, the extension records per-test timing and memory metrics to a JSON file alongside the `.exec` files. This data can be aggregated across multi-module builds using the `MergeTestMetricsMojo` in the Maven plugin.
