<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# Bulk ExecutorService ownership in the unit-test ASM agent

This implements Task 2 on `feature/asm-codex-remote`. The prerequisite is the
ForkJoin implementation equivalent to `fd5e57c` (already present as `a066f3e`) plus
its tested bootstrap extension, preserved in `b3a27e2`. Remote STP, selection and
fragment schemas are unchanged. There is no OpenTelemetry dependency.

## Supported boundaries

All four standard erased descriptors are supported at transformed call sites:

```java
ExecutorService.invokeAll(Collection)
ExecutorService.invokeAll(Collection, long, TimeUnit)
ExecutorService.invokeAny(Collection)
ExecutorService.invokeAny(Collection, long, TimeUnit)
```

The symbolic owner must resolve to `ExecutorService` using class-file hierarchy
inspection. This includes standard ThreadPoolExecutor/ForkJoinPool and custom
implementations. Unknown hierarchy produces `bulk-executor-attribution-incomplete`
and leaves the call unchanged; unrelated classes with similarly named methods
are not transformed. `ForkJoinTask.invokeAll` is a different API and is not added.

The original receiver, invocation opcode (including a custom implementation's
`super.invokeAll`), timeout, unit and result descriptor remain unchanged. Timed
calls spill/reload only their trailing arguments to reach the collection operand.
The executor still controls scheduling, results, futures, validation, interruption,
rejection, timeouts and cancellation.

## Capture and execution

`RuntimeHooks.wrapCallables` captures the current test or effective setup container
once, before entering the bulk API. `BulkCallables` exposes a read-only collection
view with the original iteration order. It does not eagerly traverse or validate
the collection, mutate it, or replace any original task. Null collections and
null elements reach the original executor validation. Each occurrence of an
original Callable receives a separate wrapper; the original Callable can be reused
under A and B without acquiring permanent context state.

Iteration wraps tasks when the executor consumes them, using the snapshot already
captured at the call site. Internal bulk/submit delegation recognizes these wrappers
and keeps their original ownership. Internal Runnable/FutureTask wrappers may nest,
but the captured Callable scope is authoritative and all scopes restore in reverse
order. There is no thread-wide suppression of unrelated nested submissions.

At execution the wrapper saves and restores the full runtime scope, including the
active test, last-finished marker, setup/container state and enclosing ForkJoin state.
Restoration is in `finally`, including Exception and Error. An empty submission owner
suppresses unrelated ambient worker ownership. Work captured under a completed test
produces `LATE_EVENT` for that test, not normal coverage or ownership under a later
test. A closed setup container remains late/incomplete. Unknown-consumer shared setup
retains its unsupported diagnostic.

Nested Runnable/Callable submissions also inherit a setup container already captured
on the worker, rather than consulting only the worker's local container stack.

### Interaction with ForkJoin bulk adapters

ForkJoinPool can execute its internal bulk Callable adapter inline while joining
on an already-owned worker. That internal adapter does not necessarily pass through
the public ForkJoinTask submission boundaries from Task 1. The bulk Callable wrapper
still has an exact submission owner.

Missing-submission diagnosis is therefore deferred to the adapter execution exit
unless an application hit occurs earlier. A recognized bulk wrapper establishes its
own captured scope and confirms that delegation. Hits outside that scope still
produce the missing-submission diagnostic; executions with neither observed submission
nor bulk delegation remain incomplete. A wrapper created only after the missing
handoff cannot validate that handoff; runtime and negative child-JVM tests retain
the incomplete diagnostic for that case. This avoids a false incomplete result without
borrowing the worker's ambient owner or enabling additional ForkJoin APIs.

## Evidence matrix

