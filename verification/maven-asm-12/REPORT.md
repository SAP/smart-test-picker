# MAVEN-ASM-12 — Shared Spring web context

## Result

**ASM Maven collection does not survive the shared Spring web context in this fixture.** Both HTTP tests pass, but the second class executes controller code on a server worker still owned by the first, now-closed JUnit container. The runtime records `ASYNC_SETUP_UNSUPPORTED / ERROR` and one `LATE_EVENT`; the fragment has `collection.completed=false`. Finalization rejects it. This is total collection rejection, not a usable degraded map.

The result reproduces without the observer and with reversed class order. **JaCoCo completes on the identical fixture.** No production code, framework adapter, collector policy or Jenkins configuration was changed.

This establishes an adoption blocker for the demonstrated cached-server configuration. It does not establish that *every* Spring Boot suite fails, nor a population percentage. ASM-11's successful separate-context PetClinic observation must not be generalized to cached shared web contexts.

## Provenance and execution

- Repository: `research/asm-codex`, source HEAD `ffdc753e12e7eac977b710006d037b19aee09b61`.
- Measurement date: 2026-11-04. Local Maven, no Jenkins.
- [Fixture](../../smart-test-picker-maven/src/test/fixtures/shared-spring-web-context/): frozen disposable Git revision `4bd8b6a40b0963ec9dd5deb9d9f3f3ebff004b09`, identical in all four runs. This is the fixture's temporary repository revision, not an STP source revision. Each retained `source-diff.txt` is empty.
- Maven 3.9.15; SapMachine JDK 21.0.13; fixture Java release 17. Spring Boot 4.1.0, Spring Framework 7.0.8, embedded Tomcat 11.0.22, Surefire 3.5.6, Jupiter/Platform 6.0.3; JaCoCo 0.8.15. Resolved runtime dependencies appear in the retained Surefire XML classpaths. STP components remain `0.3.0-SNAPSHOT`.
- Existing functional-test Maven repository supplies STP artifacts; the task-local dependency repository from ASM-10 is reused. This is not a clean-cache resolution qualification. The four subject workspaces are fresh clones, with no reused inventory, fragments or reports.
- The [runner](run.sh) uses the existing ASM-10 assignment/accounting helper and ASM-11 observer pattern. [Check.java](Check.java) invokes the production fragment/assignment codecs, assignment validator and joiner. [Audit.java](Audit.java) asserts the retained observations and exact test identities; [audit.log](audit.log) records its successful result.
- [Machine-readable summary](raw/summary.json) retains all counters, diagnostics, test identities, setup scopes, context proofs and selected observer rows.

## 1. Context reuse is proved

Both test classes have this byte-identical annotation:

```java
@SpringBootTest(classes = SharedApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
```

Surefire uses one reused fork. The fixture uses Spring's normal context cache, with no `@DirtiesContext`, MockMvc, test-specific configuration differences or custom worker-count setting. Each class makes a real `HttpURLConnection` request to `/ping`; the response contains the actual server thread name. Before the request, a direct controller call on `main` supplies the positive control.

The helper retains the first `ServletWebServerApplicationContext` reference and asserts object identity with `assertSame` in both classes. Matching context id alone would not prove reuse. The retained TSV also records identity hash, startup time and actual port.

| Run | Class order | Context id / identity hash (both classes) | Startup millis (both) | Port (both) | HTTP workers, in order |
| --- | --- | --- | ---: | ---: | --- |
| ASM observed | AFirstTests, BSecondTests | application / 1424243710 | 1793797997145 | 50234 | http-nio-auto-1-exec-1, http-nio-auto-1-exec-2 |
| ASM no observer | AFirstTests, BSecondTests | application / 831285461 | 1793798117830 | 50301 | http-nio-auto-1-exec-1, http-nio-auto-1-exec-2 |
| ASM reversed | BSecondTests, AFirstTests | application / 1424243710 | 1793798144413 | 50319 | http-nio-auto-1-exec-1, http-nio-auto-1-exec-2 |
| JaCoCo | AFirstTests, BSecondTests | application / 1375633173 | 1793798171565 | 50331 | http-nio-auto-1-exec-1, http-nio-auto-1-exec-2 |

