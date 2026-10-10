<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# ForkJoinTask ownership in the unit-test ASM agent

This extends the local test/runtime agent on `research/asm-codex-remote`, from
`a066f3e22e6dcca4b431946c34085cd8580b9017`. Remote STP, OTel dependencies,
selection semantics and fragment schemas are unchanged.

## Mechanism and ownership

`ForkJoinBootstrapTransformer` retransforms **only** `java.util.concurrent.ForkJoinTask`
and `ForkJoinPool`. Submission is captured in the actual JDK API method bodies:

- `ForkJoinTask.fork()`, `invoke()`;
- `ForkJoinPool.execute(ForkJoinTask)`, `submit(ForkJoinTask)`, `invoke(ForkJoinTask)`;
- completed, non-racing `ForkJoinTask.reinitialize()` releases a single-registration
  execution binding (see the reuse restrictions below).

Task construction is not a capture boundary. Original receivers, task objects,
arguments, scheduler, return values, cancellation and exception semantics are preserved.
Inherited methods and callers outside application transformation are covered, including
reflective invocation of these exact APIs. Overrides that bypass these bodies are not.

The single verified `ForkJoinTask.exec()Z` call inside `doExec()I` is enclosed in a
context scope. This covers application and JDK task implementations, including adapted
Runnable/Callable tasks and CountedCompleter. Restoration occurs **before** `doExec`
publishes ordinary completion, and before its original exception handler. A task may
explicitly complete itself inside `exec`; resetting while its scope is active remains
unsupported. Instrumented
application `compute`/`exec` method entries, including compiler bridges, are inside the scope.
No method hits are collected for JDK infrastructure, and no worker loop is modified.

Premain appends a tiny helper JAR containing only `ForkJoinBridge` and its JDK-only
handler interface to bootstrap. The runtime and ASM remain in the normal agent loader;
a narrow `java.base` module read edge permits the bridge call. The helper is reentrancy
protected and never throws collector failures into application code. The agent manifest
now enables retransformation. `instrumentation=off` does not install this mechanism.
No running JVM is dynamically attached to, and no JDK installation is modified.

Before changing bytes the transformer verifies the class, exact method descriptors,
and exactly one expected `doExec -> exec` call. Unsupported shapes/retransformation
failures report `forkjoin-*-incomplete` or `forkjoin-boundary-unverified` through agent
integrity errors, making the fragment incomplete. The tested bytecode versions are JDK
17 and 21; this is not a promise of compatibility with every future JDK or another agent.

The runtime uses **weak object identity**, not user `equals`/`hashCode`. Registry values
do not retain task objects or worker threads. Reference-queue cleanup runs on registry
access. Submission/reset tickets retain the original task only for the API call duration.

Every execution saves/restores active test, last-finished marker, setup container stack,
captured container, shared-setup state, diagnostic task stack and enclosing ForkJoin state.
No-owner work suppresses ambient ownership. Descendants capture the active logical
owner. Test completion still causes `LATE_EVENT` under the original test, with no normal
coverage or attribution to the next test. Closed setup containers remain late/incomplete.

## What was adopted from OpenTelemetry

The references are OTel's
[task execution/fork advice](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/executors/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/executors/JavaForkJoinTaskInstrumentation.java),
[executor submission advice](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/executors/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/executors/JavaExecutorInstrumentation.java)
and
[submission failure handling](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/executors/bootstrap/src/main/java/io/opentelemetry/javaagent/bootstrap/executors/ExecutorAdviceHelper.java).

STP now also observes bootstrap task/submission boundaries, scopes task-associated
context around execution, and rolls back rejected submissions. It does **not** import
OTel's runtime or accept ambiguous owner overwrite. Tests exercise STP's implementation;
they are not a claim that the OTel test suite was run.

A relevant JDK detail is
[`ForEachOps.ForEachTask.compute`](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/util/stream/ForEachOps.java):
a parallel stream can refork its own task while processing another split. Repeated
submissions with the **identical logical owner** are therefore allowed. Distinct active
test executions (even with the same test name) are different owners. A conflicting
owner marks the execution ambiguous; subsequent hits are quarantined and the fragment
is incomplete. Already recorded hits are never reassigned.

