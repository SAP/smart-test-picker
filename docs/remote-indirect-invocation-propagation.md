# Remote STP reflection and method-handle boundaries

For the final cross-boundary regression and current combined support index, see
[Remote propagation support](remote-propagation-support.md). Task-specific results
below are retained as historical evidence for this implementation step.


## Audit and ownership contract

Task 5 baseline: `ccfe2db158fde32a22358c2bd90224987a60adb4` on
`feature/asm-codex-remote`. Tasks 1–2 implement **unit-test ASM** ForkJoin/bulk
ownership; their runtime and lifecycle semantics are not used or changed here.
Tasks 3–4 implement Remote CompletableFuture registration and Thread start ownership.
This task uses the same **OpenTelemetry Java agent 2.32.0**, **API/Context 1.66.0**,
**Jetty 11.0.25** and **Jakarta Servlet 5.0.0**.

The owner is the context at the target API boundary: executor submission, callback
registration or Thread start. Creating a Method/MethodHandle, including `bindTo`,
does not assign ownership. The whole Context includes all three STP Baggage fields,
trace identity and arbitrary ContextKey values. Work may outlive the HTTP response;
JUnit completion/LATE_EVENT semantics do not apply.

Before production changes, the new HTTP fixture ran twice: with only OTel propagation,
and with the existing Remote adapters. Both produced the same indirect-call matrix.
The baseline was checked against finalized descriptor-aware method records, not
inferred from an invocation returning successfully. Initial audit command:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/stp-gradle-home \
./gradlew :stp-remote-agent:test --tests '*RemoteIndirectInvocationIntegrationTest' \
  --no-daemon --console=plain
