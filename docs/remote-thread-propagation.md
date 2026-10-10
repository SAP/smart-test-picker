# Remote STP Thread boundaries

For the final cross-boundary regression and current combined support index, see
[Remote propagation support](remote-propagation-support.md). Task-specific results
below are retained as historical evidence for this implementation step.


## Contract and audit

This is Remote request ownership, independent of unit-test/JUnit lifecycle. The owner of an explicit `Thread.start()` is the request active at **start**, not construction. The whole OpenTelemetry Context travels with it: structured suite/test/request Baggage, trace identity and other Context values. Headerless start captures a Context without STP identity; it must not reuse construction identity. Work after the HTTP response keeps the start request's identity.

Baseline `ad9dbc4` captured construction Context in four Runnable Thread constructors. It also wrapped `Thread.startVirtualThread` and builder `start`. Stack-size constructors, deferred builder/factory launches and overridden `run()` were not covered. The existing constructor tests intentionally expected construction ownership; they are now updated to assert the new start contract while retaining their name/group, shared-Runnable, failure and virtual-thread checks. Historical migration results remain in [the OTel audit](remote-otel-context-propagation.md).

Pinned versions: **OpenTelemetry Java agent 2.32.0**, **API/Context 1.66.0**, Jetty **11.0.25**. The new OTel-only child-JVM audit disables STP adapters but keeps ASM recording. It demonstrates missing start ownership for the listed platform and JDK 21 builder forms. OTel continues to own Servlet and existing executor propagation.

OTel's [VirtualThreadInstrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.32.0/instrumentation/executors/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/executors/VirtualThreadInstrumentation.java) disables executor propagation during carrier transitions; it does not establish the requested direct-thread start ownership. [Java Thread API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html) defines constructor/start, virtual builders and direct-run behavior. STP never instruments carrier/JDK Thread classes or uses an InheritableThreadLocal.

## Support matrix

All rows are measured using real HTTP requests and server JVMs with OTel attached before Remote STP. “Adapter” means application call-site/run advice using the existing OTel Context API.

| Boundary | OTel alone: start ownership | Remote adapter | Requirement |
|---|---|---|---|
| `Thread(Runnable)` | GAP | PASS | constructor and start call sites included |
| `Thread(Runnable, String)` | GAP | PASS | same |
| `Thread(ThreadGroup, Runnable)` | GAP | PASS | same |
| `Thread(ThreadGroup, Runnable, String)` | GAP | PASS | same |
| `Thread(ThreadGroup, Runnable, String, long)` | GAP | PASS | same; stack-size argument preserved |
| `Thread(ThreadGroup, Runnable, String, long, boolean)` | GAP | PASS | same; inheritance flag preserved |
| overridden `run()`, no supplied target | GAP | PASS | declaring run class and start call site included |
| overridden `run()`, supplied target ignored | GAP | PASS | no dependency on `super.run()` |
| override delegates to `super.run()` | GAP | PASS | outer scope remains active through target and following application code |
| inherited application `run()`; start typed as Thread or concrete subclass | GAP | PASS | hierarchy resolves to Thread; run declaration included |
| nested creation/start | not relied upon | PASS | nested call sites included |
| `Thread.startVirtualThread(Runnable)` | GAP | PASS | JDK 21; included call site |
| platform/virtual builder `start(Runnable)` | GAP | PASS | JDK 21; included call site |
| platform/virtual builder `unstarted(Runnable)` then start | GAP | PASS | JDK 21; both call sites included |
| builder `factory().newThread(Runnable)` then start | GAP | PASS | JDK 21; `ThreadFactory.newThread` and start call sites included |
| lazy standard ExecutorService worker using an application ThreadFactory | PASS per task | retained OTel propagation | factory must not install a permanent construction Context |
| direct platform `run()` | synchronous current Context | preserved | no saved-start installation on another thread |
| direct virtual `run()` | JDK no-op | preserved | do not change it into task execution |