Rejected API calls remove only their failed registrations. An accepted concurrent
registration is retained, and a conflicting registration cannot be erased by another
call's rollback. Failed submission followed by resubmission under B can safely capture B
when no execution or competing registration occurred.

## Support and executable evidence

| Boundary / scenario | Result | Evidence |
| --- | --- | --- |
| task `fork` / direct `invoke` | Supported | `ForkJoinFixtureMain`: `fork`, `direct` |
| pool `execute` / `submit` / `invoke(ForkJoinTask)` | Supported, task/result preserved | `execute`, `submit`, `invoke`, `custom` |
| RecursiveAction / RecursiveTask / custom `exec` | Supported, including recursive fork/join | `Tree`, `ForkJoinObservedTask`, descriptor assertions |
| JDK `adapt(Runnable)` / `adapt(Runnable,result)` / `adapt(Callable)` | Supported | `ExtendedForkJoinFixtureMain`: six adapter cases + reuse |
| submission via reflection to `submit(ForkJoinTask)` | Supported | `reflective`; caller needs no transformation |
| CountedCompleter tree and normal `onCompletion` path | Supported | concurrent `countedA/B`, recursive children |
| parallel stream `forEach`, internal self-refork | Supported | concurrent `streamA/B`, common-pool `streamCommon`, setup stream |
| distinct A/B on same pool | Isolated | latch-coordinated concurrent callers and exact fragment method sets |
| exception / error / actual worker reuse | Restored | both fixtures; worker onTermination checks state outside every task scope |
| cancelled before execution | No body coverage | both fixtures, runtime cancellation/reinitialize |
| task starts after A ends while B active | A's late event, never B coverage | `lateA/B`, `adaptLateA/B` |
| active setup, propagated descendants, closed setup | Original setup; closed is late/incomplete | both fixtures + runtime tests |
| completed task reuse after reinitialize under B | Supported | `reuseA/B`, `adaptFork/adaptReuse`, runtime tests |
| same-owner self-refork | Supported without changing owner | real JDK stream execution |
| different-owner concurrent reuse | Explicit incomplete | `UnsupportedMain`, runtime concurrent tests |
| rejected pool execute/submit/invoke, then accepted under B | Supported rollback | `rejected-*`, `acceptedB` |
| failed registration concurrent with accepted registration | Does not erase accepted binding | runtime interleaving tests |
| no owner / missing submission with ambient owner | No invented attribution; missing submission diagnosed | runtime tests and unowned fixture tasks |

Child JVMs use the actual shaded agent and `-Xverify:all`. Tests inspect the serialized
schema-v2 fragments, exact descriptor-aware method sets, setup scopes, late diagnostics,
and complete/incomplete status. Latches coordinate concurrency; no sleeps select ownership.

The executable evidence is in:

- [ForkJoinFixtureMain](../stp-agent/src/test/java/example/fixture/ForkJoinFixtureMain.java):
  original API boundaries, recursion, reuse, late events, setup and unsupported cases.
- [ExtendedForkJoinFixtureMain](../stp-agent/src/test/java/example/fixture/ExtendedForkJoinFixtureMain.java):
  adapters, reflection, CountedCompleter, streams, rejection and worker restoration.
- [MethodEntryTransformationTest](../stp-agent/src/test/java/com/sap/oss/smarttestpicker/agent/MethodEntryTransformationTest.java):
  actual agent child processes and serialized fragment assertions.
- [ForkJoinContextTest](../stp-runtime/src/test/java/com/sap/oss/smarttestpicker/runtime/ForkJoinContextTest.java):
  runtime lifecycle, reset/submission races, weak identity and scope restoration.
- [ForkJoinBootstrapTransformerTest](../stp-agent/src/test/java/com/sap/oss/smarttestpicker/agent/ForkJoinBootstrapTransformerTest.java):
  bytecode boundary verification and rejection of unexpected shapes.

## Remaining differences from OpenTelemetry

Task 2 update: [bulk ExecutorService propagation](asm-bulk-executor-propagation.md)
now covers Callable `invokeAll`/`invokeAny` through transformed callers, including
ForkJoinPool. This does not add OTel-style executor method-body instrumentation for
untransformed callers or static `ForkJoinTask.invokeAll`.

