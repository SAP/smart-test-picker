# Remote STP context propagation with OpenTelemetry

## Decision

Remote STP now reads its structured identity from the active OpenTelemetry `Context`. The identity is carried as W3C Baggage entries `stp.test.suite.id`, `stp.test.id`, and `stp.request.id`. The Remote STP agent still owns method identity, method-hit recording, and the request-to-method observation map. It does not create spans for application methods.

The server JVM must start the OpenTelemetry Java agent before `stp-remote-agent`, for example:

```text
java -javaagent:opentelemetry-javaagent.jar -javaagent:stp-remote-agent.jar=output=...;includes=... -jar application.jar
```

The STP agent ships OpenTelemetry API 1.66.0; the OTel Java agent must be attached first to provide automatic HTTP and execution-boundary context propagation. W3C Baggage is the HTTP carrier for STP identifiers. W3C Trace Context remains the standard carrier for trace/span identity when a client has an active trace; STP identity does not depend on creating method-level spans.

## What the audit covered

Before the change, Remote STP had a request `ThreadLocal` lifecycle layered onto custom Servlet FilterChain, Servlet, request-listener, async-listener, I/O-listener and executor call-site transformers. The executor transformer handled `Executor.execute`, `ExecutorService.submit` overloads, `CompletableFuture` stages, scheduled executor overloads, and direct platform/virtual `Thread` constructors and builders. Separate Spring transformers handled MVC `Callable`/`WebAsyncTask` and the tested Spring `@Async CompletableFuture` submission boundary. Tests covered synchronous and redispatched Servlet requests, nested filters, concurrent requests, thread reuse, failure cleanup, and the listed callback/task scenarios.

The new propagation tests run with OpenTelemetry Java agent 2.32.0, OpenTelemetry API 1.66.0, JDK 21.0.12, Jetty 11 / Jakarta Servlet for the HTTP fixtures, and Spring MVC 6.1.14 for the Spring fixture. Existing integration fixtures are run with only the remaining measured-gap adapters active. The Servlet, executor, scheduled, thread, CompletableFuture, and Spring tests exercise real child JVMs and HTTP requests.

## Support matrix

| STP scenario | Former STP mechanism | OTel automatic result in the STP fixture | Final mechanism and evidence |
|---|---|---|---|
| HTTP baggage extraction into Servlet request | Custom request header parsing and thread scope | PASS | OTel Java agent extracts W3C Baggage; `RemoteAgentIntegrationTest` observes request methods without an STP Servlet boundary transformer. |
| Servlet FilterChain, nested filters, synchronous service | FilterChain and `Servlet.service` advice | PASS | OTel Servlet instrumentation; normal, nested, concurrent, and headerless cases in `RemoteAgentIntegrationTest`. |
| Servlet FORWARD, INCLUDE, ERROR and ASYNC redispatch | Request attribute plus repeated boundary advice | PASS | OTel Servlet context; forward/include/error/async cases in `RemoteAgentIntegrationTest`. |
| Servlet request lifecycle listeners | `ServletRequestListener` advice | PASS | OTel Servlet context; initialize/destroy application methods are observed in the same fixture. |
| Concurrent requests, pooled request-thread reuse, request cleanup | STP thread-local scope restoration | PASS | OTel Servlet context isolation; sequential and concurrent requests plus headerless control in `RemoteAgentIntegrationTest`. |
| `Executor.execute`, `ExecutorService.submit(Runnable/Callable)` | Application call-site wrappers | PASS | OTel Java agent propagation; executor integration tests pass with STP executor call-site wrapping removed. |
| `CompletableFuture.runAsync` / `supplyAsync`, common and supplied executor | STP Runnable/Supplier wrappers | PASS | OTel Java agent context propagation; common-pool and explicit-executor cases in `RemoteCompletableFutureIntegrationTest`. |
| `CompletableFuture.thenRunAsync` / `thenApplyAsync` registered in a request and completed after that request | STP registration-time stage wrappers | GAP | OTel-only fixture loses the identity when the predecessor completes later; retain STP call-site wrappers using `Context.wrap` at stage registration. Verified by the failing OTel-only fixture and passing final regression fixture. |
| `ScheduledExecutorService.schedule`, fixed-rate and fixed-delay | STP scheduling bridges | GAP | OTel-only fixture recorded the registering request but not scheduled callback methods. Retain the four narrow scheduling bridges, using OTel `Context.wrap`; final delayed, periodic, reuse and failure checks pass. |
| Direct `new Thread(Runnable)` and ThreadGroup/name overloads | STP constructor call-site wrappers | GAP | OTel-only direct-thread fixture did not record Runnable methods. Retain the Runnable constructor wrappers. |
| Virtual `Thread.startVirtualThread` and `Thread.Builder.start` | STP call-site wrappers | GAP | OTel-only virtual-thread fixture did not propagate; retain wrappers when those JDK APIs are present. JDK 21 exercised these cases. |
| `AsyncContext.start(Runnable)` and Spring MVC async dispatch | STP boundary/Spring-specific transformers | PASS | OTel Java agent; these pass after removing STP-specific transformers. `RemoteAgentIntegrationTest` and `SpringMvcAsyncIntegrationTest`. |
| `AsyncListener` completion/start/error callback application work | Listener-to-Context association | PASS for tested callbacks | OTel-only fixture attributes application work for start, completion and error. Keep the small listener association because timeout callback propagation is not automatic. |
| `AsyncListener.onTimeout` callback | Listener-to-Context association | GAP | OTel-only fixture showed the timeout callback's application methods absent; keep the listener registration/callback scope adapter. |
| Servlet `ReadListener` / `WriteListener` callbacks | Listener-to-context weak association | GAP | OTel-only fixture omitted read/write callback application methods; keep only listener registration and callback scope advice. |
| Spring MVC `Callable`, `WebAsyncTask`, `DeferredResult`, tested `@Async CompletableFuture`, direct `AsyncTaskExecutor` | Targeted Spring transformers plus generic wrappers | PASS | Spring fixture passes with those STP transformers removed; Spring 6.1.14 test task. |
| Direct `ForkJoinPool.commonPool().execute(Runnable)` | Not separately covered by the former Remote STP fixture | PASS | Added a real HTTP application call-site and task; it passes without a STP executor wrapper in `RemoteAgentIntegrationTest`. |
| Reactor | No Reactor application dependency in the current Remote STP fixtures | NOT MEASURED | OTel documents Reactor context propagation support, but this STP POC has no Reactor integration fixture; no STP support claim is made. |

