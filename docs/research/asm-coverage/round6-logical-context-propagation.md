<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# Round 6: logical test-context propagation

## Decision

**LOGICAL TEST-CONTEXT PROPAGATION PROVEN FOR THE TESTED BOUNDARIES.**

Round 6 proves submission-time capture and execution-time attach/restore across the executor and
`CompletableFuture` call sites listed as **PROVEN** below. It does not prove universal JVM asynchronous
attribution. Unsupported APIs, untransformed callers, and untested overloads remain outside the result.

This round did not change selector semantics, JaCoCo mapping, the map schema, sharding, or publication. It is
the implementation and validation increment after Round 5's full-PetClinic experiment; no new PetClinic or
broader mapping experiment is part of this result.

## Baseline and environment

| Item | Verified value |
| --- | --- |
| Branch | `research/asm-codex` |
| Baseline commit | `64c2636fe4f9224e7f3af4c9677c38fa38594115` (`Document Round 5 PetClinic ASM validation`) |
| Current state | `HEAD` is still the baseline commit; Round 6 is present as modified and untracked working-tree files |
| JDK | OpenJDK `17.0.19` Homebrew build `17.0.19+0`, 64-bit Server VM |

The result was derived from the complete working-tree diff against that baseline, including the untracked
`TestExecutionContext`, overload-verification fixture, and context-propagation design note.

## Implemented model and lifecycle

`TestExecutionContext` is an immutable record containing the submitting test's `TestIdentity`.
`RuntimeContextService` now stores this logical context, rather than a bare identity, in its ordinary
`ThreadLocal`. `beginTest` creates and installs a context after registering the test bucket; `endTest` finishes
the bucket, removes the context, and records the identity in the existing same-thread `lastFinished` marker.

Propagation has four steps:

1. At a supported call site, `RuntimeHooks.wrap`, `wrapSupplier`, or `wrapFunction` asks the registered runtime
   service to capture the current immutable context.
2. With an active context, the service allocates a fresh wrapper for that submission. With no active context,
   it returns the original task object unchanged.
3. When the wrapper executes, it saves the worker's prior context and `lastFinished`, attaches the captured
   context, and clears `lastFinished` while user code runs.
4. A `finally` block restores the exact prior context and finished marker for normal return, exception, or
   error. Callable/Supplier/Function results and throwable identity are not translated.

Nested supported submissions capture the context currently attached by their parent. Context therefore
follows a logical task and its supported descendants rather than a pool worker. Concurrent submitter threads
retain independent thread-local contexts.

## Exact ASM interception strategy

`ExecutorCallSiteTransformer` rewrites invocation arguments in eligible, non-bootstrap caller bytecode; it
does not instrument JDK executor implementations, queues, worker loops, or `ForkJoinTask` internals.

For executor calls, it first requires one of these exact descriptors:

* `execute(Runnable)V`;
* `submit(Runnable)Future`;
* `submit(Runnable,Object)Future`;
* `submit(Callable)Future`;
* the three corresponding `ForkJoinTask`-returning `submit` descriptors.

It then walks the symbolic owner's class-file superclass/interface hierarchy, through the transforming class
loader's resources and without loading the class, and rewrites only an owner proven to reach `Executor` or
`ExecutorService`. This retains Round 4 coverage for interface, concrete, inherited, and custom executor
owners when their hierarchy bytes are resolvable. Unknown hierarchies remain unchanged and are diagnosed as
`executor-attribution-incomplete`.

For `CompletableFuture`, it matches the exact JDK owner and exact one- and two-argument descriptors for
`runAsync(Runnable[,Executor])`, `supplyAsync(Supplier[,Executor])`,
`thenApplyAsync(Function[,Executor])`, and `thenRunAsync(Runnable[,Executor])`. It inserts the appropriate
wrapper call around the callback argument, using `SWAP` around the explicit executor argument where required.
Common-pool support therefore comes from wrapping the callback at registration, not from general ForkJoin
instrumentation.

