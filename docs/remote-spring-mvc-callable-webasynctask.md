# Spring MVC Callable and WebAsyncTask propagation

This follow-up fixes the two gaps measured in [the initial Spring MVC validation](remote-spring-mvc-async-validation.md). It was developed from branch `research/asm-codex-remote` at baseline `fbc553ec7bcabf2f39bbf86d8b33b12464cd1097`. The change adds a narrowly targeted `WebAsyncManager` transformer; it does not add broad Spring instrumentation or change `@Async` behavior.

## Instrumented boundary

The transformer only accepts the exact class `org/springframework/web/context/request/async/WebAsyncManager`. It checks for method:

```text
startCallableProcessing(Lorg/springframework/web/context/request/async/WebAsyncTask;[Ljava/lang/Object;)V
```

and exactly one call to:

```text
AsyncTaskExecutor.submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;
```

It inserts `RemoteTestContext.wrap(Runnable)` immediately before that call. If the class, method, or call shape does not match, it leaves the bytes unchanged and emits a `spring-mvc-callable-boundary-unverified` diagnostic. The generic executor transformer explicitly skips this one class to avoid duplicate wrapping if an operator configures a broad Spring include.

The real fixture uses Spring MVC 6.1.14 and verified that the expected bytecode shape is present. It exercises both controller `Callable` and `WebAsyncTask`, since Spring routes both through that same `WebAsyncManager` submission method.

## Result matrix

| Spring boundary | Result | Evidence |
|---|---|---|
| Controller `Callable` | PASS | Callback ran on the configured MVC worker after the initial REQUEST dispatch returned; callback application methods were recorded under its own TestID. |
| `WebAsyncTask` | PASS | Callback ran on a separate task-executor worker after the initial REQUEST dispatch returned; callback application methods were recorded under its own TestID. |
| `DeferredResult` | PASS | Existing application `Executor.execute` propagation and Servlet ASYNC redispatch attribution still passed. |
| Spring `@Async` | GAP | Still runs on another thread without the originating TestID; intentionally unchanged. |
| Direct application `AsyncTaskExecutor` calls | PASS | `execute`, `submit(Runnable)`, and `submit(Callable)` remained attributed through the existing generic executor call-site transformer. |

The fixture also sent Callable A and B and WebAsyncTask A and B sequentially, followed by headerless requests. Each request produced exactly its expected observation ID; the complete set of remote IDs matched the IDs sent, so the headerless requests produced none. The single-thread worker for each callback path was reused across success, failure, and headerless work. Callback exceptions returned HTTP 500, and the following headerless callback on the same worker saw no ID. A barrier proved Callable A and WebAsyncTask B callbacks overlapped; each retained its own ID and method set.

## Remaining `@Async` gap

No `@Async` fix was added. In the validated Spring 6.1.14 path, `AsyncExecutionAspectSupport.doSubmit(...)` submits `CompletableFuture`-returning work through `AsyncTaskExecutor.submitCompletable(Callable)` from Spring AOP framework code. That is outside the one targeted MVC boundary and remains out of scope.

## Validation

`./gradlew :stp-remote-agent:test --no-daemon --console=plain` passed with **31 tests, 0 failures, 0 skipped**. The Spring fixture also passed independently with the focused command:

```text
./gradlew :stp-remote-agent:test --tests '*RemoteSpringMvcCallableTransformerTest' --tests '*SpringMvcAsyncIntegrationTest' --no-daemon --console=plain
```

The existing PetClinic manual two-JVM proof passed against PetClinic commit `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`: `/actuator/health` readiness, `vets-A` mapped to 16 methods, `owner-B` mapped to 21, and the headerless request produced no observation.
