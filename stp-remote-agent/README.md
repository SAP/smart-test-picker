<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP remote servlet request correlation agent POC

This independent Java agent records selected application method entries against an HTTP test execution ID received by a Servlet server. It has no dependency on `stp-agent`, `stp-runtime`, or the JUnit adapter and does not use JUnit test lifecycle callbacks.

Build the shaded standalone agent with:

```bash
./gradlew :stp-remote-agent:remoteAgentJar
```

Run the server JVM with an explicit output file and application package allow-list:

```text
-javaagent:/path/stp-remote-agent.jar=output=/path/remote-observations.json;includes=com.example.application.;header=X-STP-Test-Execution-Id
```

The default header is `X-STP-Test-Execution-Id`. Comma-separated package prefixes are accepted. `excludes` can narrow an included prefix. The agent instruments concrete `javax.servlet` and `jakarta.servlet` implementations of `FilterChain.doFilter`, `Servlet.service(ServletRequest, ServletResponse)`, `ServletRequestListener.requestInitialized/requestDestroyed`, and all four `AsyncListener` callbacks. No controller changes are needed.

The first valid header value is stored in an internal Servlet request attribute and temporarily installed on the current request thread. Later Servlet dispatches reuse that accepted value, so a conflicting header cannot change request ownership. Async listener callbacks recover the ID from that request attribute. `ServletInputStream.setReadListener` and `ServletOutputStream.setWriteListener` capture the active ID against the original listener instance; callback scopes restore the previous thread state. Listener associations use weak identity keys and are replaced or cleared on registration, so listener objects are not retained. Missing, blank, overlong, or control-character IDs suppress attribution. Nested boundary scopes restore their parent, and each callback exit restores or clears the prior thread state. `AsyncContext.start(Runnable)` captures the active ID at submission and restores the async worker's previous context after the task. Application methods are instrumented only under configured prefixes, and repeated hits are deduplicated by method identity.

Application call sites for `Executor.execute(Runnable)` and `ExecutorService.submit(Runnable)`, `submit(Runnable, result)`, and `submit(Callable)` wrap tasks with the current ID at submission. The agent also wraps `CompletableFuture.runAsync`, `supplyAsync`, `thenRunAsync`, and `thenApplyAsync` with or without an explicit executor. `ScheduledExecutorService.schedule(Runnable|Callable)`, `scheduleAtFixedRate`, and `scheduleWithFixedDelay` route through a small context bridge that preserves their `ScheduledFuture` results and captures the ID at registration. Custom executor owners are instrumented only when their class-file hierarchy resolves to the required executor interface; unresolved owners are skipped with an attribution-incomplete diagnostic. JDK classes themselves are never transformed. Runnable, Callable, Supplier, and Function scopes restore the worker's previous identity in `finally`.

At server JVM shutdown, the agent atomically writes a small JSON observation file. Synchronous `REQUEST`, `FORWARD`, `INCLUDE`, and `ERROR` processing, `ASYNC` redispatch, tasks submitted through `AsyncContext.start(Runnable)` or supported Executor call sites, `AsyncListener` callbacks, and Servlet non-blocking `ReadListener`/`WriteListener` callbacks are covered. Work running between `startAsync()` and redispatch and arbitrary background work submitted through other APIs are not propagated. This POC has no live export, storage, authentication contract, or cross-service propagation. The correlation header is trusted test metadata and must only be enabled in an isolated/test deployment protected from arbitrary callers.

The automated integration fixture starts an embedded Servlet server in a child JVM with this agent attached, then makes real HTTP calls exercising dispatch, listener, executor submission, no-ID, exception, and concurrent-request paths. Jetty 11 exercises the `jakarta.servlet` path; transformed-class verification covers both Servlet namespaces.

## PetClinic two-JVM proof

To validate against the standalone Spring PetClinic checkout without changing its application code, run:

```bash
PETCLINIC_DIR=/path/to/spring-petclinic-asm ./stp-remote-agent/petclinic-poc/run-two-jvm-poc.sh
```

`PETCLINIC_DIR` is required. The script builds PetClinic's executable jar with tests skipped, starts it as JVM A with this agent attached, then compiles and starts a small JDK `HttpClient` harness as JVM B. The default readiness probe is `GET /actuator/health`; `PROBE_PATH` can select another explicit path and the harness logs it. The probe must return HTTP 200 and never falls back to an application route. JVM B sends `GET /vets` with `vets-A`, `GET /owners/1` with `owner-B`, and a headerless `GET /vets`. It stops JVM A to flush observations and verifies the captured Vet and Owner controller paths, absence of cross-attribution, and absence of a headerless observation. Verification also prints the full method count for each execution ID without applying count thresholds. `PORT`, `OUTPUT_DIR`, and `OBSERVATIONS_NAME` can override defaults; the output defaults under this module's ignored `build/` directory and includes a provenance text file.

The harness is plain Java and does not start or depend on a PetClinic Spring test context. This POC verifies the JSON Vet route and the HTML Owner details route over real HTTP between separate JVMs.
