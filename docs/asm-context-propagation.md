# ASM logical test context propagation

The runtime attributes method hits to a logical test or setup container, not merely
to a worker thread. Instrumented submission sites capture that context; wrappers
install it while the submitted work runs and restore the worker's prior state on
return or exception. Nested submissions capture the installed context. No owner is
guessed when submission has no test or container context.

## Supported boundaries

The agent recognizes exact invocation descriptors for:

- `Executor.execute` and `ExecutorService.submit` Runnable/Callable overloads;
- `CompletableFuture.runAsync`, `supplyAsync`, `thenApplyAsync` and `thenRunAsync`,
  with or without an explicit executor;
- scheduled executor one-shot and periodic Runnable/Callable submissions;
- common Runnable-bearing `Thread` constructors, and on Java 21 the supported
  virtual-thread start methods;
- application ForkJoin tasks with observed submission and instrumented execution,
  subject to the [ForkJoin limitations](asm-forkjoin-task-propagation.md).

Executor ownership is checked through bytecode hierarchy resolution. Reflection,
method handles, preloaded callers, arbitrary custom scheduling APIs and thread
subclasses overriding `run` are not automatically covered. Context propagation
does not imply support for parallel JUnit execution or a shared cached web context.

## Lifetime and diagnostics

Each functional submission with an active owner gets its own wrapper. A wrapper
can be visible to identity-sensitive executor code. Cancellation and removal from
the executor's queue remain the executor's responsibility.

Work captured before a test finishes retains that owner afterwards, but its hits
are recorded as `LATE_EVENT`, not ordinary coverage for a subsequent test. Work for
a closed setup container can emit `ASYNC_SETUP_UNSUPPORTED`. ERROR setup diagnostics
still reject collection. Collector error strings alone are informational; they
must not be confused with that setup-diagnostic rejection mechanism.

Periodic work retains its scheduling owner. Publication does not wait for arbitrary
asynchronous descendants. [Suspension](runtime-test-suspension.md) also does not
cancel captured work: callers must ensure no such work is outstanding before suspending.

Implementation: `ExecutorCallSiteTransformer`, `RuntimeHooks`,
`RuntimeContextService` and `RuntimeEventAggregator`. The
[historical design record](history/asm-context-propagation.md) describes earlier stages.
