# Remote CompletableFuture registration ownership

Remote STP owns request attribution; OpenTelemetry owns Context storage, HTTP extraction and scope restoration. This contract is independent of the unit-test ASM/JUnit runtime. A callback remains attributable after its registering HTTP response has completed. There is no test-lifecycle/LATE_EVENT rule here.

## Audit and measured gap

Baseline: `1d5759f46bd1cdb52adcb23c1119f8b94e63d930`, branch `feature/asm-codex-remote`. OpenTelemetry Java agent **2.32.0**, API/Context **1.66.0**, Jetty **11.0.25**, Jakarta Servlet **5.0.0**.

Before this change the Remote adapter wrapped only `thenRunAsync` and `thenApplyAsync` (both executor variants). `runAsync` and `supplyAsync` were and remain delegated to OTel. No additional executor, Servlet, Thread, Spring or unit-test runtime implementation is added here.

The real HTTP audit starts a server with OTel before Remote STP and `-Dstp.remote.otel.contextOnly=true`. ASM method recording stays enabled, but STP context adapters are disabled. For each of the 39 forms below, A registers a callback and returns its HTTP response; B later completes the predecessor. All 39 callbacks execute under **B**, not A, in this pinned OTel version. The audit checks that exact incorrect mapping in the finalized fragment, so a future OTel upgrade that closes the gap forces reevaluation of the adapters.

This agrees with the pinned OTel implementation: [JavaExecutorInstrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.32.0/instrumentation/executors/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/executors/JavaExecutorInstrumentation.java) captures the current context at executor submission. [JavaForkJoinTaskInstrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.32.0/instrumentation/executors/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/executors/JavaForkJoinTaskInstrumentation.java) restores task-associated context at execution; neither establishes ownership at these callback registrations. A predecessor may submit its continuation under a different request's context. Synchronous callbacks can execute directly on that completion thread ([JDK CompletableFuture contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html)).

## Support matrix

Each row below covers **three forms**: synchronous, `Async(callback)` and `Async(callback, Executor)`. Binary forms also take the other `CompletionStage`. “OTel gap” means arbitrary registration/completion ownership, not that OTel never propagates those callbacks: already-completed predecessors can work automatically. Registration wrappers are still needed because completion timing can race.

| Callback family | OTel alone: late A registration / B completion | STP registration adapter | HTTP fixture |
|---|---|---|---|
| `thenApply` | GAP | `Context.wrapFunction` | all 3 |
| `thenAccept` | GAP | `Context.wrapConsumer` | all 3 |
| `thenRun` | GAP | `Context.wrap(Runnable)` | all 3 |
| `thenCompose` | GAP | `Context.wrapFunction` | all 3, delayed inner future and subsequent continuation |
| `handle` | GAP | `Context.wrapFunction(BiFunction)` | all 3 |
| `whenComplete` | GAP | `Context.wrapConsumer(BiConsumer)` | all 3 |
| `exceptionally` | GAP | `Context.wrapFunction` | all 3 |
| `thenCombine` | GAP | `Context.wrapFunction(BiFunction)` | all 3 |
| `thenAcceptBoth` | GAP | `Context.wrapConsumer(BiConsumer)` | all 3 |
| `runAfterBoth` | GAP | `Context.wrap(Runnable)` | all 3 |
| `applyToEither` | GAP | `Context.wrapFunction` | all 3 |
| `acceptEither` | GAP | `Context.wrapConsumer` | all 3 |
| `runAfterEither` | GAP | `Context.wrap(Runnable)` | all 3 |
| `runAsync`, `supplyAsync`, default/explicit executor | PASS (existing regression) | none | `RemoteCompletableFutureIntegrationTest` |