Evidence: [observed](raw/asm-observed/context-reuse.tsv), [no observer](raw/asm-plain/context-reuse.tsv), [reversed](raw/asm-reversed/context-reuse.tsv), [JaCoCo](raw/jacoco/context-reuse.tsv). Hashes are compared only within each run, not across JVMs. All four runs execute the same two identities successfully, with no failed, errored or skipped tests.

## 2. Actual ASM outcome

The agent serializes unattributed event arrays with counts. The following sums are measured, not inferred from the predicted mechanism. All three ASM runs have the same values:

| Reason | Global count, each ASM run | Sum of per-test counts, each ASM run |
| --- | ---: | ---: |
| NO_ACTIVE_TEST | 0 | 0 |
| LATE_EVENT | 1 | 0 |
| UNKNOWN_CONTEXT | 0 | 0 |
| TEST_ALREADY_FINISHED | 0 | 0 |
| STARTUP | 0 | 0 |
| SHUTDOWN | 0 | 0 |
| TRANSFORMATION_ERROR | 0 | 0 |

Every ASM run has exactly one setup diagnostic, kind `ASYNC_SETUP_UNSUPPORTED`, severity `ERROR`, method `example.shared.PingController#ping()Ljava/lang/String;`. Its detail is:

```text
work captured by closed JUnit container [engine:junit-jupiter]/[class:example.shared.AFirstTests]
```

The reversed run names `example.shared.BSecondTests` instead. No other setup diagnostic is recorded. The agent's `agentErrors` serialization contains this diagnostic; the observed runs also capture the actual `recordSetupDiagnostic` call, including kind and severity. All ASM runs transform the two production classes, with zero transformation errors.

Raw diagnostics: [observed](raw/asm-observed/module/asm-agent-diagnostic.json), [no observer](raw/asm-plain/module/asm-agent-diagnostic.json), [reversed](raw/asm-reversed/module/asm-agent-diagnostic.json).

Each raw fragment has two mapped PASS identities and no unmapped identities, but **`collection.completed=false`**. The direct positive controls explain why both leaves have controller coverage; successful leaves do not override the global ERROR. Only the first class receives the `CONTAINER` setup scope covering `PingController` and `SharedApplication`. Reversing order reverses that owner.

The actual rejection sequence is important:

1. `verify` reaches fragment/evidence finalization and exits 1:

   ```text
   Cannot finalize ASM mapping evidence: Fragment collection is not completed
   ```

2. Because `verify` fails, the chained aggregation goal is not reached. The harness separately invokes the existing aggregation goal on the retained outputs, **without re-running preparation**. It exits 1:

   ```text
   Maven reactor coverage aggregation failed: Missing module execution evidence for maven:.
   ```

   Finalization did not write evidence v2. The actual aggregator failure is missing evidence, not a fabricated claim that it reached a later completeness check.

3. Independent production assignment validation and production join on the raw fragment both reject:

   ```text
   IllegalArgumentException: Fragment collection is not completed
   ```

4. **Map publication: NOT RUN.** No Jenkins/controller/storage operation belongs to this local task. No ASM candidate map is produced: the upstream production join rejects it. This establishes rejection before publication, not a fresh publication integration test.

See each run's `mapping.log`, `aggregation.log`, `exit-codes.txt` and `validation.json` under [raw](raw/). The retained [observed validation](raw/asm-observed/validation.json) records the separate validation/join outcomes.

## 3. Worker ownership, not missing instrumentation

The disposable [observer](ContextObserver.java) instruments only STP's `RuntimeContextService` and `RuntimeEventAggregator` in memory. It reads the existing captured container and checks its unique id against the real `activeContainers` set. It observes existing capture, hit and diagnostic calls; it does not install a context, change routing or instrument Spring/Tomcat. Its separate test-interval label is for correlation only and is never passed into STP as ownership.

The first observed run records these `PingController#ping` hits:

| Row | Thread | Test interval | Active STP test | Captured container | Captured container active? |
| ---: | --- | --- | --- | --- | --- |
| 49 | main | AFirstTests#realRequest | AFirstTests#realRequest | none | N/A |
| 51 | http-nio-auto-1-exec-1 | AFirstTests#realRequest | none | AFirstTests | true |
| 55 | main | BSecondTests#realRequest | BSecondTests#realRequest | none | N/A |
| 57 | http-nio-auto-1-exec-2 | BSecondTests#realRequest | none | AFirstTests | false |

