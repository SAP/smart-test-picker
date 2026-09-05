<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP ASM coverage POC — round 3 cross-thread attribution

## Decision

**GENERIC EXECUTOR PROPAGATION NEEDS MORE WORK**

Submission-time task wrapping is safe for the boundary actually proven: a user-code bytecode call whose symbolic owner is `java.util.concurrent.Executor` or `ExecutorService`, using `execute(Runnable)` or any `submit` overload accepting `Runnable` or `Callable`. The active `TestIdentity` is captured for that submission, installed only while that task executes, and the worker's previous active/finished state is restored in `finally`.

This is not yet a generally complete JVM mechanism. Calls compiled against concrete executor types or subinterfaces are not rewritten. Submissions originating inside JDK/framework bytecode are not rewritten, notably `CompletableFuture.*Async(..., executor)`. `Thread`, scheduled operations, fork/join, virtual-thread builders/executors, and structured concurrency remain unsupported. Those omissions can still produce RTS false negatives, so the result cannot be “proven” without qualification.

This round did not change JaCoCo, STP selection, Spring integration, HTTP correlation, or multi-agent behavior.

## Required semantic model

The attribution belongs to a logical task, not a worker thread:

1. **Creation** constructs a `Runnable`, `Callable`, stage, or task. It is too early to capture: the same object may be submitted later, repeatedly, or by different tests.
2. **Submission** transfers one logical execution to an execution facility. This is the correct capture point. Each submission needs a fresh snapshot even when the task object is reused.
3. **Execution** installs that immutable snapshot immediately around user work.
4. **Completion** restores the exact prior worker state in `finally`, for return, exception, or cancellation-related exit.
5. **Thread reuse** must observe no residue. A later task receives its own submission snapshot or no test at all.

The POC snapshot contains only `TestIdentity`. A wrapper is allocated only when a test is active; no-context submissions retain the original task object. During execution the wrapper temporarily clears the worker's `lastFinished` marker as well as installing the captured active test, then restores both prior values. If Test A has already ended, its delayed task is recorded as `LATE_EVENT` under A; it cannot become Test B merely because B is active on the submitter or once used the worker.

Nested submission is deliberately transitive: Task X executes with its captured identity installed, so a nested submission captures that same identity. This is correct for unstructured descendants but implies that fire-and-forget descendants executing after the test ends become late events for the originating test.

## Execution-model survey