Runnable constructor descriptors, names, group, stack-size and inheritance arguments are preserved. Wrapping changes only the Runnable argument. No thread/executor is substituted. The 5-argument constructor requires JDK 9; Remote STP's baseline is JDK 17. Builders and virtual threads require JDK 21.

## Minimal implementation

- `RemoteExecutorCallSiteTransformer` rewrites proven `Thread`/subclass `start()V` calls to `RemoteThreadContext.start(Thread)`, retaining the original virtual `thread.start()` call inside the bridge. Constructors, `unstarted` and `ThreadFactory.newThread` receive a deferred Runnable wrapper that captures **no** construction Context. Trailing constructor arguments are saved/restored with their actual JVM types, including category-2 stack-size values.
- Immediate virtual/builder `start` retains the existing `RemoteTestContext.wrap` path: this call itself is the start point. Task 3's CompletableFuture code and wrappers are unchanged.
- `RemoteThreadContext` stores only a weak-identity Thread-to-Context handoff. It captures the whole Context at start and consumes the handoff once at actual execution. Competing instrumented starts serialize on a private per-thread token; a rejected second start cannot overwrite the first owner. Failed starts clear captured Context, retaining only an empty weak token so waiting retries use the same synchronization object. No custom current-context ThreadLocal is added.
- `RemoteThreadBoundaryTransformer` wraps included application `run()V` overrides. It installs Context before the existing method-hit hook and closes Scope on every return/Throwable. Entry only consumes a handoff when `receiver == Thread.currentThread()`. Direct `run()` on a different thread and nested/super calls do not reinstall saved ownership.
- No handoff means no scope change. This is essential for executor workers: creating a worker in an application factory does not retain request A for the worker lifetime. Standard executor instrumentation still installs each task's A/B/headerless Context.
- The method-entry recorder also checks visibility of its existing `RemoteHooks` helper, so an excluded-by-capability Thread class is not left with an earlier unresolvable method-hit call. This guard preserves execution in isolated loaders without claiming attribution there.
- Injected code must resolve the **same** Remote helper and OTel Scope classes as the agent. Inaccessible helpers or unresolvable run-frame hierarchies leave that boundary unchanged with a `thread-boundary-unavailable`/`thread-boundary-unverified` diagnostic.

## Proven behavior

`RemoteThreadOwnershipIntegrationTest` validates:

- every constructor/builder shape above, names/groups and actual platform/virtual execution;
- construction A → start B; construction A → headerless start; headerless construction → start B;
- callback released only after the start HTTP response; full identity, trace and arbitrary ContextKey from the start request;
- direct run followed by real start; overridden run with/without target and inherited run;
- RuntimeException, Error, interruption, second start while the thread is blocked and another start after termination;
- uncaught exception handler sees cleared STP/custom Context after wrapper/advice cleanup;
- concurrent A/B work rendezvous at a barrier, nested start and exact method ownership;
- lazy single executor worker reused for A, B and headerless tasks;
- virtual scheduling with **one carrier** (`jdk.virtualThreadScheduler.parallelism=1`, `maxPoolSize=1`), including blocked/unmounted tasks and headerless work after attributed work. Neither the adapter nor fixture accesses/instruments carrier Context; negative observations validate isolation, while OTel retains its carrier-transition protection.

The final schema-v2 fragment must equal the complete expected `(testSuiteId, testId, requestId) → descriptor-aware method set`. That includes construction/start/override methods and excludes all wrong-owner leaf relations. Service, unique child-instance and revision metadata are asserted; Java version and PID are logged.

`RemoteThreadContextTest` additionally verifies full previous worker Context restoration after success/exception/error, reuse of one Runnable under different starts, competing starts and failed-start cleanup. `RemoteThreadBoundaryTransformerTest` executes a transformed subclass through a delegating custom loader and rejects an isolated loader without the shared helper. Existing Thread transformer checks and direct-thread HTTP tests remain, updated for start ownership.

## Limits with executable evidence