Row 58 records the ERROR setup diagnostic from the second worker. Both direct calls are positive controls proving that the observer sees an active test when one exists. Both requests returned HTTP 200 and the asserted worker name.

[Observed trace](raw/asm-observed/context.tsv) and [reversed trace](raw/asm-reversed/context.tsv) retain the capture stacks. They pass through `TaskThread`, `ThreadPoolExecutor.prestartAllCoreThreads` and `NioEndpoint.startInternal` while the first class's context starts. The long-lived worker runnable captures that first JUnit container. Request processing on these workers does **not** re-capture ownership for each request or each subsequent class.

The reversed run proves the owner is the class that first starts this server, not a special dependency on the name `AFirstTests`. Ownership persists for these captured worker tasks' lifetime; this is not a claim about every future worker or every server implementation. The no-observer run reproduces the same diagnostic and incomplete fragment, so observer overhead is not necessary for the failure.

Source explanation: [RuntimeContextService.record](../../stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/RuntimeContextService.java) routes a hit with an active captured container into setup coverage. The same hit after that container closes records ERROR plus global LATE_EVENT. [AsmCoverageFragmentProjector](../../stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/AsmCoverageFragmentProjector.java) makes the fragment incomplete on an ERROR setup diagnostic. The runtime therefore refuses to pretend that the first class still owns later requests safely.

## 4. Adoption consequence and the shared-context hook

**A is demonstrated for this configuration; A's literal universal wording is not established.** A normal cached RANDOM_PORT web context, reused across sequential test classes in the same fork with Tomcat's prestarted workers, is sufficient to reject ASM collection when the later class executes instrumented production code on a worker captured by the closed first class. B is refuted for these workers: no ownership re-establishment was observed.

The fraction of realistic Spring Boot suites excluded is **unknown**. This task measures one reproducible topology and two class orders, not a representative population. Non-web tests, MockMvc, separate contexts, separate JVMs and other worker lifecycles are different scenarios; they were not qualified here. It would be misleading either to invent a percentage or to describe this as a harmless loss of attribution precision. For the measured suite, the entire collection is unusable even though all tests pass.

`beginUnboundedSharedContextSetup` has **no production caller**. The current direct test callers are `ForkJoinContextTest` and `AdapterFixtures`; `AsmCoverageFragmentProjectorTest` constructs a `SHARED_CONTEXT_SETUP_UNSUPPORTED` diagnostic directly rather than calling the helper. This distinction comes from the current reference search, not the task's assumed list.

**Simply wiring that helper would not convert this into a usable, honestly degraded result.** Its current branch records `SHARED_CONTEXT_SETUP_UNSUPPORTED / ERROR` and `NO_ACTIVE_TEST` because the complete set of affected containers is unknown. That ERROR also makes projection incomplete. It is an explicit unsupported-boundary signal, not an implementation of safe shared ownership.

Supporting this configuration requires a separately approved production design for ownership/complete consumer accounting across the shared lifetime. Silencing the ERROR or forcing `completed=true` would discard the safety guarantee; merely invoking the existing hook does not supply the missing semantics. No such change was made. Boundary documentation should state that this cached shared-server configuration is currently rejected, not claim broad Spring Boot web qualification from ASM-11.

## 5. JaCoCo comparison

On the same frozen fixture, the existing Maven JaCoCo path succeeds through inventory, assignment, preparation, tests, reports, fragment, evidence and reactor aggregation. Both tests pass; the fragment is complete. Production codecs verify `assignment=2`, `mapped=2`, executable-unmapped=0, positive non-execution=0 with exact identities. Production join accepts the local candidate. Publication remains NOT RUN.

Both identities have `PASS / COLLECTED_WITH_COVERAGE`:

```text
maven:.::example.shared.AFirstTests#realRequest
maven:.::example.shared.BSecondTests#realRequest
```

Each contains the class `example.shared.PingController` and method `example.shared.PingController#ping()Ljava/lang/String;`. JaCoCo also emits `FRAMEWORK_SETUP` scope `junit-container:example.shared.AFirstTests`, owner `maven:.`, covering `PingController` and `SharedApplication`, affecting `AFirstTests`.

