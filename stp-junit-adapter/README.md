<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# stp-junit-adapter (experimental)

This module converts JUnit Platform leaf-test execution callbacks into explicit
`stp-runtime` contexts. `TestIdentifier.isTest()` is the boundary: engines,
classes, nested containers, parameterized/repeated containers, and dynamic-test
factories never open contexts.

The Platform unique ID is the canonical test ID. Consequently parameterized,
repeated, dynamic, and nested leaf tests remain distinct even when their readable
class or method metadata is shared or absent. `MethodSource`, when present,
provides `testClass` and `testMethod`; neither is required.

Programmatic use supplies a `RuntimeContextService` to
`StpRuntimeTestExecutionListener`. Service-loader discovery is declared through
`META-INF/services/org.junit.platform.launcher.TestExecutionListener`. Because
SPI construction requires a no-argument constructor. Runtime ownership and the
single registry state live in `stp-runtime` as `RuntimeContextRegistry`; the
listener reads it and never creates a fallback service. The obsolete adapter-
owned registry facade was removed during stabilization; there is exactly one
registry API and it belongs to `stp-runtime`. Programmatic listener construction
is unchanged.

The supported service-loader startup order is strict:

```text
STP agent premain installs RuntimeContextRegistry
→ JUnit Platform constructs the service-loaded listener
```

An isolated-JVM agent test proves this ordering. The no-argument constructor
captures the currently installed service. If it is constructed before premain
or without an installed runtime, that listener instance is permanently inert;
later registry installation does not activate it. This intentional no-op is not
a lazy fallback and avoids creating a second runtime.
Existing Smart Test Picker modules do not depend on this adapter, so it does not
enable or replace their JaCoCo listeners.

Integration tests confirm the callback interval used by JUnit Jupiter 5.9.3:
the listener's leaf `executionStarted` callback occurs before `@BeforeEach`, and
leaf `executionFinished` occurs after `@AfterEach`. Therefore same-thread events
from both callbacks are attributed to that leaf. `@BeforeAll`, `@AfterAll`,
discovery, engine startup, and container callbacks occur outside a leaf context
and are recorded as unattributed/late evidence rather than dependencies.

Attribution is intentionally `ThreadLocal` and cleared in a `finally` block.
There is no propagation to executors, random-port HTTP server threads, reactive
pipelines, virtual-thread handoffs, or other asynchronous work. A finished test
cannot be reopened by the same listener; events after closure are recorded as
late rather than silently assigned to a following test.

The adapter stores only execution status plus failure class name and message. It
does not retain exceptions or serialize stack traces. Compatibility and JSON
schema remain experimental.
