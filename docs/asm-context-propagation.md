<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# ASM logical test context propagation

## Baseline inventory

This change starts from branch `research/asm-codex` at commit
`64c2636fe4f9224e7f3af4c9677c38fa38594115` (`Document Round 5 PetClinic ASM validation`).

Before this change, `RuntimeContextService` stored the active `TestIdentity` in a plain `ThreadLocal`.
`beginTest` registered an aggregator bucket, cleared the same-thread `lastFinished` marker, and set the active
identity. `endTest` finished the bucket and, in `finally`, removed the active identity and set `lastFinished`.
ASM-instrumented methods called `RuntimeHooks.methodHit`; the installed `AgentRuntime` sink resolved the method
ID and called `RuntimeContextService.record`, which read the thread-local identity and recorded through the
existing `RuntimeEventAggregator`. With no active identity, a same-thread post-test event was `LATE_EVENT` for
`lastFinished`; otherwise it was global `NO_ACTIVE_TEST`. An unknown identity known neither to the current
scope nor aggregator was `UNKNOWN_CONTEXT`.

Rounds 1–5 already supplied the architecture review, ASM method-entry transformer, deterministic method IDs
and catalog, runtime/JUnit lifecycle integration, class filters and marker idempotence, shaded agent packaging,
JaCoCo coexistence checks, initial Runnable/Callable wrappers, executor-owner hierarchy resolution, isolated
JVM propagation fixtures, and PetClinic validation. This change retains those paths. It fixes the Round 5
descriptor-prefix defect by recognizing only exact supported descriptors.

## Current problem

A thread-local alone associates events with a physical thread. Pools create and reuse worker threads
independently of JUnit lifecycle threads, so an unpropagated worker either has no test or can retain unrelated
thread state. Ownership instead has to follow each logical task from submission to execution.

## Architecture

`TestExecutionContext` is an immutable snapshot containing the current `TestIdentity`. The local current value
remains a `ThreadLocal`; propagation is implemented by one wrapper allocated for each submission made with an
active context.

```text
JUnit Test A
    |
    +-- submit / register async stage
          capture TestExecutionContext(A)
              |
              v
          worker thread
              |
              +-- save previous worker context
              +-- attach A
              |
          ASM-instrumented application code
              |
              +-- existing RuntimeHooks -> RuntimeEventAggregator
              |
              +-- finally restore previous worker context
```

The wrapper also temporarily clears and then restores the worker's `lastFinished` marker. Restoration happens
in `finally` for return, exception, or error. A nested submission sees the attached context and captures it.
When no context is active, hooks return the original task object and attribution continues through the existing
unattributed behavior; no owner is guessed.

## Instrumentation points

`ExecutorCallSiteTransformer` transforms eligible non-bootstrap caller classes. It does not modify executor
implementations or JDK worker loops.

The actual matched invocation descriptors are:

* `Executor.execute(Runnable)`;
* `ExecutorService.submit(Runnable)`, `submit(Runnable,Object)`, and `submit(Callable)` returning `Future`;
* the equivalent `ForkJoinPool.submit` overloads returning covariant `ForkJoinTask`;
* `CompletableFuture.runAsync(Runnable[,Executor])`;
* `CompletableFuture.supplyAsync(Supplier[,Executor])`;
* `CompletableFuture.thenApplyAsync(Function[,Executor])`; and
* `CompletableFuture.thenRunAsync(Runnable[,Executor])`.

Executor symbolic owners are accepted only when class-file hierarchy inspection proves they implement
`Executor` or `ExecutorService`. CompletableFuture matches its exact JDK owner and exact descriptors. The
transformer calls `RuntimeHooks.wrap`, `wrapSupplier`, or `wrapFunction` on the functional argument at the
caller. Bootstrap/JDK, Gradle runner infrastructure, STP, agent, and ASM packages remain excluded, preventing
recursive instrumentation and avoiding hooks from a build-worker classloader that cannot resolve agent runtime.

For ordinary `AbstractExecutorService.submit`, the source wrapper is passed to the JDK, which creates a distinct
`FutureTask` for that submission and schedules it through `execute`. For direct `execute`, the wrapper itself is
scheduled. No external `Map<Runnable,...>` exists.

## Supported execution mechanisms

