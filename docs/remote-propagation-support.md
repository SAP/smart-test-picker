# Remote STP propagation: consolidated support and final validation

This is the current Remote support index after Tasks 1–6 on
`feature/asm-codex-remote`. Task 6 starts at
`ce7d5dd7fa7f1abd21161dfa7a9785a82fb9a0e1`. It adds integration evidence and
documentation, not a new propagation mechanism. Earlier task reports retain their
historical measurements; this index distinguishes those from current support.

## Audit of preceding tasks

| Task / commits | Implemented | Remote evidence / remaining boundary |
|---|---|---|
| 1: ForkJoin, `b3a27e2`, `a066f3e` | Unit-test ASM ForkJoin ownership | Not Remote evidence. Direct `ForkJoinTask.fork/invoke`, recursive task reuse and setup-container rules must not be inferred for Remote. |
| 2: bulk executor, `1d5759f` | Unit-test ASM bulk ownership | Not Remote evidence. Remote bulk support is separately measured with OTel by Task 5. |
| 3: CF, `ad9dbc4` | 39 direct callback registration shapes | Proven by real HTTP/agent tests, including OTel-only counterexamples; exact bytecode owner and application scope limits remain. |
| 4: Thread, `ccfe2db` | Start ownership, deferred targets, included run overrides, JDK 21 builders | Proven platform and virtual paths; excluded creation/start/run and indirect starts remain limited. |
| 5: indirect calls, `ce7d5dd` | Audit of 17 shapes through seven invocation forms; narrow reflective CF/periodic adapter | OTel executor/bulk/one-shot paths pass; opaque-handle CF, periodic and Thread start gaps remain reproducible. |

No JUnit/runtime ownership or `LATE_EVENT` rule is used in these Remote tests.

## Versions and scope

- OpenTelemetry Java agent **2.32.0**, API/Context **1.66.0**.
- SapMachine **21.0.12.1**: complete Remote regression, including virtual threads.
- SapMachine **17.0.20.1**: combined platform-chain server in Task 6. Tasks 3–5
  also retain their separate JDK 17 server evidence in the linked reports.
- Jetty **11.0.25**, Jakarta Servlet **5.0.0**; Spring MVC **6.1.14**.
- `javax.servlet` **4.0.1** and Jakarta listener bytecode tests both run, but the
  real HTTP container uses Jakarta. This is not a real-container javax validation.
- Production bytecode targets Java 17. Compilation/test orchestration uses JDK 21
  because the complete test source set references JDK 21 APIs. Running a Java 17
  child server does not claim that the full test source set compiles on Java 17.

Attach OTel before Remote STP. All HTTP tests use W3C Baggage. STP reads
`stp.test.suite.id`, `stp.test.id`, `stp.request.id` from `Context.current()` and
records schema-v2 structured identity and descriptor-aware methods. Adapter scopes
carry the whole Context, including trace identity and arbitrary ContextKey values.
There is no STP current-identity ThreadLocal or method-level span generation.

In the matrix, **OTel** means automatic propagation proved by a Remote agent HTTP
fixture; **adapter** means the existing STP bridge uses OTel Context capture/scope
restoration. A passing test which deliberately reproduces a gap does not turn that
gap into support. OTel source/documentation references are in the linked task reports.

## Unified support matrix

All listed integration tests are in `stp-remote-agent/src/test/java`. Unless stated
otherwise, the full-suite result is JDK 21; combined platform chains also run on 17.

