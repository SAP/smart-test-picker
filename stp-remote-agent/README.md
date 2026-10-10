<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# Remote STP Agent — server-side setup

The Remote STP Agent records application methods executed for an HTTP test request. Test identity travels in OpenTelemetry W3C Baggage, is read from the active OpenTelemetry `Context`, and remains explicit in the resulting STP observation fragment.

The agent does not depend on JUnit, Karate, Spring, or another test framework. The test client is responsible for sending the STP Baggage values.

## Requirements

Build the standalone agent and copy of the OpenTelemetry Java agent from the repository root:

```bash
./gradlew :stp-remote-agent:remoteAgentJar :stp-remote-agent:copyOpenTelemetryJavaAgent
```

This produces:

```text
stp-remote-agent/build/libs/stp-remote-agent.jar
stp-remote-agent/build/otel-agent/opentelemetry-javaagent.jar
```

Attach both agents to the server JVM, with OpenTelemetry first:

```bash
java \
  -javaagent:/opt/otel/opentelemetry-javaagent.jar \
  '-javaagent:/opt/stp/stp-remote-agent.jar=output=/var/stp/observations.json;includes=com.sap.commerce.;serviceId=commerce;revision=abc123' \
  -jar application.jar
```

The OpenTelemetry Java agent must be attached before STP so that its instrumentation extracts W3C Baggage into the current OpenTelemetry context. The STP agent checks that the OpenTelemetry Context API is available at startup.

No controller, Servlet filter, Spring MVC, or Spring Security changes are required in the server application. Configure `includes` for the application classes you want to record, and configure the REST test client to send the STP Baggage entries described below.

## Agent parameters

Parameters are separated with semicolons. Quote the complete `-javaagent` argument in shell commands so the shell does not treat semicolons as command separators.

| Parameter | Required | Meaning |
| --- | --- | --- |
| `output=<path>` | Yes | One local Remote STP fragment file owned by this JVM. Relative paths resolve against the JVM working directory and are normalized. |
| `includes=<prefix>[,<prefix>...]` | Yes | Comma-separated Java class/package-name prefixes to instrument. |
| `excludes=<prefix>[,<prefix>...]` | No | Prefixes excluded from instrumentation, even when they match an include. |
| `serviceId=<id>` | Yes* | Logical application/service that produced this fragment. |
| `instanceId=<id>` | No | Explicit identity for this application instance. |
| `instanceIdEnv=<name>` | No | Environment variable to use for the instance fallback; default is `STP_INSTANCE_ID`. |
| `revision=<id>` | Yes* | Build or source revision running in this JVM. |
| `flushIntervalSeconds=<N>` | No | Periodic checkpoint interval in seconds; default `60`, minimum `1`. |
| `debugPort=<port>` | No | Starts the read-only debug HTTP server on loopback; valid ports are `1`–`65535`. |

* `serviceId` and `revision` can be set either as agent parameters or as environment variables `STP_SERVICE_ID` and `STP_REVISION`. If neither source provides a nonblank, valid value, startup fails. Values must be no longer than 256 characters and contain no ISO control characters.

### Package selection

Use the narrowest prefixes that cover the application code of interest. These are prefixes, not regular expressions:

```text
includes=com.sap.cx.,de.hybris.platform.myextension.
excludes=com.sap.cx.generated.
```

The agent records instrumented method entries from matching classes. It does not instrument the whole JVM unless a broad prefix is explicitly configured. See the [context propagation support matrix](../docs/remote-otel-context-propagation.md) for tested async boundaries and measured limitations.

### Producer identity

Each output fragment carries a `source` object with `serviceId`, `instanceId`, and `revision`. Instance selection follows this order:

1. explicit `instanceId=`;
2. the environment variable named by `instanceIdEnv=` (default `STP_INSTANCE_ID`);
3. local hostname;
4. generated `jvm-<UUID>` if hostname lookup is unavailable.

The hostname fallback may identify multiple JVMs on the same host with the same value. Configure an explicit `instanceId` or a unique `STP_INSTANCE_ID` for deployments where multiple application instances can share a host. STP does not query Kubernetes or cloud metadata services.

Example using environment values:

```bash
STP_SERVICE_ID=commerce STP_INSTANCE_ID=commerce-pod-17 STP_REVISION=abc123 \
  java -javaagent:/opt/otel/opentelemetry-javaagent.jar \
  '-javaagent:/opt/stp/stp-remote-agent.jar=output=/var/stp/observations.json;includes=com.sap.commerce.' \
  -jar application.jar
```

## Test identity propagation

The client sends these standard W3C Baggage entries on each HTTP request:

```text
stp.test.suite.id=<suite ID>
stp.test.id=<test execution ID>
stp.request.id=<request ID>
```

The three values identify one concrete request and must each be nonblank, at most 256 characters, and free of ISO control characters. Attribution is accepted only when all three values are present and valid. Missing, partial, or invalid STP identity produces no attributed observation. `RequestID` is supplied by the client; the server agent does not invent or replace it.

