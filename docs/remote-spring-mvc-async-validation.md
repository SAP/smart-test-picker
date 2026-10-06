# Spring MVC async correlation validation

This note records a test-only measurement of Spring MVC asynchronous execution against the existing Remote STP propagation in `research/asm-codex-remote` at `2509be3c16aba18362d15713e2d4ff11bbc97d4e` (the fixture and this note are uncommitted additions). No production instrumentation was changed.

The fixture uses Spring MVC 6.1.14, Jetty 11.0.25, Jakarta Servlet 5.0, and Java 21. It starts a real Spring MVC application in a child JVM with `stp-remote-agent`; a separate test JVM sends real HTTP requests carrying `X-STP-Test-Execution-Id`. The fixture logs request and callback thread names, the `RemoteTestContext` ID seen in application methods, and monotonic timestamps. The test checks that asynchronous application work occurs after the initial Servlet REQUEST dispatch has returned where the API allows it.

## Results

| Spring boundary | Async thread confirmed | TestID propagated to application callback | Existing mechanism | Result |
|---|---:|---:|---|---|
| Controller `Callable<String>` | Yes | No | Spring MVC `WebAsyncManager` submits its callback | GAP |
| Controller `WebAsyncTask<String>` | Yes | No | Spring MVC `WebAsyncManager` submits its callback | GAP |
| `DeferredResult<String>` | Yes | Yes, through completion and ASYNC redispatch | Application `Executor.execute` call-site wrapping, then Servlet request-attribute recovery | PASS |
| Spring `@Async` service | Yes | No | Spring AOP async interceptor submits the invocation | GAP |
| Application calls to `AsyncTaskExecutor.execute`, `submit(Runnable)`, and `submit(Callable)` | Yes | Yes for all three forms | Existing Executor call-site wrapping after hierarchy resolution | PASS |

The fixture sent TestID A, TestID B, and a headerless request through each applicable route. It verified that A and B callbacks were attributed only to their own IDs. Headerless callbacks saw no ID and created no remote observation. The application `AsyncTaskExecutor` used one worker thread across A, B, and headerless work; each execution saw the expected ID and the worker had no stale ID on the next execution. For `Callable`, `WebAsyncTask`, `DeferredResult`, and `@Async`, the fixture compared callback timestamps with the initial REQUEST dispatch exit. The callbacks ran after that dispatch returned.

## Exact gaps

Spring MVC's `WebAsyncManager.startCallableProcessing(...)` is the submission boundary for both controller `Callable` and `WebAsyncTask`. In Spring Web 6.1.14 its implementation calls `AsyncTaskExecutor.submit(Runnable)` from the Spring framework class. The generic call-site transformer only instruments configured application classes, so it never wraps that Runnable. The request still has an accepted ID on its Servlet request attribute, but the callback runs on a worker with an empty `RemoteTestContext`. The later Servlet ASYNC redispatch can recover the request ID; that does not attribute the earlier callback body.

For `@Async`, Spring AOP's `AsyncExecutionInterceptor` delegates to `AsyncExecutionAspectSupport.doSubmit(...)`. Because this fixture's service returns `CompletableFuture`, Spring 6.1.14 calls `AsyncTaskExecutor.submitCompletable(Callable)` from framework code. That call site is outside the configured application packages, and `submitCompletable` is not one of the generic Executor shapes currently wrapped. The service method therefore runs on a different thread with no TestID.

By comparison, the DeferredResult completion starts at `SpringMvcService.deferred()` and calls `Executor.execute(Runnable)` from configured application bytecode. The existing transformer wraps the task at that call site. Its completion sees the request ID, and the subsequent ASYNC dispatch recovers the same ID from the Servlet request attribute.

The three direct `AsyncTaskExecutor` calls are also in configured application bytecode. The transformer resolves `AsyncTaskExecutor` through its Executor hierarchy and wraps each supported overload. This validates direct application usage; it does not establish that Spring's internal use of the same executor is covered.

## Validation commands and regressions

The complete `./gradlew :stp-remote-agent:test --no-daemon --console=plain` suite passed: **27 tests, 0 failures, 0 skipped**. This includes the new child-JVM Spring fixture.

The existing PetClinic proofs also passed against the clean PetClinic checkout at `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`:

- `run-two-jvm-poc.sh`: `/actuator/health` readiness; `vets-A` recorded 16 methods, `owner-B` 21; the headerless request created no observation.
- `run-existing-stp-identity-poc.sh sequential`: all five HTTP test IDs joined exactly to their remote observations; client instrumentation was off and `bytecodeModified=false`.
- `run-existing-stp-identity-poc.sh parallel`: the same five IDs joined; the verifier proved overlap between `vetsRequest` and `ownerRequest` with no cross-attribution.

No Spring-specific production hook, broad Spring framework instrumentation, or changes to PetClinic application code were made. The three GAPs are evidence for a separate follow-up; this task does not implement their fixes.