```

The OTel-only test remains executable using the existing
`stp.remote.otel.contextOnly=true` child-JVM switch. It disables STP propagation
adapters but retains ASM method recording, so dependency upgrades must explicitly
reconcile any changed gap behavior.

## Measured support matrix

Every row is exercised through seven actual invocation forms:

- `Method.invoke`;
- unbound `MethodHandle.invoke`, `invokeExact`, `invokeWithArguments(List)`;
- the same three handle invokers after `bindTo(receiver)`.

`invokeExact` uses each target's actual typed descriptor; the fixture does not
replace it with an Object-array spreader. The other `invoke` path deliberately
exercises Object-argument adaptation. Two fixture classes contain equivalent
invocation bytecode: one inside `includes`, one outside. Merely instrumenting a
delegating caller is not counted as instrumenting the actual invocation.

| Target boundary | OTel alone, all seven forms | STP adapter for included `Method.invoke` | Handles / excluded invocation sites |
|---|---|---|---|
| `Executor.execute(Runnable)` | PASS | none needed | PASS through OTel |
| Concrete custom `MeasuredExecutor.execute(Runnable)` | PASS | none needed | PASS through OTel |
| Three `ExecutorService.submit` overloads | PASS | none needed | PASS through OTel |
| Four `invokeAll` / `invokeAny` overloads | PASS | none needed | PASS through OTel |
| `schedule(Runnable/Callable, delay, unit)` | PASS for tested scheduler/tasks | none added | PASS through OTel |
| `scheduleAtFixedRate` / `scheduleWithFixedDelay` | GAP: execution has no STP identity | PASS: registration Context wrapper | GAP: no STP identity |
| `CompletableFuture.thenApply(Function)` | GAP: delayed callback borrows completion Context | PASS: registration Context wrapper | GAP: completion ownership |
| `CompletableFuture.thenApplyAsync(Function, Executor)` | GAP: delayed callback borrows completion Context | PASS: registration Context wrapper | GAP: completion ownership |
| `CompletableFuture.thenCombine(CompletionStage, BiFunction)` | GAP: delayed callback borrows completion Context | PASS: registration Context wrapper | GAP: completion ownership |
| `Thread.start()` | GAP: no start identity | GAP, unchanged | GAP, unchanged |

Executor/scheduler rows cover named Runnable/Callable objects **and lambda method
references**. The concrete executor overrides `execute` and delegates to the JDK
ThreadPoolExecutor. This does not claim all arbitrary custom executor implementations
are supported. The standard scheduler is subclassed only to inspect `afterExecute`.

The earlier migration document reported a scheduling gap in its fixture. This audit
provides more specific positive evidence for the one-shot shapes above; it does not
remove the existing direct scheduling bridges or rewrite historical results.

OTel's pinned [JavaExecutorInstrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.32.0/instrumentation/executors/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/executors/JavaExecutorInstrumentation.java)
instruments executor implementation methods, including submit, bulk and one-shot
schedule. Such advice can run regardless of how the method was invoked. That source
does not add fixed-rate/fixed-delay advice. For future callbacks, execution-time
executor capture is not registration-time capture; Tasks 3 and this audit reproduce
the resulting ownership difference.

## Narrow reflection adapter

`RemoteExecutorCallSiteTransformer` recognizes the exact
`Method.invoke(Object, Object[])Object` instruction in included application classes.
It passes the method, receiver and argument array to `RemoteReflectiveCallbacks`,
then executes the **original Method.invoke instruction at the original caller**.
It neither delegates reflection into a different caller nor catches/rewrites its
exceptions, so access checks and InvocationTargetException behavior remain intact.

The helper accepts only:

- exact `CompletableFuture` receiver and declaring class, expected return type,
  and the three callback descriptors in the matrix;
- `ScheduledExecutorService`-declared fixed-rate/fixed-delay methods with the exact
  Runnable/long/long/TimeUnit signature and ScheduledFuture return type.

It validates argument compatibility, clones the argument array, and replaces only
the callback slot with an existing `RemoteTestContext` OTel wrapper. The caller's
array, receiver, non-callback values, executor and futures remain unchanged. Long
arguments retain reflection's primitive-widening behavior. Null/malformed calls,
custom CompletableFuture subclasses and unlisted signatures are left to the original
invocation. A loader unable to see the shared helper is skipped with a diagnostic.

Capture happens for each invocation, including a context without STP Baggage. The
OTel wrapper installs the complete captured Context and closes Scope after return
or failure. No thread-local identity, handle-to-owner association, new schema or
method-level spans are introduced. The original direct CF/scheduler adapters do not
also match `Method.invoke`; the transformer test verifies one reflection helper and
unchanged reflection/handle descriptors/opcodes.

## Reproducible gaps and follow-up boundaries

These are **unsupported ownership paths**, not successful propagation tests:

1. **Method-handle CF callbacks:** A registers, B completes; application hits are
   recorded under B. Headerless registration also borrows B. Every bound/unbound
   form checks that incorrect relation explicitly in the final fragment. A handle's
   invocation descriptor does not identify its target API, and bound/adapted handles
   cannot safely be classified from that descriptor. Wrapping all functional
   arguments at arbitrary handle calls would change unrelated application semantics.
   A separate follow-up would need narrowly targeted CF implementation instrumentation
   (with bootstrap/helper visibility and duplicate-capture handling), not a generic
   reflection framework or capture when a handle is constructed.
2. **Method-handle periodic scheduling:** two executions occur without identity in
   every handle form. The same issue affects excluded reflective registration sites.
   Follow-up: instrument the verified scheduling implementation boundary, or include
   a known application reflection site when the narrow adapter is sufficient.
3. **Indirect Thread.start:** the fixture constructs a prepared Thread under A and
   invokes start indirectly under B; its Runnable has no start identity. Task 4's
   constructor preparation is deliberately insufficient without its start bridge.
   Correct support needs a safe start/run handoff that also preserves failed and
   concurrent start behavior and excludes permanent executor-worker ownership.
   Replacing Method.invoke with a plain helper call would lose reflection exception
   semantics; supporting opaque handles and excluded callers requires a wider
   Thread implementation/bootstrap intervention. That remains a separate follow-up.
4. **Excluded reflection sites:** executor and one-shot scheduler still pass through
   OTel. CF/periodic registrations bypass the STP adapter and retain the baseline
   gaps. Expanding `includes` to the known application invocation class can enable
   the supported reflection shapes; it does not fix method handles or Thread.start.

The task does not add support for arbitrary handle combinators, private lookups,
every custom CF/scheduler implementation, all 39 CF signatures through reflection,
or classloaders without shared helpers. No inference of support from matching method
names or callback-shaped argument types is made.

## Executable evidence

`RemoteIndirectInvocationIntegrationTest` starts real Servlet server JVMs with OTel
before Remote STP. All test requests use W3C Baggage. It checks:

- all 17 API shapes × seven invokers × included/excluded callers, attributed and
  headerless; executor/scheduler shapes repeat with named and lambda tasks;
- handle/Method creation under A, target invocation under B; unchanged handle type;
- delayed work released only after submission HTTP response (bulk API requests
  necessarily remain open until their blocking invocation completes);
- deferred CF completion under another request, including headerless registration,
  and callbacks registered under two owners on one predecessor;
- actual concurrent tasks using a barrier, distinct method leaves and different
  physical worker threads; sequential reuse of the same worker;
- return values and CF function inputs/results; callback failures, failed invokeAny,
  Thread uncaught exceptions and rejected second starts;
- cancellation before execution using a blocked worker or incomplete predecessor;
  cancelled callbacks generate no application method hit;
- invalid reflection arguments, handle casts, WrongMethodTypeException, original
  InvocationTargetException wrapping, and no context left in executor `afterExecute`;
- exact final request–method set equality: all three identity fields, descriptor-aware
  methods, schema version, service, revision and unique JVM instance metadata.

`RemoteReflectiveCallbacksTest` additionally checks full previous nonempty Context
restoration, callback reuse, headerless capture, untouched original argument arrays,
primitive widening, invalid receiver/signature handling, one wrapping instruction,
and safe rejection when the helper is unavailable.

Commands:

```sh
# Complete Remote regression; existing Task 3/4 and Servlet/Spring tests included.
JAVA_HOME=/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/stp-gradle-home \
./gradlew :stp-remote-agent:test --no-daemon --console=plain

