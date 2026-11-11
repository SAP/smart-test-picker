<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# ForkJoinTask ownership in the unit-test ASM agent

Baseline: `research/asm-codex`, `132a215a3e48d0d5bc6eaf8aa33a87b13ecd2568`.
This extends the local test/runtime agent, not Remote STP. No OTel dependency,
selection change, or fragment schema change is introduced.

## Mechanism and ownership

`ExecutorCallSiteTransformer` captures the active test execution or effective setup
container immediately before the original call. It neither replaces the task nor
changes the invocation opcode, receiver, arguments, return value, or scheduler.
Construction is not a capture boundary.

`ForkJoinExecutionTransformer` instruments non-bootstrap concrete `compute()`
implementations of `RecursiveAction` / `RecursiveTask`, and concrete `exec()Z`
implementations of custom `ForkJoinTask`. It runs after method-entry instrumentation,
so the original method's descriptor-aware hit is inside the attribution scope.
Compiler bridges and nested calls restore their enclosing scope. A catch-all finally
also restores state on exceptions and errors; existing catch handlers retain precedence.
No JDK classes or worker loops are transformed.

The mechanism reference is OpenTelemetry's
[`JavaForkJoinTaskInstrumentation`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/executors/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/executors/JavaForkJoinTaskInstrumentation.java):
task-associated context, activation around execution, and closure on all exits.
STP uses its own existing test/container lifecycle contract. Unlike OTel's bootstrap
instrumentation, this implementation requires a transformable application execution
boundary, and adds explicit incomplete diagnostics for ambiguous reuse.

The runtime associates a task by **weak object identity**, not user `equals`/`hashCode`.
Values do not retain task objects. Reference-queue cleanup runs on registry access;
class support checks use `ClassValue`. A binding lasts until an observed completed
`reinitialize()` or collection of the task, so duplicate submission cannot silently
replace its owner. Cancelled tasks retain no independent strong task reference.

Each execution saves and restores the worker's active test, last-finished marker,
container stack/captured container, shared-setup state, diagnostic task stack, and
enclosing ForkJoin execution. A captured no-owner execution suppresses ambient worker
ownership rather than borrowing it. Recursive children capture the installed logical
owner, including propagated setup containers.

Test hits after `endTest(A)` use the existing aggregator's `LATE_EVENT` bucket under A;
they do not enter A's ordinary coverage or B's coverage. Work for a closed container
retains the existing late/`ASYNC_SETUP_UNSUPPORTED` contract. Shared setup with unknown
consumers remains unsupported.

## Support and executable evidence

| Boundary / scenario | Result | Evidence |
| --- | --- | --- |
| `ForkJoinTask.fork()` | Supported with instrumented execution | `ForkJoinFixtureMain`, `fork` |
| direct `ForkJoinTask.invoke()` | Supported with instrumented execution | `direct` |
| `ForkJoinPool.execute(ForkJoinTask)` | Supported | `execute` |
| `ForkJoinPool.submit(ForkJoinTask)` | Supported; same task returned | `submit` |
| `ForkJoinPool.invoke(ForkJoinTask)` | Supported; result retained | `invoke`, `custom` |
| inherited calls on application pool/task subtypes | Supported through bytecode hierarchy resolution | `CustomPool`, `Action`, `Value`, `CustomTask` |
| recursive fork / direct invoke / join | Supported | `Tree`, `recursive` |
| sequential completed task + `reinitialize()` under another test | Supported | `reuseA`, `reuseB`, runtime reuse test |
| concurrent A/B on the same pool | Isolated | two caller threads and latch-gated workers, `parallelA/B` |
| cancellation before execution | No execution coverage; cancellation retained | `cancelled`, runtime cancellation/reinitialize test |
| exception / error / worker reuse | Restored | `exception`, `error`, same physical worker assertion |
| task starts after A ends while B is active | A's late event, no B coverage | `lateA/B` |
| active setup and recursive child | Original container | `setup`, runtime setup test |
| closed setup | Late + incomplete | `UnsupportedMain`, runtime setup test |
| no active owner | Unattributed; ambient state restored | `unowned`, runtime no-owner/ambient/shared-state tests |
| same task submitted again without completed reinitialize | Explicit incomplete, original owner never overwritten | `ambiguousA/B`, runtime duplicate-submission test |
| JDK-created `ForkJoinTask.adapt(...)` via task-shaped APIs | Unsupported execution boundary; explicit incomplete with active owner | `jdkAdapted` |

`MethodEntryTransformationTest` runs child JVMs with the actual shaded agent and
`-Xverify:all`, reads their runtime output and serialized schema-v2 fragments, checks
exact application method descriptors per API boundary, and asserts complete versus
incomplete status. `ForkJoinObservedTask` also proves that both typed `compute` and
its bridge are recorded inside the scope. Existing Thread/Executor fixtures remain
in place; their formerly unsupported application ForkJoin tasks now assert attribution.
An adapted JDK task retains their negative incomplete-fragment check.

## Ambiguity and limits

- Repeated submission before completed reinitialization marks the execution ambiguous
  and emits `ASYNC_SETUP_UNSUPPORTED` with ERROR severity. Subsequent hits in that
  execution are quarantined as `UNKNOWN_CONTEXT`; descendants do not acquire a guessed
  owner. Already recorded hits are not retroactively reassigned. The fragment is incomplete.
- `reinitialize()` during execution, including a cancelled-but-still-running task,
  is unsupported/incomplete. The original Java operation still executes unchanged.
- Both submission and execution must be observed. Bootstrap/JDK, excluded, preloaded,
  reflection/method-handle submissions, bulk APIs, and `CountedCompleter` are not newly
  supported. An observed active-owner submission to a task without a verified execution
  marker emits an incomplete diagnostic; unobserved boundaries cannot be inferred.
- The caller hierarchy must resolve from bytecode. Unknown candidate owners are reported
  through the existing agent error/incomplete path instead of guessed.
- After a rejected submission the weak binding remains. Retrying the same unfinished
  object is conservatively incomplete, not silently assigned to a different test.
- Publication still does not wait for arbitrary descendants. Late-event handling and
  the existing projector's completeness rules are unchanged.

## Validation commands

```sh
./gradlew :stp-runtime:test :stp-agent:test :stp-junit-adapter:test --no-daemon --console=plain
```

Final verification used SapMachine 17.0.20.1, with `JDK21_HOME` pointing to
SapMachine 21.0.12.1 for the existing virtual-thread child-JVM test:

```sh
JAVA_HOME="$JDK17_HOME" JDK21_HOME="$JDK21_HOME" \
  ./gradlew :stp-runtime:test :stp-agent:test :stp-junit-adapter:test --no-daemon --console=plain
```

Results: **47 runtime + 44 agent + 21 JUnit adapter tests = 112 tests, zero failures,
errors, or skips**. The runtime/agent/adapter regression also passed when Gradle and
the ordinary child JVMs ran on JDK 21 (the virtual-thread test was initially skipped
until its existing `JDK21_HOME` configuration was supplied in the final JDK 17 run).

Generated XML, child-JVM observations/fragments and HTML reports stay in ignored
build/temp output. Historical research reports are unchanged.