The OpenTelemetry Java agent documents Servlet, Spring, and Reactor instrumentation; its Java API documents immutable `Context`, thread-local current-context storage, wrappers for task APIs, and propagators. Those documents establish available mechanisms, while the matrix only calls a scenario supported when the STP child-JVM fixture also demonstrated it.

## Identity and recording

The incoming `baggage` header is extracted by OpenTelemetry instrumentation. `RemoteTestContext.currentIdentity()` reads the three STP baggage entries from `Context.current()`. If the complete identity is not present and valid, the recorder creates no attributed observation. The identity values remain structured fields in the Remote STP observation schema; they are not collapsed into a delimiter string.

At the measured gaps, the STP agent captures the current OTel `Context` and restores it with OTel `Scope` semantics. Listener mappings use weak listener keys. No custom identity `ThreadLocal`, HTTP propagation header family, or Spring-wide instrumentation remains active. No spans are created for method entries.

## Test commands

```sh
./gradlew :stp-karate-client:test --no-daemon --console=plain
./gradlew :stp-remote-agent:test --no-daemon --console=plain
```

The PetClinic proof scripts now attach `opentelemetry-javaagent.jar` before the STP agent and send STP identity in W3C Baggage. Readiness remains a headerless `/actuator/health` request.

## References

- [OpenTelemetry Java API and Context](https://opentelemetry.io/docs/languages/java/api/)
- [OpenTelemetry Java instrumentation model](https://opentelemetry.io/docs/languages/java/instrumentation/)
- [OpenTelemetry Java agent supported libraries](https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/)
- [W3C Baggage API](https://opentelemetry.io/docs/specs/otel/baggage/api/)
- [W3C Baggage specification](https://opentelemetry.io/docs/specs/otel/baggage/)
- [OpenTelemetry propagators](https://opentelemetry.io/docs/specs/otel/context/api-propagators/)

## CompletableFuture registration coverage update

The original migration measurements above are retained as historical evidence. The registration adapter now covers 39 synchronous/default-async/explicit-executor callback shapes. OTel 2.32.0 alone fails registration ownership for all 39 when request B completes a future whose callback was registered by A. The extended HTTP fixture verifies exact schema-v2 method relations, full Context restoration, cancellation, concurrent registrations and delayed composition. See [Remote CompletableFuture support and evidence](remote-completablefuture-propagation.md) for the complete matrix, JDK requirements and precise call-site limitations.