| API forms | Owner / mechanism | Instrumentation scope and limit | Executable evidence |
|---|---|---|---|
| HTTP Servlet, FilterChain, nested filters; REQUEST/FORWARD/INCLUDE/ERROR/ASYNC redispatch; requestInitialized/requestDestroyed | Incoming Baggage / OTel | Tested Jetty/Jakarta lifecycle, not all containers | `RemoteAgentIntegrationTest` |
| `AsyncContext.start(Runnable)` | Submission / OTel | Tested Servlet implementation | `RemoteAgentIntegrationTest` |
| `AsyncListener.onStartAsync/onComplete/onError/onTimeout` | Listener registration Context / adapter | Concrete javax/jakarta listener and registration boundaries, separate from application includes; timeout is a measured OTel gap. Adapter retains all four callbacks | `RemoteAgentIntegrationTest`, `RemoteBoundaryTransformerTest` |
| `setReadListener`, onDataAvailable/onAllDataRead/onError; `setWriteListener`, onWritePossible/onError | Listener registration Context / adapter, weak identity association | Tested concrete Servlet boundaries; no arbitrary callback interface inference | Same two tests |
| `Executor.execute`; `ExecutorService.submit(Runnable)`, `(Runnable,result)`, `(Callable)` | Submission / OTel | Verified JDK pool and custom executor delegating to it; works through reflection/handles and outside includes. Does not imply arbitrary custom executor support | `RemoteAgentIntegrationTest`, `RemoteIndirectInvocationIntegrationTest`, combined chains |
| `invokeAll(Collection)`, timed invokeAll, `invokeAny(Collection)`, timed invokeAny | Bulk submission / OTel | Task 5 independently proves all four through interface/implementation calls; no unit-test ASM evidence reused | `RemoteIndirectInvocationIntegrationTest`; combined invokeAll → CF chain |
| `ForkJoinPool.commonPool().execute(Runnable)` → nested Executor submission | Submission / OTel | Exactly the Runnable overload. No Remote claim for direct ForkJoinTask APIs, CountedCompleter, recursive fork/join or reinitialize | `RemoteAgentIntegrationTest`, combined ForkJoin chain |
| `CompletableFuture.runAsync`, `supplyAsync`, each default/explicit executor | Submission / OTel | Tested common-pool and explicit-executor forms | `RemoteCompletableFutureIntegrationTest` |
| CF thenApply/thenAccept/thenRun/thenCompose/handle/whenComplete/exceptionally/thenCombine/thenAcceptBoth/runAfterBoth/applyToEither/acceptEither/runAfterEither: synchronous, default Async, explicit Executor Async (**39 forms**) | Callback registration / adapter | Included call site, exact `CompletableFuture` bytecode owner and descriptor. No `CompletionStage`/custom-subclass owner inference. exceptionallyAsync requires JDK 12, hence available on 17 | `RemoteStageOwnershipIntegrationTest`, `RemoteStageContextTest`; [full forms and evidence](remote-completablefuture-propagation.md) |
| `schedule(Runnable,long,TimeUnit)`, `schedule(Callable,long,TimeUnit)` | Scheduling / existing direct adapter; OTel also passes Task 5's indirect one-shot fixture | Direct owner must resolve to ScheduledExecutorService; indirect OTel result limited to tested scheduler/tasks | `RemoteScheduledExecutorIntegrationTest`, `RemoteIndirectInvocationIntegrationTest`, combined scheduled chain |
| `scheduleAtFixedRate`, `scheduleWithFixedDelay` | Scheduling / adapter on every invocation | Included direct call with proven hierarchy; OTel-only periodic gap. Included reflection supports verified interface signatures; handles/excluded reflection remain gaps | Same two tests |
| Six Runnable Thread constructors: target; target/name; group/target; group/target/name; plus long stack size; plus boolean inheritance flag | Actual included `start()` / adapter; constructor only prepares target | Both target preparation and start included, or effective run override included. All original arguments retained | `RemoteDirectThreadIntegrationTest`, `RemoteThreadOwnershipIntegrationTest`, combined platform thread chain |
| Thread/subclass-typed `start()`, inherited/overridden `run()` with or without target/super.run, nested starts; direct run | Start / adapter; direct run retains caller Context | Resolvable Thread hierarchy, included effective run and shared helpers; no broad JDK instrumentation | `RemoteThreadOwnershipIntegrationTest`, `RemoteThreadBoundaryTransformerTest`; [full matrix](remote-thread-propagation.md) |
| `startVirtualThread`, platform/virtual builder start, unstarted → start, factory.newThread → start | Immediate start or later explicit start / adapter | JDK **21**; matching JDK Builder/ThreadFactory owners and included call sites; no carrier ownership | `RemoteThreadOwnershipIntegrationTest`, `RemoteDirectThreadIntegrationTest`, combined virtual chain |
| Spring MVC Callable, WebAsyncTask, DeferredResult, tested @Async CompletableFuture, direct AsyncTaskExecutor execute/submit | Framework submission / OTel | Spring 6.1.14 fixture; no broad Spring instrumentation or claim for all @Async return types | `SpringMvcAsyncIntegrationTest` |
| Reflection `Method.invoke` for CF thenApply, thenApplyAsync with Executor, thenCombine | Callback registration / narrow adapter | Included invoke site, exact CompletableFuture receiver/declaring class and verified descriptors; other reflective CF APIs unclaimed | `RemoteIndirectInvocationIntegrationTest`, `RemoteReflectiveCallbacksTest` |
| MethodHandle invoke/invokeExact/invokeWithArguments, bound/unbound: executor/bulk/one-shot scheduling | Submission / OTel | Implementation advice, including invocation outside includes; tested concrete executors only | `RemoteIndirectInvocationIntegrationTest`; combined invokeExact → submit → CF |
| Same handle forms for CF callbacks or periodic scheduling; reflective/handle Thread.start | **GAP** | CF borrows completion identity; periodic/Thread work executes without the expected identity. Excluded reflective CF/periodic paths have the same gaps | `RemoteIndirectInvocationIntegrationTest` verifies actual execution and the incorrect/missing relation |
| Reactor/RxJava, arbitrary custom executors/builders/loaders, additional APIs | **NOT CLAIMED** | No new support or fixture in this task | Separate follow-up only |