- A standard Thread whose target constructor/factory is outside `includes`, **and** has no included run override, cannot install the handoff. An included start call alone cannot safely access an arbitrary JDK Thread's target without broader JDK instrumentation. `excludedRunConstructorAndStartBoundariesRemainExplicitGaps` reproduces this missing attribution.
- A subclass whose effective run declaration is outside the included scope is not covered, unless it delegates to an instrumented Runnable target. The same gap test uses an excluded override without `super.run()`.
- An excluded/uninstrumented start call does not establish ownership for deferred threads. The gap test confirms it does not fall back to stale construction A. Immediate JDK builder start remains covered only at its included call site.
- Unresolved custom-class hierarchies, isolated/child-first loaders that cannot resolve the shared helper/Scope, reflection/method handles, arbitrary custom builders and application overrides of Thread lifecycle/state APIs beyond the tested run overrides are not claimed. Builder recognition uses the three JDK Builder interfaces; factory recognition uses the `ThreadFactory` interface descriptor. No approximate match or broad JDK instrumentation is used.
- STP does not infer that an arbitrary application-written long-running Thread loop is an executor. Standard executor worker creation is covered and remains delegated to OTel; no new custom executor propagation is introduced.
- Weak handoffs for a successfully started but **uncovered** run path cannot be consumed by run advice; they are reclaimable when the Thread becomes unreachable. Supported execution paths consume the Context before executing application work. Interrupted/crashed JVMs are not given new durability semantics.

Task 5 separately [reproduces indirect `Thread.start()` gaps](remote-indirect-invocation-propagation.md) through reflection and bound/unbound method handles, inside and outside `includes`. It does not extend this Thread adapter or claim those starts are covered.

## Commands

Full regression, including unchanged Task 3 coverage:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/stp-gradle-home \
./gradlew :stp-remote-agent:test --no-daemon --console=plain
```

Platform HTTP fixture and gap audit in a JDK 17 server; compilation/test orchestration stays on JDK 21 because the existing full test source set includes Java 21 APIs:

```bash
STP_THREAD_JAVA=/Library/Java/JavaVirtualMachines/sapmachine-17.jdk/Contents/Home/bin/java \
JAVA_HOME=/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/stp-gradle-home \
./gradlew :stp-remote-agent:test \
  --tests '*RemoteThreadOwnershipIntegrationTest.platformThreadsHaveStartOwnership' \
  --tests '*RemoteThreadOwnershipIntegrationTest.otelOnlyDoesNotEstablishDirectThreadStartOwnership' \
  --tests '*RemoteThreadOwnershipIntegrationTest.excludedRunConstructorAndStartBoundariesRemainExplicitGaps' \
  --rerun-tasks --no-daemon --console=plain
```

Child logs and finalized fragments remain in ignored `stp-remote-agent/build/thread-start-*` and `thread-otel-only-*`; normal JUnit XML/HTML provides test results. No separate production Commerce/PetClinic deployment was performed for this task.

## Results (2026-10-10)

- Full Remote regression on SapMachine **21.0.12.1**: **77 tests, zero failures/errors/skips**, `BUILD SUCCESSFUL` (61s). Includes unchanged Task 3 CompletableFuture tests, Servlet/Spring/executor/scheduled-task regressions and all virtual/builder cases.
- Targeted real-server checks on SapMachine **17.0.20.1**: **3 tests, zero failures/errors/skips**, `BUILD SUCCESSFUL` (20s): platform start ownership, OTel-only platform audit and excluded-boundary gap reproduction.
- Exact persisted relations: platform fixture **252 identity records / 486 request-method relations**, JDK 21 builder/virtual fixture **141 / 280**. Excluded-boundary fixture **5 / 5**, deliberately without worker leaf observations. Counts are evidence for this deterministic fixture, not production coverage thresholds.
- Final JDK 21 XML is also retained locally in ignored `build/thread-jdk21-final-results`. JDK 17 XML/HTML is in the normal test result directories. Raw child logs and fragments are not committed.