| Model | Creation and submission | Execution/completion/reuse | Safe capture/restore point | Round-3 status |
|---|---|---|---|---|
| `new Thread(r)` | Task is associated in the constructor; `start()` performs the handoff, possibly much later. A thread can start only once. | `run()` executes on the new thread and terminates; no reuse. | Capture at constructor association or `start`, install around `run`. `start` alone cannot replace the already stored target without deeper instrumentation. | Unsupported. |
| `Executor` | `execute(r)` is the submission boundary. | Implementation later calls the command; pools reuse workers. | Wrap the command at each `execute` invocation; restore in wrapper `finally`. | Proven only for call sites symbolically owned by `Executor`. |
| `ExecutorService` | `submit` creates/returns a `Future`; `invokeAll`/`invokeAny` submit collections. | Usually adapts work to internal `FutureTask`; workers are reused. | Wrap each source `Runnable`/`Callable` before `submit`; cancellation before start means wrapper never installs anything. | The three `submit` overloads are proven at `ExecutorService` call sites. Bulk methods unsupported. |
| `ThreadPoolExecutor` | `execute` enqueues or starts a worker; `submit` is inherited from `AbstractExecutorService` and eventually calls `execute`. | `runWorker` repeatedly takes tasks. Hooks such as `beforeExecute` are too late to know submitter context unless attached to the task. | Wrap at public submission, never bind context to `Worker` or `runWorker`. | Works when referenced through supported interfaces; concrete-owner call sites unsupported. |
| `ForkJoinPool` | `execute`/`submit`/`invoke` push a `ForkJoinTask`; tasks may fork internally. | Work stealing means execution can move among workers; task objects have lifecycle/status and may be reinitialized. | A per-submission wrapper/task association is required. Worker locals are unsafe; `fork()` needs its own capture rule. | Unsupported. |
| `CompletableFuture` | Async factory/stage creation constructs internal completion tasks, often inside JDK code; an explicit executor is passed separately. | Internal `ForkJoinTask`-like objects call executor `execute`; dependent stages and cancellation have independent semantics. | Instrument CF stage creation or the actual executor implementation boundary. Rewriting only user executor call sites cannot see it. | Unsupported, including explicit executors. |
| `ScheduledExecutorService` | `schedule*` captures a one-shot or periodic command plus timing. | Periodic tasks execute repeatedly, possibly on different reused workers, until cancelled. | One-shot work can use submission context. Periodic work needs an explicit lifetime policy: retaining a finished test indefinitely is unsafe/noisy. | Unsupported. |
| Virtual threads | `Thread.startVirtualThread`, builders, and virtual-thread-per-task executors create/start logical threads; carrier platform threads are reused invisibly. | A virtual thread itself is not reused; carrier thread-local details must not be used as identity. | Bind around the virtual task or supported executor submission. Never bind to carrier threads. | Unsupported as a direct API; an `ExecutorService`-typed per-task executor call would be rewritten on a newer runtime, but is not tested on this Java-17 build. |
| Reused pooled threads | Creation is pool lifecycle, unrelated to individual submissions. | Many unrelated tasks serially occupy one worker. | Per-task install/restore only. Thread creation/inheritance is categorically the wrong lifetime. | Reuse safety proven for supported calls. |

## Candidate evaluation

| Candidate | Correctness and leakage | Nested/reuse/cancel/exception | Cost and compatibility | ASM complexity and existing `ThreadLocal` interaction | Assessment |
|---|---|---|---|---|---|
| `InheritableThreadLocal` | Captures at **thread creation**, not task submission. Pooled workers commonly predate tests and retain inherited values; this can both miss work and leak Test A. | Accidental inheritance can reach child threads, but task reuse and pool reuse are wrong. Cancellation does not repair stale state. | Available on all relevant JDKs; hidden copying cost at thread creation. Virtual threads make indiscriminate inheritance especially undesirable. | Easy substitution, fundamentally wrong semantics. | Reject. |
| Wrapping `Runnable`/`Callable` at submission | Matches the required invariant when every submission boundary is covered and wrappers restore in `finally`. | Nested submissions naturally capture installed parent context. Each reuse gets a new wrapper. Pre-start cancellation installs nothing; exceptions pass through unchanged. | One wrapper plus bounded thread-local operations per active-context submission/execution; Java 17 compatible. | Moderate. Existing `ThreadLocal` remains the method-entry lookup and wrapper scope restores it. | Chosen POC mechanism. |
| Executor implementation instrumentation | Can cover callers in libraries/JDK code at the real queue boundary if all implementations/adapters are covered. | Good for nesting and reuse when it wraps commands, not workers. Must avoid double wrapping and account for rejection/adaptation. | Hot boundary; bootstrap/module/class-loading and already-loaded-class compatibility matter. | High: bootstrap visibility, retransformation, concrete/custom executor coverage, shaded-agent loading. | Strong next direction, not proven here. |
| `Thread.start` instrumentation | Useful only for one-shot threads; capture is at start rather than target creation. | No pooled-thread reuse, but nested starts work only with a way to install around `run`. Cancellation is not a Thread concept. | Broad JDK compatibility, but `Thread` is normally loaded before `premain`. | High-risk bootstrap transformation/retransformation; cannot safely solve executors. | Not a generic executor solution. |
| `ForkJoinTask` instrumentation | A task association could survive work stealing, but fork/join has specialized state, internal subclasses, helping, and reinitialization. | Nested `fork` needs capture; task reuse after `reinitialize`, cancellation, and exceptional completion need exact lifecycle handling. | Hot-path overhead and JDK-internal compatibility risk. | High bootstrap and verifier complexity; a plain `Runnable` wrapper is insufficient for all APIs. | Separate research required. |
| `CompletableFuture` instrumentation | Stage-level capture can be correct, but capture policy differs for dependent stages registered by different threads. | Must define which registration/completion context wins; cancellation/exception chains must remain exact. | Version-sensitive JDK internals and potentially high stage counts. | High; public overload call-site instrumentation is possible but incomplete, while internals are unstable. | Separate research required. |
| JDK context propagation | Java 17 has no general executor context-capture facility. Java 25 `ScopedValue` is permanent, while inheritance into `StructuredTaskScope` is limited to structured child tasks and that concurrency API remains preview. | Lexical restoration and structured nesting are excellent; arbitrary legacy executor tasks are not automatically propagated. | Not usable by this Java-17-targeted agent without a multi-release/reflection strategy. | Would complement rather than automatically replace submission instrumentation. | Relevant future option, not a solution for legacy pools. |
| Scoped values alone | A scoped binding is per-thread and lexically bounded. It does not jump an arbitrary `Executor` boundary by itself. | Rebinding restores automatically on return/throw; structured children inherit safely. Detached work is intentionally outside the model. | Final in JDK 25; unavailable on Java 17. | Low for code that controls both scopes, insufficient for transparent generic interception. | Do not use as sole mechanism. |