Application method recording always requires matching `includes`, no matching
`excludes`, and a loader that can resolve the shared recorder helper. OTel can
propagate through an excluded executor call while STP records an included method
later; this differs from an STP call-site adapter which needs that invocation site
itself included. Do not broaden includes to all framework/JDK classes to infer support.

## Combined HTTP evidence

`RemoteCombinedPropagationIntegrationTest` launches actual agent JVMs. Included
`example.chains.ChainBoundaries` holds application API call sites;
`ChainMethods` contains separate A/B descriptor-aware leaf methods. Excluded
`example.chainsupport` coordinates execution and reports observed Context and
thread IDs without implementing propagation.

The platform fixture exercises:

1. HTTP → execute → thenApplyAsync → schedule(Runnable).
2. HTTP → commonPool.execute(Runnable) → submit(Runnable) → thenApplyAsync.
3. HTTP → execute → invokeAll(two Callables) → two thenApplyAsync callbacks.
4. HTTP → new Thread/start → thenApplyAsync.
5. HTTP → Method.invoke submit(Callable) → thenApplyAsync.
6. HTTP → typed MethodHandle.invokeExact submit(Callable) → thenApplyAsync.
7. A shared callback through executor → thenApplyAsync across requests.

The JDK 21 fixture also exercises HTTP → virtual builder start → thenApplyAsync.
The same original Function object is used across all these registrations. It is
wrapped per registration, never permanently assigned to one request.

For each chain, A/B tasks meet at a barrier on different workers. Initial HTTP
responses return before task gates open. Callback registration completes under
the original request; separate HTTP requests with another identity complete the
predecessors. Every callback must retain its registering identity, trace and extra
ContextKey. Headerless work executes between A and B; its event log proves execution
while the final fragment must exclude those leaf hits. A single worker is explicitly
reused across A/headerless/B/A. Failure and dependent-stage cancellation in the
executor/CF/scheduler chain preserve exceptions/results and do not invent callback
execution or contaminate subsequent work.

Cleanup probes drain every configured worker using barriers. `afterExecute` records
raw Context outside the task wrapper, retaining every leak even if a later task is
clean. Existing Task 3–5 fixtures retain direct-thread uncaught-error cleanup,
virtual isolation, all callback forms and API-specific cancellation checks.

### Checkpoint overlap and HTTP completion

The test-only `ChainCheckpointControl` uses the recorder's **existing** test latch
at snapshot capture. It holds persistence, permits new late leaf methods under the
same request identities, then releases the write. Assertions establish:

- during the write, logical snapshot includes late hits while disk is still the
  previous successful checkpoint;