`exceptionallyAsync`, with and without executor, requires **JDK 12** ([JDK API](https://docs.oracle.com/en/java/javase/12/docs/api/java.base/java/util/concurrent/CompletionStage.html)). Other listed API forms date to JDK 8; Remote STP itself has a Java 17 baseline. The existing full Remote test source set requires JDK 21. The new HTTP fixture can also execute in a Java 17 child JVM.

## Implementation and boundaries

`RemoteExecutorCallSiteTransformer` extends its existing CF call-site adapter. Only exact `INVOKEVIRTUAL java/util/concurrent/CompletableFuture` owners, the listed names and their full expected descriptors match. The original invocation is preserved; only its callback argument is wrapped. The optional executor and the other predecessor are unchanged. Existing `thenRunAsync`/`thenApplyAsync` wrappers are not applied a second time.

`RemoteTestContext` delegates to the OTel Context API. The wrapper captures the **complete current Context**, including STP Baggage, trace identity and other context values. The OTel wrapper installs that Context for the callback and closes its scope on return/exception/error. No STP ThreadLocal, identity regeneration, future-associated mutable owner or new schema is introduced.

Capturing a Context **without STP identity is intentional**: such a callback must not borrow the completing request's STP identity. Combining futures does not combine their owners; the registering request owns the callback. Wrappers do not force callbacks to execute if normal CF completion/cancellation rules skip them.

Limits:

- The registering call site must be in a configured `includes` class and outside `excludes`. Calls made inside uninstrumented library/framework classes are not covered by this adapter.
- Calls whose bytecode owner is `CompletionStage`, or a custom CompletableFuture subclass, are not matched. They are not claimed to have registration ownership; ordinary OTel execution-time propagation may still occur. No hierarchy-based guess is made.
- `exceptionallyCompose`/`exceptionallyComposeAsync`, reflective registrations and unlisted CF APIs are outside this matrix. Inner work returned by `thenCompose` needs its own supported propagation boundary; returning a future does not retroactively change the owner of work already started elsewhere.
- Default async execution uses CF's normal executor (normally the common pool; the JDK can use its documented low-parallelism fallback). No executor substitution is introduced.

## Executable evidence

`RemoteStageOwnershipIntegrationTest` uses real Servlet HTTP requests and separate JVMs with both agents. `.feature` files, remote schema and client identity protocol are unchanged.

For **every one of the 39 API shapes**, the fixture checks:

- predecessor completed before registration;
- A registration, response returned, completion without STP context or under B;
- headerless registration, completion under B;
- callback exception, predecessor cancellation, dependent-stage cancellation;
- results, single callback execution or correctly skipped callback;
- registration trace identity and an additional arbitrary OTel ContextKey;
- pooled-worker cleanup after each case;
- two callbacks registered under A and B on the **same future**; asynchronous callbacks rendezvous at a barrier, proving overlap.

Additional cases create the two predecessor futures through requests A/B and register each binary form under C. Delayed `thenCompose` inner futures complete through another HTTP request; a continuation registered with the initial request must still belong to it. Latches/barriers and completion handshakes coordinate scenarios; sleeps are used only to poll server readiness.

The final schema-v2 fragment must equal the full expected map of `(testSuiteId, testId, requestId) → descriptor-aware method set`, including completion-thread methods, headerless exclusions and no cross-attribution. Assertions also verify `source.serviceId`, a unique child-instance ID and `source.revision`. Child logs record Java version and PID.

`RemoteStageContextTest` checks all five functional wrapper types against a previous nonempty worker Context, including RuntimeException/Error and headerless capture, and verifies one wrapper per supported call with unchanged original descriptors/opcodes. Existing executor/Servlet/Spring/scheduled/Thread tests remain in the full regression.

Commands (local JDK paths may differ):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/stp-gradle-home \
./gradlew :stp-remote-agent:test --no-daemon --console=plain

STP_CF_JAVA=/Library/Java/JavaVirtualMachines/sapmachine-17.jdk/Contents/Home/bin/java \
JAVA_HOME=/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/stp-gradle-home \
./gradlew :stp-remote-agent:test --tests '*RemoteStageOwnershipIntegrationTest' \
  --rerun-tasks --no-daemon --console=plain
```

Evidence stays in ignored `stp-remote-agent/build/cf-registration-*` and `cf-otel-only-*` directories: child logs and finalized observations. JUnit XML/HTML results are under the normal module build test directories. This validation extends the HTTP agent fixture, without claiming a new production Commerce/PetClinic deployment validation.

### Results (2026-10-10)

- Full Remote regression on SapMachine **21.0.12.1**: **67 tests, 0 failures/errors/skips**, `BUILD SUCCESSFUL` (46s).
- Both ownership integration tests repeated with SapMachine **17.0.20.1** server JVMs: **2 tests, 0 failures/errors/skips**, `BUILD SUCCESSFUL` (17s). Compilation/test orchestration used JDK 21 because unrelated existing Remote fixtures require it; this is not a claim that the entire existing source set builds on JDK 17.
- Both JDKs: **39 OTel-only ownership gaps**, verified against 78 persisted request records. With adapters: **723 exact expected request records / 1,389 deduplicated request-method relations**, no additional or missing relations. These counts describe the deterministic fixture, not production coverage thresholds.
- Full JDK 21 result XML is additionally preserved locally under `build/cf-jdk21-results`; the final targeted JDK 17 run has the normal test report. Generated logs/fragments are not committed.