| Mechanism | Status | Instrumentation point | Test |
| --- | --- | --- | --- |
| `Executor.execute(Runnable)` | PROVEN | exact caller invocation; Runnable wrapper | `executorCallSitesPropagateExactSubmittingTestWithoutPoolLeakage`, fixture `execute-runnable` |
| `ExecutorService.submit(Runnable)` | PROVEN | exact caller invocation before JDK `FutureTask` creation | same test, fixture `submit-runnable` |
| `ExecutorService.submit(Callable)` | PROVEN | exact caller invocation before JDK `FutureTask` creation | same test, fixtures `callable`, `same-callable-a/b` |
| CompletableFuture explicit executor | PROVEN | exact `runAsync`, `supplyAsync`, `thenApplyAsync`, `thenRunAsync` caller invocation | same test, `cf-explicit-*` fixtures |
| CompletableFuture common pool | PROVEN | same callback registration points; wrapped callback later runs in common pool | same test, `cf-common-*` fixtures |
| ForkJoinPool | PARTIALLY COVERED | exact `execute(Runnable)` and `submit` executor overloads | same test, `forkjoin-execute`, `forkjoin-submit` |
| raw `Thread` | UNSUPPORTED | none | not claimed |
| virtual threads | UNSUPPORTED | none; build/runtime JDK 17 has no virtual-thread APIs | not claimed |

ForkJoin coverage does not include direct `ForkJoinTask.fork()`, `invoke()`, task reuse/reinitialization, or work
created inside JDK ForkJoin code. Common-pool CompletableFuture is proven because the registered callback is
wrapped before JDK scheduling, not because arbitrary ForkJoinTask propagation is supported.

## Identity, cancellation, and lifecycle

Every active-context submission creates a fresh wrapper even when the same original Runnable or Callable is
submitted by tests A and B. Each wrapper holds exactly its own immutable context and original task. There are
no equality-based associations, global task maps, or STP cleanup tables. Once execution returns, STP retains no
reference to either object. If a submitted Future is cancelled before execution, the wrapper is never attached;
it remains reachable only through normal JDK executor/Future queue state until that implementation removes or
purges the cancelled task. STP adds no independent lifetime or unbounded strong-reference leak.

Work submitted by A and executed after `endTest(A)` still carries the captured A context. The current aggregator
has already marked A finished, so its method event is recorded as `LATE_EVENT` under A, not as a normal method
hit and never under a later test. Whether publication should wait for late asynchronous work is unchanged and
is a separate completeness/lifecycle concern.

Wrapper identity remains observable to identity-sensitive executors: direct queue inspection, `remove(original)`,
or executor-specific casts/classification can see the wrapper. Rejection retains normal executor behavior. A
cancelled queued Future can remain in a JDK queue until its executor removes it; this is not an STP registry leak.

## Diagnostics and cost model

Agent `debug=true` enables context diagnostics for capture, task identity, captured test, submission/execution
thread, attach, restore, and cleanup. The debug branch is disabled by default.

The existing event path still performs one `ThreadLocal` current-context lookup for every recorded instrumented
method hit. An active asynchronous submission adds one current-context lookup and one wrapper allocation; a
no-context submission returns the original object without allocation. Execution adds current/previous
`ThreadLocal` reads and set/remove operations around the task. CompletableFuture Supplier and Function stages
have the same wrapper cost. There is no external map operation per submission or execution and no metadata-map
cleanup cost. JDK `submit` retains its normal `FutureTask` allocation in addition to the STP wrapper.

## Known limitations

* Only transformed call sites with the exact descriptors above propagate context. Reflection, method handles,
  preloaded callers, and unsupported scheduling APIs are not inferred.
* Scheduled executor one-shot and periodic APIs are supported at their exact caller descriptors. Periodic wrappers
  deliberately retain the scheduling test for every invocation; invocations after test completion remain
  `LATE_EVENT` and cancellation/lifetime remain the scheduler's responsibility.
* Raw platform threads are supported when transformed code invokes the common `Thread` constructors whose
  Runnable can be wrapped safely: `(Runnable)`, `(Runnable,String)`, `(ThreadGroup,Runnable)`, and
  `(ThreadGroup,Runnable,String)`. Thread subclasses overriding `run`, less common constructors with stack-wide
  primitive arguments, and threads constructed by preloaded/JDK code are unsupported.
* On JDK 21, exact transformed calls to `Thread.startVirtualThread(Runnable)` and
  `Thread.Builder.start(Runnable)` are supported without static JDK 21 linkage. The virtual-thread-per-task
  executor is covered by the existing `ExecutorService` rule.
* Direct ForkJoinTask operations, reactive/request boundaries, reflection, method handles, and preloaded callers
  are unsupported. ForkJoin executor-style Runnable/Callable overloads remain supported.
* Late submitted work retains the correct owner but is classified by the unchanged aggregator as `LATE_EVENT`;
  publication does not wait for arbitrary asynchronous descendants.
* Wrapper identity can be visible to custom identity-sensitive executors.