- after it, disk equals the captured set and memory contains only the late hits;
- the next checkpoint unions both and clears pending memory;
- shutdown's finalized schema-v2 file equals the complete expected relation map.

The test routes are fixture-only, not new production debug or flush endpoints.
Periodic interval is 3600 seconds to prevent an unrelated timer competing with the
deterministic checkpoint experiment; existing periodic/output/crash tests still run.

**An HTTP response does not finish Remote ownership.** Supported async work keeps
its originating identity until that work completes. There is no Remote LATE_EVENT
cutoff at response completion. Snapshot contains work recorded so far, not future
hits. Agent shutdown checkpoints recorded hits; it does not wait for arbitrary
unfinished application tasks. The fixture waits for async completion before stop.

All snapshots/final files assert exact positive **and forbidden** request–method
relations, all three identity fields, `source.serviceId`, unique child-JVM instance
ID and revision. Neither method counts nor span parentage substitute for this check.

## Diagnostics and remaining follow-ups

- Isolated loader tests now assert `method-hook-unavailable`,
  `thread-boundary-unavailable`, and `reflective-boundary-unavailable` diagnostics.
  The untransformed isolated Thread still executes, proving missing attribution
  does not imply missing application execution.
- Thread transformation/frame failures can emit `thread-boundary-unverified`.
  Unresolved hierarchy lookup may instead return no match silently. Generic
  transformer catch/skip paths and unmatched owners do not all emit diagnostics.
- `RemoteReflectiveCallbacksTest` explicitly verifies an opaque handle call is
  unchanged **without a completeness diagnostic**. Remote output has no global
  unsupported/incomplete flag. An empty or smaller method set cannot certify full
  application coverage. Task 5's event logs independently prove unsupported work ran.
- Follow-ups require their own scope: opaque-handle CF registration and periodic
  scheduling, indirect Thread start, unsupported bytecode owners/custom
  implementations/loaders, and consistent unsupported-boundary reporting.
- Direct ForkJoinTask and recursive/reinitialize contracts need independent Remote
  measurement before reuse of the unit-test ASM support claims. This task only
  composes the already-proven Remote Runnable submission path.

No production regression was found by this final validation. No propagation,
schema, JUnit runtime or `examples/` changes were needed. This is a local real-HTTP
agent proof, not a fresh Commerce deployment or PetClinic/Karate run.

## Commands and results

Exact commands (local JDK paths can be adjusted):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/stp-gradle-home \
./gradlew :stp-remote-agent:test --no-daemon --console=plain

JAVA_HOME=/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/stp-gradle-home \
STP_CHAIN_JAVA=/Library/Java/JavaVirtualMachines/sapmachine-17.jdk/Contents/Home/bin/java \
./gradlew :stp-remote-agent:test \
  --tests '*RemoteCombinedPropagationIntegrationTest.combinedPlatformChainsCheckpointAndIsolation' \
  --no-daemon --console=plain
```

On 2026-10-10, the Java 17 combined platform fixture passed with zero
failures/errors/skips: **77 exact request records, 243 request–method relations**.
The JDK 21 virtual combined fixture expects **9 records, 25 relations**. These
counts describe the fixture; assertions compare the actual complete maps.

Full JDK 21 regression: **89 tests, zero failures/errors/skips**, `BUILD SUCCESSFUL`
(1m 19s). Both combined fixtures pass: platform **77 / 243**, virtual **9 / 25**.
JDK 17 targeted platform run: **1 test, zero failures/errors/skips**,
`BUILD SUCCESSFUL` (12s). If repeating an identical test selection with a changed
child-JVM environment override, add `--rerun-tasks` to prevent an up-to-date skip.
The Java 17 targeted run intentionally excludes virtual/builders because they
require Java 21; they are exercised by the full JDK 21 regression. No test is marked
skipped to conceal an unsupported boundary: explicit gap assertions remain tests.

Generated artifacts stay in ignored `stp-remote-agent/build/combined-chains-*`
directories (Java/PID logs and finalized output). JUnit XML is in
`build/test-results/test`; the Java 17 result is also retained locally in
`build/task6-jdk17-results`. Historical Task 3–5 report results remain unchanged.