Round 5 found that descriptor-prefix matching treated Spring `AsyncTaskExecutor.execute(Runnable,long)` as
`execute(Runnable)`, inserted a wrapper against the top-of-stack primitive `long`, and produced a
`VerifyError`. Round 6 replaces that prefix test with exact descriptor equality. The isolated
`ExecutorOverloadVerificationFixtureMain` exercises a custom `execute(Runnable,long)` path under full agent
instrumentation and exits successfully, proving the verifier regression is fixed for that defect.

Bootstrap/JDK, Gradle infrastructure, STP, agent, and ASM implementation packages remain excluded. When agent
`debug=true`, the runtime optionally writes capture, task identity, captured test, thread, attach, and
restore/cleanup diagnostics to standard error; this branch is off by default.

## Proven behavior

Only behavior exercised by an actual test is marked **PROVEN**.

| Mechanism or invariant | Status | Test evidence |
| --- | --- | --- |
| `Executor.execute(Runnable)` | **PROVEN** | isolated agent fixture `execute-runnable` |
| `ExecutorService.submit(Runnable)` | **PROVEN** | `single`, `submit-runnable`, fixed-pool and reuse cases |
| `ExecutorService.submit(Callable)` | **PROVEN** | `callable` and reused-callable cases; returned object identity checked |
| `ExecutorService.submit(Runnable,Object)` | **KNOWN LIMITATION: IMPLEMENTED, NOT PROVEN IN ROUND 6** | exact descriptor is matched, but no current execution fixture invokes this overload |
| Reused worker threads | **PROVEN** | sequential tests on one worker have isolated buckets; later no-context work is global |
| Nested submissions | **PROVEN** | outer and inner work are attributed only to the parent logical test |
| Reuse of the same `Runnable` | **PROVEN** | the same object is submitted under `same-runnable-a` and `same-runnable-b` |
| Reuse of the same `Callable` | **PROVEN** | the same object is submitted under `same-callable-a` and `same-callable-b` |
| Exception restoration | **PROVEN** | exact throwable identity is preserved and following worker work is unattributed |
| Restoration over an existing worker context | **PROVEN** | runtime unit test restores the worker context after a captured task |
| No-context execution | **PROVEN** | later worker work is `NO_ACTIVE_TEST`, absent from completed test buckets |
| Late execution | **PROVEN** | delayed work is retained as `LATE_EVENT` under the submitting test |
| Overlapping logical contexts | **PROVEN** | two concurrent submitters/workers record only their respective events |
| Cancellation before execution | **PROVEN** | a queued submitted task is cancelled and its body must not execute |
| Rejection | **PROVEN** | rejection remains a submitter-side `RejectedExecutionException` |
| `CompletableFuture.runAsync` | **PROVEN** | explicit executor and common pool |
| `CompletableFuture.supplyAsync` | **PROVEN** | explicit executor and common pool; result identity checked |
| `CompletableFuture.thenApplyAsync` | **PROVEN** | explicit executor and common pool; result identity checked |
| `CompletableFuture.thenRunAsync` | **PROVEN** | explicit executor and common pool |
| `ForkJoinPool.execute(Runnable)` | **PROVEN** | dedicated pool fixture `forkjoin-execute` |
| `ForkJoinPool.submit(Callable)` | **PROVEN** | dedicated pool fixture `forkjoin-submit`; result identity checked |
| `ForkJoinPool.submit(Runnable)` | **KNOWN LIMITATION: IMPLEMENTED, NOT PROVEN IN ROUND 6** | exact covariant descriptor is matched, but no current execution fixture invokes it |
| `ForkJoinPool.submit(Runnable,Object)` | **KNOWN LIMITATION: IMPLEMENTED, NOT PROVEN IN ROUND 6** | exact covariant descriptor is matched, but no current execution fixture invokes it |

The isolated shaded-agent fixture is run with `-Xverify:all` and asserts exact per-test method ownership for
the named cases. Runtime unit tests separately exercise capture, nesting, reuse, exception cleanup, restoration
of a pre-existing context, no-context behavior, and overlap.

## Unsupported boundaries

The following are **UNSUPPORTED**, based on the absence of an interception rule and the explicit current
architecture boundary:

* raw `Thread` construction/start and virtual-thread creation APIs;
* direct `ForkJoinTask.fork()` and direct `ForkJoinTask.invoke()`;
* `ForkJoinTask` reuse or reinitialization;
* `ScheduledExecutorService` scheduling and periodic operations;
* reflective or method-handle invocation of submission APIs; and
* callers loaded before transformer installation, because the agent does not retransform them.

These labels describe propagation, not method-entry visibility: an application method reached through
reflection can still be observed by method-entry instrumentation, but a reflective executor submission bypasses
the rewritten caller instruction and does not capture context. Likewise, common-pool `CompletableFuture`
coverage does not imply support for arbitrary ForkJoin work. Java 17 cannot validate virtual-thread APIs in
this build.

## Memory, cancellation, and retained references

Round 6 does **not** use an external `task -> context` map. Every active-context submission creates a fresh
wrapper holding the immutable context and original task. This is why the same Runnable or Callable can be
submitted repeatedly under different tests without equality/identity collisions. No-context submissions
allocate no wrapper.

After a wrapper returns or throws, STP has no independent reference to the wrapper, task, or context. A Future
cancelled before execution never attaches its nested wrapper. That wrapper may remain reachable through the
normal executor/Future queue state until the JDK implementation removes or purges it, but there is no STP
registry entry to clean up and no separate STP retention table. Direct `execute` does expose wrapper identity
to identity-sensitive queues, rejection handlers, removal calls, casts, or custom executors; this remains a
known semantic limitation of wrapper-based propagation.

## Late execution and publication

Work captured under Test A and executed after `endTest(A)` still attaches A. Because A's aggregator bucket is
already finished, `RuntimeEventAggregator.record` stores the event in that test's unattributed collection with
reason `LATE_EVENT`; it is not a normal method hit and cannot be reassigned to a later test.

Publication/completeness behavior is unchanged. Ending a test does not join, count, or otherwise wait for
arbitrary asynchronous descendants, and output publication does not introduce such a wait. Work that executes
after the final output snapshot can therefore be absent; Round 6 preserves ownership when observed, not a
structured-completion guarantee.

## Cost model

Every recorded instrumented event retains one `ThreadLocal` lookup of the current logical context. A supported
submission performs a current-context lookup; with an active context it allocates one wrapper. Execution reads
the prior current context and prior `lastFinished`, sets the captured context, removes the finished marker, and
restores both values with `set` or `remove` in `finally`. Supplier and Function wrappers have the same shape.

The no-context path returns the original task without wrapper allocation or attach/restore work. There are no
external-map lookups, inserts, removals, or cleanup operations. `ExecutorService.submit` and `ForkJoinPool`
retain their own normal task/Future allocations in addition to any STP source wrapper. Debug mode adds string
formatting and standard-error output at capture, attach, and restoration; it is disabled by default. No
benchmark claim is made.

## Validation

On the JDK above, `./gradlew test --rerun-tasks` completed with `BUILD SUCCESSFUL`: all 56 actionable tasks
executed. The generated JUnit XML contains 367 tests, zero failures, zero errors, and zero skipped tests across
39 test-suite files. This includes `stp-runtime`, `stp-agent`, and `stp-junit-adapter`, plus the repository's
other test projects. `git diff --check` also passes.

## Final classification

**PROVEN:** logical test context is captured per supported submission and correctly attached/restored for the
tested Executor, ExecutorService, CompletableFuture, and limited ForkJoinPool paths, including reuse, nesting,
failure, no-context, late, overlap, and cancellation cases.

**UNSUPPORTED:** raw and virtual threads, direct/reinitialized ForkJoinTask operations, scheduled executors,
reflective/method-handle submission, and preloaded callers.

**KNOWN LIMITATION:** support is exact-call-site and wrapper based, not universal. Three matched submit
overloads lack direct execution tests (`ExecutorService.submit(Runnable,Object)` and the two Runnable-based
covariant `ForkJoinPool.submit` overloads); wrapper identity is observable; and publication does not wait for
unstructured asynchronous descendants.
