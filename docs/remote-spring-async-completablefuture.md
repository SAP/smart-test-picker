# Spring `@Async` CompletableFuture correlation

This change closes the measured Spring `@Async` gap for the concrete CompletableFuture-returning path in the Spring MVC fixture. The server-side agent still receives only an opaque request TestID; it has no JUnit or Spring dependency in its runtime context API.

## Verified Spring boundary

The fixture uses Spring Framework 6.1.14. Its loaded `org.springframework.aop.interceptor.AsyncExecutionAspectSupport` bytecode contains:

```text
doSubmit(Ljava/util/concurrent/Callable;Lorg/springframework/core/task/AsyncTaskExecutor;Ljava/lang/Class;)Ljava/lang/Object;
  INVOKEINTERFACE org/springframework/core/task/AsyncTaskExecutor.submitCompletable
    (Ljava/util/concurrent/Callable;)Ljava/util/concurrent/CompletableFuture;
```

There is exactly one matching `submitCompletable(Callable)` call in that method. This is the `CompletableFuture` return branch of Spring's async submission. `RemoteSpringAsyncTransformer` verifies the target class, method descriptor, call descriptor, and one-call count before changing bytes. A mismatch leaves the class unchanged and reports `spring-async-boundary-unverified:...`. The generic executor transformer explicitly excludes this target class, including when an operator configures broad Spring `includes`; the targeted transformer is the sole owner of the call site.

At the verified call site the existing `RemoteTestContext.wrap(Callable)` captures the active TestID. The wrapped Callable runs the Spring invocation under that ID and restores the worker's previous context in `finally`.

## Measured fixture results

The real child-JVM Spring MVC fixture sent TestID A and B through the `@Async` endpoint, then sent a headerless request. Its app-level log and remote observations showed:

- A and B application methods ran on the separate `spring-async-` worker after the initial Servlet REQUEST dispatch returned.
- Each response retained the expected `asyncService|TestID|worker` value, and both requests returned HTTP 200.
- A forced exception in the async service returned HTTP 500; the next headerless callback on the same single-thread worker observed no TestID.
- Two `@Async` requests on separate worker threads passed a barrier concurrently. Each repository hit was recorded only under its own TestID.
- The headerless request created no observation. The complete observed ID set matched the supplied IDs.

| Spring boundary | Result |
|---|---|
| Controller `Callable` | PASS |
| `WebAsyncTask` | PASS |
| `DeferredResult` | PASS |
| Direct application `AsyncTaskExecutor` | PASS |
| `@Async` returning `CompletableFuture` | PASS for the fixture's Spring 6.1.14 `submitCompletable(Callable)` path |

This does not establish support for other `@Async` return types or other Spring bytecode shapes. Existing PetClinic manual-ID, sequential existing-STP identity, and parallel existing-STP identity two-JVM proofs also passed after the change.