The JDK-25 status and structured inheritance constraints are documented in the [Java 25 `ScopedValue` API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ScopedValue.html) and [Java 25 `StructuredTaskScope` API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html).

## POC implementation

`ExecutorCallSiteTransformer` rewrites supported invocation arguments to `RuntimeHooks.wrap(...)`. It intentionally transforms user/test code independently of application method-entry inclusion, while excluding bootstrap code, STP runtime/agent code, and both original and shaded ASM packages. It does not instrument an executor loop or recursively instrument STP.

The exact covered bytecode shapes are:

```text
Executor.execute(Runnable)
ExecutorService.submit(Runnable)
ExecutorService.submit(Runnable, Object)
ExecutorService.submit(Callable)
```

The symbolic owner qualification matters. `ScheduledExecutorService.submit(...)`, `ThreadPoolExecutor.execute(...)`, a custom narrower interface, reflection, method handles, and a framework/JDK class excluded from transformation are not covered even if runtime dispatch eventually reaches the same implementation.

`RuntimeHooks.wrap` fails open: no installed runtime, no active test, or an internal collector failure returns the original task. Null remains null so the executor retains responsibility for its normal validation. `RuntimeContextService.wrap` is the strongly checked internal API.

The wrapper neither catches nor translates task exceptions and returns the Callable's exact result. A submitted wrapper changes the queue element identity, an unavoidable property of wrapper designs; the `Future` result and exception cause identities are preserved. Code that depends on executor queue identity or removes the original Runnable by identity is therefore an unresolved compatibility issue.

## Fixtures and results

The isolated agent process runs with `-Xverify:all`. Exact JSON test sections are asserted, including absence from every wrong test section.

| Fixture | Expected and observed result |
|---|---|
| Single-thread executor | `single -> AsyncApplication#single` |
| Same worker, sequential tests | `reuse-a -> reusedA`; `reuse-b -> reusedB`; neither bucket contains the other's method. |
| Fixed pool | `fixed -> fixedOne, fixedTwo` across two workers. |
| Callable | `callable -> callable`; the exact returned object/value is preserved. |
| Nested submission | `nested -> nestedOuter, nestedInner`; the inner submission captures the outer task's installed test. |
| Failing task | `failure -> failing`; the original throwable object is the `ExecutionException` cause. The next no-context task is global, proving cleanup. |
| Submitted during A, executes after A ends | `delayed` is a `LATE_EVENT` in A's bucket, never an event for a later test. |
| No active test | `unrelated` is global `NO_ACTIVE_TEST`. |
| Cancellation | A queued task cancelled before execution produces no application hit and installs no context. |
| Explicit-executor CompletableFuture | Not run as a positive fixture because this mechanism does not support the JDK-internal submission; explicitly classified unsupported rather than producing a misleading test. |