| Scenario | Evidence / expectation |
| --- | --- |
| Four overloads on standard pool, ForkJoinPool, custom executor | Exact method sets, ordered invokeAll futures, unchanged results and exception causes |
| Parallel A/B using the same Callable object and pool | Barrier proves overlapping execution; common method plus disjoint A-only/B-only methods |
| Sequential A/B with the same Callable and physical worker | Separate capture, correct return identity, worker cleanup |
| invokeAny winner, failed competitor and interrupted competitor | All three executed paths recorded; unfinished candidate cancelled |
| All invokeAny candidates fail, timed and untimed | ExecutionException retains the original failure type |
| Interruption of caller, all four overloads | InterruptedException, cleared caller interrupt status and cancelled running candidate |
| Zero timeout and blocked worker | Timed invokeAll returns cancelled future; timed invokeAny throws TimeoutException; queued body never recorded |
| Running candidate ignores cancellation, continues after A ends | Latch-controlled LATE_EVENT under A, no coverage under B |
| Partial rejection | First accepted task runs under its owner; second rejection preserved; unexecuted task has no hits |
| Nested submit/bulk; nested bulk on single-worker ForkJoinPool | Descendants retain owner; final fragment remains complete |
| Custom internal submit/execute and super delegation | No owner replacement or leaked worker context |
| No owner, including inline execution under unrelated ambient test | No invented attribution; ambient state restored |
| Exception/Error and worker reuse | Future exception semantics and afterExecute cleanup assertions |
| Open/closed setup container and nested setup work | Correct setup methods; closed container produces incomplete fragment |
| Empty/null collections, null entries/unit | Original API outcomes, no mutation of caller collection |
| Unknown owner hierarchy | Explicit transformer diagnostic, unchanged bytecode |

The real agent fixtures use `-Xverify:all`, the shaded agent in a child JVM, and
method recording only for `example.instrumented.*`. No fixture invokes the capture
hook explicitly. Coordination uses latches/barriers, not sleeps. A cancelled future
is not used as evidence that a running task has stopped: separate latches and pool
termination establish cleanup.

Sources:

- [BulkExecutorFixtureMain](../stp-agent/src/test/java/example/fixture/BulkExecutorFixtureMain.java)
- [MethodEntryTransformationTest](../stp-agent/src/test/java/com/sap/oss/smarttestpicker/agent/MethodEntryTransformationTest.java)
- [BulkCallSiteTransformerTest](../stp-agent/src/test/java/com/sap/oss/smarttestpicker/agent/BulkCallSiteTransformerTest.java)
- [BulkContextTest](../stp-runtime/src/test/java/com/sap/oss/smarttestpicker/runtime/BulkContextTest.java)

Tests decode the final fragment and compare descriptor-aware method sets for every
test. Supported scenarios require `collection.completed=true`; the separate closed
setup fixture requires `false`. Existing ForkJoin ambiguity tests remain unchanged.

## Validation

```sh
./gradlew :stp-runtime:test :stp-agent:test :stp-junit-adapter:test \
  --no-daemon --console=plain
```

Set `JAVA_HOME` to the selected JDK and `JDK21_HOME` for the existing virtual-thread
fixture. Verified on SapMachine **17.0.20.1** and **21.0.12.1**. Each run passed
**64 runtime + 53 agent + 21 JUnit adapter = 138 tests**, with zero failures,
errors or skips. The pre-change ForkJoin prerequisite regression also passed.

Preserved local evidence is under `stp-agent/build/bulk-proof/jdk17/` and `jdk21/`:

- `gradle.log` and module XML reports;
- `supported.json`, `supported-fragment.json`, `supported.log`: 39 test identities,
  exact expected descriptor-aware coverage, `collection.completed=true`;
- `closed.json`, `closed-fragment.json`, `closed.log`: closed-container late events and unobserved-parent diagnostics,
  `collection.completed=false` as required.

These extra child-JVM runs use `-Xverify:all`, the current shaded `stp-agent.jar`,
`includes=example.instrumented.`, `instrumentation=on` and the same fragment options
as `MethodEntryTransformationTest`. As in that test harness, fixture classes are
copied to a separate `fixture-classes` directory: the agent intentionally does not
record application coverage from Gradle's `classes/java/test` location. Evidence
and copied classes remain ignored build output, not tracked artifacts.

## Limits

- These are application call-site boundaries. Reflective/method-handle calls,
  bootstrap/excluded callers and callers loaded before transformation are not newly
  supported. Method-recording `includes=` does not itself define call-site eligibility.
- Custom executors must obey the ExecutorService contract. Collection/task object
  identity, mutation of the supplied collection, concrete collection casts and unusual
  covariant descriptors beyond the standard API shapes are not promised.
- Concurrent user mutation of a submitted collection remains outside the executor
  contract. STP does not make an unsafe user collection safe.
- Cancellation cannot stop a Callable that ignores interruption. Only methods actually
  executed are recorded; hits after the owner finishes stay late under that owner.
- No automatic ownership is inferred for completely unobserved handoffs. Existing
  unknown/shared setup, lifecycle and incomplete rules still apply.
