<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP remote servlet request correlation agent POC

This independent Java agent records selected application method entries against the three-field HTTP request identity received by a Servlet server. It has no dependency on `stp-agent`, `stp-runtime`, or a test framework.

Build the shaded standalone agent with:

```bash
./gradlew :stp-remote-agent:remoteAgentJar
```

Run the server JVM with an explicit output file and application package allow-list:

```text
-javaagent:/path/stp-remote-agent.jar=output=/path/remote-observations.json;includes=com.example.application.
```

Each request must carry `X-STP-Test-Suite-Id`, `X-STP-Test-Id`, and `X-STP-Request-Id`. Every value must be nonblank, at most 256 characters, and free of ISO control characters; partial or invalid identity sets are ignored. Comma-separated package prefixes are accepted. `excludes` can narrow an included prefix. The agent instruments concrete `javax.servlet` and `jakarta.servlet` implementations of `FilterChain.doFilter`, `Servlet.service(ServletRequest, ServletResponse)`, `ServletRequestListener.requestInitialized/requestDestroyed`, and all four `AsyncListener` callbacks. No controller changes are needed.

The first valid header set is stored as an immutable identity object in an internal Servlet request attribute and temporarily installed on the current request thread. Later Servlet dispatches reuse that accepted value, so conflicting headers cannot change request ownership. Async and I/O listener callbacks recover the identity from request state or weak listener associations. All supported task wrappers capture and restore the complete identity. Observations are deduplicated by method only within the exact suite/test/request tuple; separate RequestIDs remain separate records.

Application call sites for `new Thread(Runnable)` and the named/ThreadGroup constructor forms wrap the Runnable with the ID active at construction. `Thread.startVirtualThread(Runnable)` and `Thread.Builder.start(Runnable)` are also recognized without a compile-time dependency on virtual-thread APIs. Thread subclasses that override `run()` without a Runnable are unsupported. Executor call sites for `Executor.execute(Runnable)` and `ExecutorService.submit(Runnable)`, `submit(Runnable, result)`, and `submit(Callable)` wrap tasks with the current ID at submission. The agent also wraps `CompletableFuture.runAsync`, `supplyAsync`, `thenRunAsync`, and `thenApplyAsync` with or without an explicit executor. `ScheduledExecutorService.schedule(Runnable|Callable)`, `scheduleAtFixedRate`, and `scheduleWithFixedDelay` route through a small context bridge that preserves their `ScheduledFuture` results and captures the ID at registration. Custom executor owners are instrumented only when their class-file hierarchy resolves to the required executor interface; unresolved owners are skipped with an attribution-incomplete diagnostic. JDK classes themselves are never transformed. Runnable, Callable, Supplier, and Function scopes restore the worker's previous identity in `finally`.

At server JVM shutdown, the agent atomically writes schema-version-2 JSON with explicit `testSuiteId`, `testId`, and `requestId` fields under `requests`. Synchronous dispatch, supported async callbacks, and task APIs are covered; unsupported async mechanisms remain outside this POC. This agent has no live export, storage, authentication contract, or cross-service propagation. Correlation headers are trusted test metadata and should only be enabled in an isolated/test deployment protected from arbitrary callers.

The automated integration fixture starts an embedded Servlet server in a child JVM with this agent attached, then makes real HTTP calls exercising dispatch, listener, executor submission, no-ID, exception, and concurrent-request paths. Jetty 11 exercises the `jakarta.servlet` path; transformed-class verification covers both Servlet namespaces.

## PetClinic two-JVM proof

To validate against the standalone Spring PetClinic checkout without changing its application code, run:

```bash
PETCLINIC_DIR=/path/to/spring-petclinic-asm ./stp-remote-agent/petclinic-poc/run-two-jvm-poc.sh
```

`PETCLINIC_DIR` is required. The script builds PetClinic's executable jar with tests skipped, starts it as JVM A with this agent attached, then compiles and starts a small JDK `HttpClient` harness as JVM B. The default readiness probe is `GET /actuator/health`; `PROBE_PATH` can select another explicit path and the harness logs it. The probe must return HTTP 200 and never falls back to an application route. JVM B sends `GET /vets` with `vets-A`, `GET /owners/1` with `owner-B`, and a headerless `GET /vets`. It stops JVM A to flush observations and verifies the captured Vet and Owner controller paths, absence of cross-attribution, and absence of a headerless observation. Verification also prints the full method count for each execution ID without applying count thresholds. `PORT`, `OUTPUT_DIR`, and `OBSERVATIONS_NAME` can override defaults; the output defaults under this module's ignored `build/` directory and includes a provenance text file.

The harness is plain Java and does not start or depend on a PetClinic Spring test context. This POC verifies the JSON Vet route and the HTML Owner details route over real HTTP between separate JVMs.
