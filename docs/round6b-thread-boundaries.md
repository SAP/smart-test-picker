<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# Round 6b: JVM thread and asynchronous boundaries

## Baseline and result

The working baseline is commit `64c2636fe4f9224e7f3af4c9677c38fa38594115` plus the uncommitted Round 6
changes listed by `git status`. Before Round 6b edits, `./gradlew test` passed all modules, including the existing
executor, CompletableFuture, and limited ForkJoin integration fixture.

Round 6b extends the caller transformer rather than replacing the proven design. Exact scheduled-executor calls
are changed to static `RuntimeHooks` bridges; each bridge wraps the task at scheduling and immediately delegates
to the original `ScheduledExecutorService`. Common raw `Thread` constructors wrap their Runnable argument before
construction. JDK 21 virtual-thread calls wrap the Runnable immediately before `startVirtualThread` or builder
`start`. Attach and restoration still happen inside the same Round 6 wrappers and their `finally` blocks.

## Observed compatibility matrix

| Mechanism | JDK | Status | Capture point | Execution point | Test |
| --- | --: | --- | --- | --- | --- |
| `Executor.execute` | 17 | PROVEN | existing caller rule | existing Runnable wrapper | `executorCallSitesPropagateExactSubmittingTestWithoutPoolLeakage` |
| `ExecutorService.submit` | 17 | PROVEN | existing caller rule | existing Runnable/Callable wrapper | same |
| `CompletableFuture` | 17 | PROVEN | existing stage registration rule | callback wrapper | same |
| Scheduled executor one-shot | 17 | PROVEN | exact `schedule` bridge | Runnable/Callable wrapper | `scheduledExecutorsAndRawThreadsPropagateThroughRealAsmInstrumentation` |
| Scheduled executor periodic | 17 | PROVEN | exact periodic bridge | same wrapper on every invocation | same |
| raw `Thread` common Runnable constructors | 17 | PROVEN | transformed constructor call | Runnable wrapper in `Thread.run` | same |
| `Thread` subclass overriding `run` | 17 | UNSUPPORTED | none | subclass bypasses target Runnable | same, negative observation |
| virtual thread direct/builder | 21 | PROVEN | exact start call | Runnable wrapper in virtual thread | `jdk21VirtualThreadApisPropagateThroughRealAsmInstrumentation` |
| virtual-thread executor | 21 | PROVEN | existing executor caller rule | Runnable wrapper in virtual thread | same |
| `ForkJoinTask.fork` | 17 | UNSUPPORTED | none | direct task execution has no wrapper boundary | boundary negative fixture |
| `ForkJoinTask.invoke` | 17 | UNSUPPORTED as propagation boundary | none | may run inline, so ambient context is incidental | boundary analysis |
| `ForkJoinPool.invoke/submit(ForkJoinTask)` | 17 | UNSUPPORTED | none | reusable task cannot be safely decorated | boundary negative fixture |
| reflection | 17 | UNSUPPORTED | transformed caller sees `Method.invoke`, not submission | none | architectural inspection |
| MethodHandle | 17 | UNSUPPORTED | transformed caller sees signature-polymorphic invoke | none | architectural inspection |
| preloaded callers | 17 | UNSUPPORTED | transformer installed without retransformation | none | agent lifecycle inspection |

`PROVEN` rows execute a real `-javaagent` process and record an ASM-instrumented application method under the
originating `TestIdentity`. The JDK 21 test compiles a JDK-21-only fixture dynamically, so production and normal
test code retain class-file/JDK 17 compatibility. It uses `JDK21_HOME` when supplied and the Homebrew JDK 21
location in this validation environment; it skips only when no JDK 21 is available.

## Semantics and lifecycle

A scheduled periodic task has one logical owner: the test active when it was scheduled. Every invocation installs
that snapshot and restores the worker state afterward. Cancellation does not require an STP registry cleanup,
because STP has no task map; normal scheduler reachability owns the wrapper. If it runs after `endTest`, the owner
remains unchanged and the aggregator records `LATE_EVENT`. A periodic task can outlive a test indefinitely if
application code never cancels it; Round 6b intentionally adds no descendant waits or publication policy.

Raw threads capture at construction, matching the moment a Runnable becomes that Thread's target. Wrapping at
`start` alone could not attach around an overridden `run`, and instrumenting `Thread.run` would require safe
bootstrap injection. Each construction gets a fresh wrapper, so reuse of the same externally supplied Runnable
under tests A and B does not create a permanent Runnable association. A Thread is single-use; after its target
returns, the wrapper restores/removes context in `finally` and remains reachable only according to the Thread's
ordinary lifecycle. An overriding Thread subclass does not execute the constructor target and is therefore
explicitly unsupported.

Direct `ForkJoinTask` support was rejected for this increment. The task object is both the execution object and a
potentially reusable object after `reinitialize`; wrapping it changes concrete identity/type, while a permanent
task-to-context map is lifecycle-unsafe. Correct coverage needs execution-boundary instrumentation inside
ForkJoinTask/ForkJoinPool plus reuse-aware metadata cleanup and bootstrap visibility. `invoke` can execute inline,
which may appear attributed solely because it never crossed a thread boundary; that is not claimed propagation.

## Architecture and remaining gaps

The current caller-rewriting model remains sufficient for ScheduledExecutorService, common raw/virtual Thread
Runnable entry points, and virtual-thread executors. It also avoids changing JDK internals and preserves JDK 17.
A hybrid implementation-boundary extension is required for direct reusable ForkJoinTask operations, overridden
Thread subclasses, reflection/MethodHandles, and JDK-internal submissions. These are architectural gaps, not just
missing descriptors.

Preloaded callers remain unsupported. `StpAgent` registers both transformers with `canRetransform=false`; the
agent manifest declares `Can-Retransform-Classes: false`. Thus checking
`Instrumentation.isRetransformClassesSupported()` cannot enable retransformation for this artifact. Blanket
retransformation was not enabled: a safe version would first opt in through the manifest/registration, select only
modifiable non-JDK classes containing supported call sites, and measure startup/verification impact.

Reflection and MethodHandle callers expose `Method.invoke` or signature-polymorphic handle invocation in their
bytecode, not the eventual executor method. Inferring arbitrary targets there is unsafe. Supporting them requires
intercepting known executor implementation boundaries (a hybrid design), with bootstrap/classloader handling and
duplicate-wrap protection. No such general interception was added.

Wrappers remain visible to custom executors. `remove(originalRunnable)`, identity-sensitive queues, custom casts,
rejection handlers that inspect the submitted object, and similar code can observe the wrapper. The scheduled
bridges also pass wrappers to the scheduler. Preserving original identity while retaining per-submission ownership
would require side metadata and execution interception, with harder cancellation and cleanup semantics; Round 6b
does not trade correct attribution for that speculative redesign.