Evidence: [accounting](raw/jacoco/accounting.log), [module fragment](raw/jacoco/module/coverage-fragment-v3.json), [execution evidence](raw/jacoco/module/execution-evidence-v2.json), [validation](raw/jacoco/validation.json), [candidate map](raw/jacoco/candidate-map.json).

Qualification: each test's positive control directly calls the same controller method before its HTTP request. A set of method edges cannot distinguish those two contributions. This run proves JaCoCo accepts the shared-context execution and records the stated edges; it does **not** separately prove precise HTTP-only attribution or general JaCoCo accuracy. ASM diagnostics are not applicable in the JaCoCo run, not measured zero. The collection-rejection mechanism is ASM-specific in this comparison.

## Reproduction and retained evidence

From the repository root, use JDK 21 and Maven 3.9.15. Build/populate the existing functional-test artifact repository if not already available using `./gradlew publishStpToIsolatedRepository`; no private repository or Jenkins installation is required. The runner needs the existing common/Maven JARs and the prior checked-in ASM-10 helper/settings and ASM-11 observer manifest.

The measured environment was:

```sh
export PATH=/Users/D061177/Programs/apache-maven-3.9.15/bin:$PATH
export ASM12_WORK=/private/tmp/stp-asm12.DzRWXM
export ASM12_M2=/private/tmp/stp-asm10.VvlrmI/m2
export ASM12_ASM=/private/tmp/stp-asm10.VvlrmI/m2/org/ow2/asm/asm/9.8/asm-9.8.jar
export ASM12_GSON=/Users/D061177/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
bash verification/maven-asm-12/run.sh asm-observed
bash verification/maven-asm-12/run.sh asm-plain
bash verification/maven-asm-12/run.sh asm-reversed
bash verification/maven-asm-12/run.sh jacoco
```

For a fresh repetition set `ASM12_WORK` to a new `mktemp -d` directory and point the other variables at available local dependencies. The runner refuses to reuse a mode's subject directory. Preserve/copy the existing raw evidence before repeating: its output directory is fixed. Each `raw/<mode>/command.txt` records the expanded Maven command actually executed. ASM runs disable JaCoCo; the JaCoCo run attaches no ASM agent. No handwritten ASM scope is used.

Recheck the retained evidence without starting Maven:

```sh
javac -cp "$ASM12_GSON" -d "$ASM12_WORK/classes" verification/maven-asm-12/Audit.java
java -cp "$ASM12_WORK/classes:$ASM12_GSON" Audit \
  verification/maven-asm-12/raw \
  smart-test-picker-maven/src/test/fixtures/shared-spring-web-context
```

One initial harness retry is retained in [asm-plain-harness-initial.log](asm-plain-harness-initial.log). Reusing the observer build directory left ASM's unpacked `module-info.class` in javac's output directory, causing `package java.lang.instrument is not visible` under module `org.objectweb.asm`. It stopped before Maven or tests. The harness now uses a separate observer build directory per mode. The initial disposable clone was preserved separately, and the successful plain control used a fresh clone. This was not a product failure; it is not hidden among the successful measurements.

## Validation and scope

- Four completed local Maven measurements; two tests per run, eight passing test executions in total. ASM mapping failure is the measured result, not a test-body failure.
- Automated evidence assertions pass for context identity, exact test identities/outcomes, diagnostics, completeness, owner reversal, positive controls and JaCoCo acceptance.
- Production fragment/assignment validation and join were executed, not reimplemented by matching counts.
- Runner shell syntax, changed source/report whitespace checks and all 26 report-relative links pass. Raw Maven logs retain their original trailing whitespace rather than being rewritten for formatting.
- Every new task file is below 5 MiB. The repository-wide large-file check still fails on pre-existing `EI-10/artifacts/published-r0-map.json` (30,045,626 bytes) and `EI-11/artifacts/deployed-smart-test-picker.hpi` (10,450,073 bytes); neither was changed.
- No production source change. Baseline suites were **not rerun**; no ASM-11 or older baseline counts are presented as fresh results.
- No Jenkins, distributed shards, AE, S7, per-fork merging, mutation campaign or shared-context-hook wiring. No published map is claimed.

**Measurement complete. ASM adoption for this shared web-context topology remains blocked pending a separately authorized production decision.**