This comparison was reviewed on **2026-10-10** against the upstream source linked
above and below. Those links follow `main`; they are not a pinned OTel release.
OTel support here means a matching implementation exists in that source, **not** that
a side-by-side OTel integration suite was executed. STP support is limited to the
executable evidence described in this document.

| Use case | STP today | OTel source mechanism | Why the difference remains |
| --- | --- | --- | --- |
| `ForkJoinPool.invokeAll(Collection<Callable>)`, `invokeAny(...)`, including timed overloads | Supported through transformed call sites (Task 2); no general guarantee for untransformed callers | Executor method advice captures context for the Callable collection | STP now wraps the collection at call sites, but has no bulk executor method-body advice |
| Pool `execute(Runnable)`, `submit(Runnable)`, `submit(Runnable,result)`, `submit(Callable)` from a caller STP does not transform | No general guarantee; supported from matched transformed call sites | Advice in recognized executor method bodies captures at submission | STP's bootstrap pool advice covers only the `ForkJoinTask` argument overloads |
| Custom pool overrides that bypass original JDK submission bodies | No guarantee unless another supported capture boundary is reached | Recognized/configured executor implementations can receive submission advice | STP transforms the exact JDK pool class, not every overriding implementation |

The first two rows are supported by OTel's
[JavaExecutorInstrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/executors/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/executors/JavaExecutorInstrumentation.java).
The custom-implementation qualification follows
[ExecutorMatchers](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/executors/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/executors/ExecutorMatchers.java):
OTel uses an executor allowlist and configurable inclusion; it does not automatically
enable every arbitrary custom executor.

### Bulk operations

Collection-of-Callable executor operations must not be confused with static
`ForkJoinTask.invokeAll(ForkJoinTask...)` or its Collection overload. STP does not
establish a separate capture contract for those static task-bulk methods either.
Their internal calls may reach supported boundaries, but there is no dedicated
fixture proving all participants. OTel's Callable-collection advice is not evidence
for equivalent task-bulk coverage.

### Runnable/Callable overloads and caller visibility

`pool.submit(callable)` and `pool.submit(ForkJoinTask.adapt(callable))` select different
overloads. The latter reaches the new bootstrap boundary even through reflection;
the former still depends on STP's existing generic call-site wrapper. Existing
`ExecutorPropagationFixtureMain` tests prove ordinary transformed Runnable/Callable
calls; the extended reflective fixture proves only `submit(ForkJoinTask)`.

A library caller is not necessarily uninstrumented. The
[ExecutorCallSiteTransformer](../stp-agent/src/main/java/com/sap/oss/smarttestpicker/agent/ExecutorCallSiteTransformer.java)
processes eligible non-bootstrap callers independently of method-recording `includes=`.
Bootstrap/excluded callers and reflective invocation do not supply an equivalent
matched call site. Instrumenting a task's execution alone cannot reconstruct an
owner that was never captured at submission.

### Custom pools

The existing `CustomPool` fixture inherits the JDK methods; it does **not** override
them. Inherited calls and overrides delegating to the instrumented superclass can
reach STP's capture advice. An override bypassing those bodies is not validated.
Generic Runnable/Callable call-site wrappers may still handle resolvable executor
implementations, but that does not prove a bypassing `ForkJoinTask` overload.

### Differences that are not proven OTel advantages

Neither `quietlyInvoke` nor externally invoked completion callbacks are established
as OTel advantages by the inspected advice. Likewise, the narrower STP stream test
set is a coverage limitation, not proof that OTel handles every stream operation.

For concurrent reuse, OTel's
[ExecutorAdviceHelper](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/executors/bootstrap/src/main/java/io/opentelemetry/javaagent/bootstrap/executors/ExecutorAdviceHelper.java)
explicitly acknowledges non-atomic task/context association and possible overwrite
when the same task is submitted concurrently. STP must preserve stricter test
ownership: conflicting owners produce incomplete evidence rather than silently
choosing one. OTel context propagation also does not replace STP's test-completion,
setup-container or `LATE_EVENT` rules.

## Ambiguity and limits

- A conflicting owner without completed reinitialization is incomplete, never silently
  substituted. Same-owner repetition is allowed; it does not make invalid application
  scheduling correct or promise exactly-once Java task execution.
