<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# stp-agent (experimental)

This module packages the experimental Java agent for the runtime-dependency
spike. Its default remains a no-op shell. With explicit method-entry mode it can
instrument only configured application packages, assign stable method IDs, and
call `RuntimeHooks.methodHit(long)` at ordinary method entry. Task 6 validated
this mode against the pinned Spring PetClinic experiment tests; it remains
spike code and is not connected to Smart Test Picker selection behavior.

## Arguments

Agent key/value arguments are separated by semicolons; comma is reserved for
prefix lists:

```text
output=target/stp-agent.json;includes=org.example.,com.acme.;excludes=org.example.generated.;runId=run-1;debug=false;instrumentation=off
```

Supported keys are `output`, `includes`, `excludes`, `runId`, `debug`, and
`instrumentation`. Instrumentation accepts `on` or `off` and defaults to `off`.
Unknown, duplicate, empty, and malformed values fail agent startup explicitly.
The defaults are:

- output: `stp-agent-output.json`
- includes: `org.springframework.samples.petclinic.`
- run ID: `run-1`
- debug: `false`
- instrumentation: `off`

User exclusions are additive and override includes. Mandatory exclusions cover JDK namespaces,
JUnit, Spring, Hibernate, Mockito, Byte Buddy, JaCoCo, and Smart Test Picker's
own packages. The specific default PetClinic include is intentionally allowed
through the broader mandatory `org.springframework.` framework exclusion; all
other Spring classes remain excluded. Diagnostics mention only
known argument names, run ID, and error type; system properties and arbitrary
argument values are not dumped.

## Packaging and execution

Build the single runnable JAR with:

```bash
./gradlew :stp-agent:agentJar
```

The result is `stp-agent/build/libs/stp-agent-experimental.jar`. It contains the
agent, `stp-runtime`, and shaded ASM 9.8 classes. Shadow 8.3.6 relocates ASM from
`org.objectweb.asm` to `com.sap.oss.smarttestpicker.internal.asm`; no original
ASM package or ASM type in a public API is present. The manifest contract is:

```text
Premain-Class: com.sap.oss.smarttestpicker.agent.StpAgent
Can-Redefine-Classes: false
Can-Retransform-Classes: false
```

Run it with `-javaagent:/path/stp-agent-experimental.jar=<arguments>`. The agent
calls `Instrumentation.addTransformer(transformer, false)` and never requests
retransformation.

## Runtime visibility

The spike uses the system/application classloader model. The JVM loads the agent
JAR's premain class through the system loader; the same loader can resolve the
bundled `stp-runtime` classes. A dedicated isolated-JVM test verifies that an
application class can resolve `TestIdentity` when its own classpath contains
only the fixture classes. It also verifies that application code sees the
agent-installed `RuntimeContextRegistry` service. Maven/Surefire PetClinic tests
use this same loader relationship. A Spring Boot launched child classloader
delegates to its parent, so this remains the simplest candidate for that
packaging as well.

Nothing is appended to the bootstrap classloader. Bootstrap and framework
classes are excluded, so Task 4 has no need for bootstrap-visible hooks and no
split package. If a later task instruments bootstrap-defined classes, that would
require a separate, JDK-only bootstrap hook JAR and a new explicit decision.

## Method-entry mode

`instrumentation=on` applies only after include/exclude filtering. Interfaces,
annotation types, abstract/native methods, module descriptors, constructors,
class initializers, framework classes, and STP's own packages are skipped. Each
selected method receives exactly two entry instructions: a constant 64-bit ID
and a static call to `RuntimeHooks.methodHit(J)V`. ASM `COMPUTE_MAXS` recalculates
the operand-stack maximum; existing stack-map frames are retained. No class
loading or hierarchy lookup is performed by the transformer.

Canonical input is `binaryClassName#methodName(JVM descriptor)`. Method IDs use
version `fnv1a64-v1`: standard 64-bit FNV-1a, seed/offset basis
`0xcbf29ce484222325`, prime `0x100000001b3`, and UTF-8 canonical-key bytes.
The catalog retains full keys. If one ID maps to two distinct keys, both remain
in deterministic order, `methodIdCollisions` increments, an agent error is
emitted, and runtime hits for that ambiguous ID remain unattributed rather than
being silently merged.

Idempotence uses a private static final synthetic boolean field named
`$stp$instrumented$v1`. A class containing the marker is returned unchanged and
increments `alreadyInstrumentedClasses`; a second hook is never injected.

`RuntimeHooks` performs no allocation on the installed fast path beyond the
collector's counting needs, captures no values, timestamps, reflection data, or
stack trace, and catches every collector failure. This observation-boundary
policy deliberately includes serious JVM errors: losing evidence is preferable
to changing instrumented application behavior. Without an active JUnit
context, method hits are recorded with `NO_ACTIVE_TEST`. The bundled listener's
no-argument constructor obtains the agent-owned `RuntimeContextService` from
the framework-neutral `RuntimeContextRegistry` in `stp-runtime`, so the listener
and `RuntimeHooks` use one aggregator. The agent installs it during `premain`
before transformer registration. Initialization failure rolls back registry and
hook state; normal shutdown writes final output before closing both scoped
registrations. No JUnit-owned registry or fallback service remains.

PetClinic test classes are excluded by their `target/test-classes` code source.
Generated proxy-like names containing `$$`, Mockito, Byte Buddy, Hibernate
proxy, or Hibernate instantiator markers are also excluded. These are narrow
spike rules, not a general framework integration policy.

## Shutdown output and coexistence

At normal JVM shutdown the agent writes `agent-shell-1` JSON containing the run
and PID-based JVM IDs, normalized configuration, thread-safe counters, sorted
agent errors, `classesTransformed: 0`, and `bytecodeModified: false`. It records
only counts for classloader and protection-domain presence. It never records
loader objects, domains, class bytes, timestamps, arbitrary properties, or
stack traces. Method-entry mode additionally writes the sorted method catalog,
per-ID hit counts, unattributed runtime events, transformation counts, skipped
methods, idempotence count, and collision count.

The no-op mode can coexist with JaCoCo and Mockito/Byte Buddy because it
does not alter bytes and is registered without retransformation support. Task 6
also exercised method-entry mode with both JaCoCo-before-STP and
STP-before-JaCoCo ordering. A bad output path is
rejected before application main; a shutdown write failure is reported clearly
to stderr without pretending that output succeeded. The failed output file
cannot contain the error discovered while writing that same file; the in-memory
agent error list is updated for diagnostics, but stderr is the only reliable
notification on that failure path.

This module has no publishing configuration and no integration with current
Smart Test Picker selection or existing JaCoCo listeners.