For example, the HTTP header is standard `baggage`, not a custom `X-STP-*` header:

```text
baggage: stp.test.suite.id=checkout-suite,stp.test.id=scenario-17,stp.request.id=8d3f...
```

OpenTelemetry's default Java agent propagation includes W3C Baggage. Baggage is sent in HTTP headers, so use these values only on trusted test traffic and avoid sensitive data. See [OpenTelemetry Baggage](https://opentelemetry.io/docs/concepts/signals/baggage/) and the [Remote STP context propagation evidence](../docs/remote-otel-context-propagation.md).

If telemetry export is not needed for the test run, the OpenTelemetry exporters can be disabled independently:

```text
-Dotel.traces.exporter=none
-Dotel.metrics.exporter=none
-Dotel.logs.exporter=none
```

This disables signal export; it does not disable the OpenTelemetry agent's context/Baggage propagation used by Remote STP. The Java agent configuration documents `none` as a supported exporter value for traces, metrics, and logs ([configuration reference](https://opentelemetry.io/docs/zero-code/java/agent/configuration/)).

## Output and checkpoints

`output=` names one fragment file for one application JVM. Parent directories are created automatically. A completed output is not overwritten or appended to: use a fresh path for each run. Startup reserves the path and creates run-state markers; an interrupted run is handled according to the [output lifecycle contract](../docs/remote-stp-output-contract.md).

The default checkpoint interval is `flushIntervalSeconds=60`. Each successful checkpoint writes a cumulative schema-v2 output and only then compacts the checkpointed observations from memory. If a write fails, pending observations remain in memory and the previous valid output is preserved. Clean shutdown performs a final checkpoint. A hard crash can lose observations recorded since the last successful checkpoint; the agent cannot recover data that was only in memory.

The fragment keeps request and producer fields structured:

```json
{
  "schemaVersion": 2,
  "source": {
    "serviceId": "commerce",
    "instanceId": "commerce-pod-17",
    "revision": "abc123"
  },
  "requests": [
    {
      "testSuiteId": "checkout-suite",
      "testId": "scenario-17",
      "requestId": "8d3f...",
      "methods": ["com.example.CartController#add()V"]
    }
  ]
}
```

Methods are deduplicated within a request identity only. Different RequestIDs remain separate observations. The schema and file lifecycle are documented in the [output contract](../docs/remote-stp-output-contract.md).

## Debug endpoints

Set `debugPort=<port>` to start a separate JDK HTTP server bound to loopback. It is disabled by default and has no authentication or TLS; do not expose it outside a trusted host without operator-managed network controls.

| Endpoint | Response |
| --- | --- |
| `GET /stp/debug/memory` | Pending observations not yet compacted after a successful checkpoint. |
| `GET /stp/debug/output` | Latest successfully persisted checkpoint; returns JSON `404` before the first checkpoint. |
| `GET /stp/debug/snapshot` | Read-only union of persisted output and pending memory. |

All successful responses use schema-v2 observation JSON with source metadata. These endpoints do not flush, reset, or mutate recorder state.

## Supported async boundaries

The agent relies on OpenTelemetry automatic propagation where integration tests prove it is sufficient. Narrow STP call-site/listener adapters remain for measured gaps, including scheduled tasks, direct platform/virtual thread creation, [39 `CompletableFuture` callback registration forms](../docs/remote-completablefuture-propagation.md), and Servlet listener callbacks. The current tests exercise Servlet dispatch, Executor APIs, CompletableFuture paths, scheduled executors, direct threads, and selected Spring MVC async flows. This is not a claim of propagation through every Java framework or async API; see the [support matrix and evidence](../docs/remote-otel-context-propagation.md). Thread subclasses that override `run()` without a supplied `Runnable`, arbitrary untested executors, and async APIs outside that matrix are not covered by this POC.

## PetClinic validation

The repository includes a real two-JVM PetClinic proof. It uses `/actuator/health` for readiness, sends W3C Baggage from a separate HTTP harness, stops the server normally, and checks Vet/Owner attribution and the headerless case:

```bash
PETCLINIC_DIR=/path/to/spring-petclinic \
  ./stp-remote-agent/petclinic-poc/run-two-jvm-poc.sh
```

The full harness requires the controlled PetClinic checkout described in the script. The standalone Karate E2E project and multi-instance experiments are documented separately and are not part of the Remote STP agent module.

## Joining fragments

Once fragment files have been collected, combine them locally with the CLI:

```bash
smart-test-picker join-remote \
  --input ./fragments/*.json \
  --output distributed-map.json
```

The join preserves test, request, service, instance, revision, and method data. It joins only the files supplied and does not retrieve fragments or determine whether a distributed test run is complete. See the [fragment join contract](../docs/remote-stp-fragment-join-contract.md).