- Reset while executing, including cancelled-but-running tasks, or concurrent reset and
  submission is incomplete. A reset is supported after one completed, non-racing registration. After repeated registrations
  or submission of an already-completed task, reset remains incomplete: the agent cannot prove
  that no stale queue entry could execute after reinitialization. No new owner is guessed.
- Static `ForkJoinTask.invokeAll`, untransformed executor bulk callers, `quietlyInvoke`, explicit completion operations outside task execution,
  CountedCompleter callbacks invoked externally under an unrelated context, custom pool
  overrides bypassing the verified bodies, and direct application calls to `compute/exec`
  are not newly supported. Missing submissions observed under an active owner are diagnosed;
  ownership cannot be inferred for completely unobserved handoffs.
- Stream evidence covers the listed `forEach` paths, not every stream operation/JDK version.
- Shared setup with unknown consumers remains unsupported. Publication does not await
  arbitrary descendants. Existing aggregator/projector lifecycle rules remain authoritative.
- Functional wrapper propagation (Executor, Thread, CompletableFuture, scheduling) is unchanged;
  bootstrap ForkJoin support does not make their other untransformed callers supported.

| Remaining limit | Reason / resulting behavior |
| --- | --- |
| Direct `quietlyInvoke()` without prior captured submission | No capture advice at that API; the execution hook cannot invent the originating owner |
| Direct application call to `compute()` or `exec()` | Bypasses the verified `doExec -> exec` scope boundary |
| External `complete`, `completeExceptionally`, or CountedCompleter completion callbacks outside task execution | These are not separately scoped; the normal completion path inside an attributed task is the tested case |
| Reset during execution or uncertain stale queue entries | A new binding could attribute old queued work to a new test; STP marks the ambiguity incomplete |
| Unknown-consumer shared setup | No exact test ownership is available; existing setup rules remain in force |
| Other stream operations / unverified JDK bytecode | Listed tests do not establish coverage; unexpected bootstrap shapes fail verification visibly |

Unsupported does not mean every possible call is automatically detected. A missing
submission encountered with an ambient owner must not borrow that owner. Task 2 defers
the diagnostic until an unscoped hit or execution exit, allowing a recognized bulk Callable
wrapper to establish its own exact scope for JDK bulk adapters; see the bulk document.
An entirely unobserved handoff on an empty worker cannot reveal which test
originated it. These paths must not be treated as a guarantee of complete coverage.

## Validation commands

Run each with the indicated JDK selected as `JAVA_HOME`, and set `JDK21_HOME` for the
existing virtual-thread fixture:

```sh
./gradlew :stp-runtime:test :stp-agent:test :stp-junit-adapter:test \
  --rerun-tasks --no-daemon --console=plain
```

Verified on SapMachine **17.0.20.1** and **21.0.12.1**: each run passed
**55 runtime + 48 agent + 21 adapter = 124 tests**, zero failures, errors or skips.
Both include the existing JDK 21 virtual-thread fixture.

Reports are preserved under `stp-agent/build/forkjoin-proof/jdk17/` and `jdk21/`.
A separate real-agent run is preserved as `extended.json`, `fragment.json` and
`extended.log` in `stp-agent/build/forkjoin-proof/`: 22 test identities, zero agent
errors, `collection.completed=true`, disjoint CountedCompleter/stream A/B methods,
and only `workerRestored(I)V` attributed to the worker's previous ambient owner.
All generated evidence stays in ignored build output.

## Historical validation retained

The first application-call-site implementation started at `research/asm-codex`,
`132a215a3e48d0d5bc6eaf8aa33a87b13ecd2568`, and was later carried to this branch as
`a066f3e22e6dcca4b431946c34085cd8580b9017`. It used `ForkJoinExecutionTransformer`
and class markers; JDK-adapted tasks were explicitly unsupported. Those mechanisms
are now replaced by the verified bootstrap boundaries above.

Its recorded verification was **47 runtime + 44 agent + 21 adapter = 112 tests**, zero
failures/errors/skips, on SapMachine 17.0.20.1 with SapMachine 21.0.12.1 for the virtual
thread fixture. Its JDK 21 regression also passed (an initial virtual-thread skip was
resolved by configuring `JDK21_HOME`). Those historical results are not new-run results.