Runtime-level tests additionally prove restoration of a pre-existing worker context after a captured task, preservation after exceptions, delayed Callable results, and two-level nesting. Task reuse is safe by construction because wrapping occurs on every supported invocation; the same source task submitted by A and B produces distinct wrappers. Rejection occurs before execution and therefore installs no worker context, though rejection identity was not separately fixture-tested.

Verification command:

```bash
./gradlew :stp-runtime:test :stp-agent:test :stp-junit-adapter:test
```

## PetClinic relevance

Round 2's retained `fixed-1-asm.json` has 23 distinct global `NO_ACTIVE_TEST` application methods. They are application/bootstrap constructors and configuration callbacks observed before a JUnit leaf became active. There is no retained worker-thread name in the schema, so thread identity cannot be reconstructed directly; classification uses the method identities, counts, and Round-2 temporal comparison evidence. None is evidence of executor or HTTP-server work in the three current tests.

All current unattributed identities classify as **startup/global lifecycle**:

- `PetClinicApplication#<init>`
- `BaseEntity#<init>` (six hits), `NamedEntity#<init>` (three), `Person#<init>` (two)
- `Owner#<init>`, `Pet#<init>`, `PetType#<init>`, `Visit#<init>`, `Specialty#<init>`, `Vet#<init>`
- `OwnerController#<init>`, `PetController#<init>`, `VisitController#<init>`, `CrashController#<init>`, `WelcomeController#<init>`, `VetController#<init>`
- `PetTypeFormatter#<init>`
- `CacheConfiguration#<init>` and `petclinicCacheConfigurationCustomizer`
- `WebConfiguration#<init>`, `addInterceptors`, `localeChangeInterceptor`, and `localeResolver`

Classification totals from the existing evidence:

| Classification | Distinct methods | Interpretation |
|---|---:|---|
| JVM executor propagation can solve it | 0 | No observed async application method in the retained three-test run. |
| HTTP/request boundary required | 0 | These tests do not show application execution on an independently attributed server request thread. |
| Startup/global lifecycle | 23 | All listed identities. They should remain outside leaf attribution under the current ASM lifecycle policy. |
| Unknown | 0 | The retained identities are explainable; thread-name evidence would still improve future audits. |

Thus Round 3 fixes no demonstrated PetClinic edge in the current sample. It proves a generic safety property needed by other suites. HTTP/server-thread correlation remains a separate causal-boundary problem and was intentionally not attempted.

## Supported and unsupported APIs

**Supported and tested:** Java-17 `Executor.execute(Runnable)` and all three `ExecutorService.submit` forms when the caller bytecode names exactly those interfaces; `newSingleThreadExecutor` and `newFixedThreadPool` instances used through `ExecutorService`; sequential pool reuse; nested supported submissions.

**Unsupported:** direct `Thread` and `Thread.start`; concrete-owner/custom-interface executor calls; `ExecutorService.invokeAll/invokeAny`; every `ScheduledExecutorService.schedule*` operation and periodic policy; direct `ThreadPoolExecutor` boundaries; `ForkJoinPool`/`ForkJoinTask`; common-pool and explicit-executor `CompletableFuture`; virtual-thread creation APIs and untested virtual-thread executors; structured concurrency; reactive callbacks; request/server boundaries; reflection/method-handle submission; tasks submitted from untransformed JDK/framework code.

The next generic experiment should instrument a true executor implementation boundary without bootstrap leakage or semantic changes, then prove custom executors and `CompletableFuture` explicit-executor submission. Until that succeeds, the collector must report these APIs as attribution-incomplete.
