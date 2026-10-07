<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP remote servlet request correlation agent POC

This independent Java agent records selected application method entries against the three-field request identity in OpenTelemetry Context. It has no dependency on `stp-agent`, `stp-runtime`, or a test framework; it uses the OpenTelemetry API and W3C Baggage.

Build the shaded standalone agent with:

```bash
./gradlew :stp-remote-agent:remoteAgentJar :stp-remote-agent:copyOpenTelemetryJavaAgent
```

Run the server JVM with an explicit output file and application package allow-list:

```text
-javaagent:/path/opentelemetry-javaagent.jar -javaagent:/path/stp-remote-agent.jar=output=/path/remote-observations.json;includes=com.example.application.
```

`output=` is one file owned by this application JVM. Absolute paths are accepted; relative paths resolve against the JVM working directory and are normalized. Parent directories are created at startup. The agent takes an OS lock on `<output>.lock` and creates `<output>.inprogress` plus an empty output reservation. A completed existing schema-v2 output is never overwritten. A stale marker allows a later run to remove only this output's reservation/temp files; the immediately previous contract's empty reservation and exact `<output>.tmp` are also recognized for migration. Recovery does not restore observations that existed only in memory. Observations are written on clean JVM shutdown as one schema-v2 JSON snapshot: serialize completely, write/sync a unique path-hashed temp file, move it over the reservation atomically where supported, and remove the marker last. See [the output contract](../docs/remote-stp-output-contract.md) for crash states, malformed-artifact handling, and restart behavior.

An optional `debugPort=<1..65535>` starts an agent-owned JDK HTTP server on loopback only. It serves read-only `GET /stp/debug/memory` snapshots during the run and `GET /stp/debug/output` only after clean finalization. The debug listener is disabled when the option is absent. Do not expose it externally without adding operator-managed network controls; this debug endpoint has no authentication or TLS.

Send the identity as standard W3C Baggage entries `stp.test.suite.id`, `stp.test.id`, and `stp.request.id`. Each value must be nonblank, at most 256 characters, and free of ISO control characters; partial or invalid identity sets are ignored. The OpenTelemetry Java agent must be attached first so its Servlet instrumentation extracts Baggage into `Context.current()`. STP reads the identity from that context when instrumented application methods execute. Comma-separated package prefixes are accepted. `excludes` can narrow an included prefix. No controller changes are needed.

The OpenTelemetry agent owns normal Servlet request, dispatch, and request-thread context. STP keeps only weak listener-to-Context associations for delayed `AsyncListener`, `ReadListener`, and `WriteListener` callbacks, plus call-site adapters for direct Thread creation, `ScheduledExecutorService`, and late-registered `CompletableFuture` chained stages where the OTel-only fixtures showed gaps. These adapters capture and restore the complete OTel Context. Observations are deduplicated by method only within the exact suite/test/request tuple; separate RequestIDs remain separate records.

Executor, ExecutorService, CompletableFuture `runAsync`/`supplyAsync`, Servlet async dispatch, and tested Spring MVC paths use OpenTelemetry automatic context propagation. Direct `new Thread(Runnable)`, including named/ThreadGroup variants, `Thread.startVirtualThread(Runnable)`, and `Thread.Builder.start(Runnable)` use a narrow STP call-site wrapper; Thread subclasses that override `run()` without a Runnable remain unsupported. The agent also wraps only `thenRunAsync` and `thenApplyAsync` calls where registration-time capture is needed after a later future completion. Scheduled executor overloads use a context bridge because the OTel-only test showed they did not capture identity at scheduling time. JDK classes themselves are never transformed. Runnable, Callable, and Function scopes restore the worker's prior OTel Context in `finally`.

At server JVM shutdown, the agent atomically writes schema-version-2 JSON with explicit `testSuiteId`, `testId`, and `requestId` fields under `requests`. Synchronous dispatch, supported async callbacks, and task APIs are covered; unsupported async mechanisms remain outside this POC. This agent has no live export, storage, authentication contract, or cross-service propagation. STP Baggage values are trusted test metadata and should only be enabled in an isolated/test deployment protected from arbitrary callers.

The automated integration fixture starts an embedded Servlet server in a child JVM with this agent attached, then makes real HTTP calls exercising dispatch, listener, executor submission, no-ID, exception, and concurrent-request paths. Jetty 11 exercises the `jakarta.servlet` path; transformed-class verification covers both Servlet namespaces.

## PetClinic two-JVM proof

To validate against the standalone Spring PetClinic checkout without changing its application code, run:

```bash
PETCLINIC_DIR=/path/to/spring-petclinic-asm ./stp-remote-agent/petclinic-poc/run-two-jvm-poc.sh
```

`PETCLINIC_DIR` is required. The script builds PetClinic's executable jar with tests skipped, starts it as JVM A with this agent attached, then compiles and starts a small JDK `HttpClient` harness as JVM B. The default readiness probe is `GET /actuator/health`; `PROBE_PATH` can select another explicit path and the harness logs it. The probe must return HTTP 200 and never falls back to an application route. JVM B sends `GET /vets` with `vets-A`, `GET /owners/1` with `owner-B`, and a headerless `GET /vets`. It stops JVM A to flush observations and verifies the captured Vet and Owner controller paths, absence of cross-attribution, and absence of a headerless observation. Verification also prints the full method count for each execution ID without applying count thresholds. `PORT`, `OUTPUT_DIR`, and `OBSERVATIONS_NAME` can override defaults; the output defaults under this module's ignored `build/` directory and includes a provenance text file.

The harness is plain Java and does not start or depend on a PetClinic Spring test context. This POC verifies the JSON Vet route and the HTML Owner details route over real HTTP between separate JVMs.