# Same indirect HTTP fixtures in JDK 17 server JVMs.
STP_INDIRECT_JAVA=/Library/Java/JavaVirtualMachines/sapmachine-17.jdk/Contents/Home/bin/java \
JAVA_HOME=/Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/stp-gradle-home \
./gradlew :stp-remote-agent:test --tests '*RemoteIndirectInvocationIntegrationTest' \
  --rerun-tasks --no-daemon --console=plain
```

Compilation/test orchestration uses JDK 21 because other existing Remote test sources
use Java 21 APIs. The new server/invocation fixture uses Java 17-compatible APIs.
Ignored `build/indirect-otel-only-*` and `build/indirect-adapters-*` retain logs and
actual finalized fragments; normal JUnit XML/HTML contains assertions/results.
This is a local real HTTP agent proof, not a new Commerce/PetClinic deployment claim.

## Results (2026-10-10)

- Full Remote suite on SapMachine **21.0.12.1**: **86 tests, zero failures/errors/skips**,
  `BUILD SUCCESSFUL` (73s). Existing Task 3 CompletableFuture, Task 4 platform/virtual
  Thread, Servlet, Spring and executor regressions remain passing.
- All three new integration tests with SapMachine **17.0.20.1** child servers:
  **3 tests, zero failures/errors/skips**, `BUILD SUCCESSFUL` (25s).
- Each JDK executes **840 scenarios** in the OTel-only matrix and the same 840 with
  adapters, plus the concurrency/failure/cancellation fixture. Unsupported rows
  deliberately assert the reproduced gap; a green test suite is not a claim that
  every indirect boundary is supported.
- Exact finalized evidence on both JDKs: OTel-only **1,232 request records / 1,470
  request–method relations**; adapter matrix **1,232 / 1,471**; concurrency and API
  semantics fixture **595 / 777**. Set equality checks ownership, not just counts.
  The small count difference hides substantial reassignment from completion owners
  to registration owners; the assertions compare every complete identity and method.
- Final JDK 21 XML is retained locally in ignored `build/indirect-jdk21-final-results`;
  final targeted JDK 17 XML/HTML is in the normal test report directories. Generated
  observations and logs are not committed. `git diff --check` passes.
