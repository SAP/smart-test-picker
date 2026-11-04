# MAVEN-ASM-11 — PetClinic HTTP attribution root cause

## Decision

**The 74 methods were executed and observed by ASM. They were attributed to the correct JUnit container's setup coverage, not to the HTTP test's leaf coverage and not to an unattributed-event bucket.** This explains the zero `NO_ACTIVE_TEST`, `LATE_EVENT` and `UNKNOWN_CONTEXT` counts without contradicting execution on servlet worker threads.

The precise per-request/per-test correlation gap is **A: the documented request-handoff boundary**. No failed supported capture/restore path was found. The observed result also needs **C: coarser container attribution**, rather than the premise that these executions disappeared from the map. The retained schema-v3 map contains all 20 differing classes in setup scopes affecting the correct test classes. The current selector consumes those scopes.

Accordingly, **this ledger does not establish an under-selection example in the current selector**. A leaf-only consumer would miss the relevant HTTP tests, but that is not how the current selection implementation works. No mutation or selection experiment was run. The evidence supports the routing explanation and static safety consequence below, not universal inclusiveness for Spring applications or shared application contexts.

No fix, framework adapter or production change was implemented.

## Provenance and observation boundary

- STP checkout: `research/asm-codex`, initial HEAD `5fee1fb7413dc5cd9be9426114d7884219280ac0`.
- PetClinic: unchanged upstream revision `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, same 69-test H2 scope as [MAVEN-ASM-10](../maven-asm-10/REPORT.md).
- One fresh local Maven diagnostic run, no Jenkins. Same STP `0.3.0-SNAPSHOT` artifacts and task-local dependency repository as ASM-10. The Maven/JDK/Spring/Surefire versions remain those recorded in ASM-10; no dependency/version changes were made.
- The disposable agent observes **only two STP runtime classes in memory**, `RuntimeContextService` and `RuntimeEventAggregator`. It does not transform application or framework classes, install ownership, reset state, alter filtering or change runtime routing. On-disk production files and subject sources are untouched. [Observer source](ContextObserver.java), [measurement runner](run.sh), [actual Maven command](raw/observed/command.txt).
- It reads existing `currentTest`, container ThreadLocals and the actual `recordSetup` calls. Capture stacks come from existing `wrap` entry points. A separate observation-only test interval labels rows for correlation; it never supplies ownership to STP. The audit asserts 69 nonoverlapping, correctly paired BEGIN/END intervals.
- The observer is bounded to 20,000 rows; it produced **4,915**, with no observer errors. Eight WATCH rows confirm all intended methods were observed in the application classloader. Reflective state reads and file output impose significant overhead; this is not a timing experiment.
- **69 tests passed; exact test identities/outcomes equal ASM-10. The candidate map is byte-identical to ASM-10.** [Console](console.log), [accounting](raw/observed/accounting.json), [result comparison](raw/observed/results-comparison.json), [observed map](raw/observed/map.json). The source diff is empty.

## 0. The original diagnostic really has zero unattributed events

[Machine-readable counter audit](raw/diagnostic-audit.json) contains all original agent metrics, all reason counts at global and per-test level, the per-test metric sums, and the fresh observed run's equivalents. Its input is the retained [ASM-10 diagnostic](../maven-asm-10/raw/asm/module/asm-agent-diagnostic.json), not a recollection of TASK 21.

The following are weighted counts from the global `runtimeEvents.unattributedEvents` array and all 69 tests' corresponding arrays. The format emits event arrays with `count` values, not dedicated scalar fields for every enum. All those arrays are empty.

| Reason | Original global | Original per-test sum | Observed global / per-test sum |
| --- | ---: | ---: | --- |
| `NO_ACTIVE_TEST` | 0 | 0 | 0 / 0 |
| `LATE_EVENT` | 0 | 0 | 0 / 0 |
| `UNKNOWN_CONTEXT` | 0 | 0 | 0 / 0 |
| `TEST_ALREADY_FINISHED` | 0 | 0 | 0 / 0 |
| `STARTUP` | 0 | 0 | 0 / 0 |
| `SHUTDOWN` | 0 | 0 | 0 / 0 |
| `TRANSFORMATION_ERROR` | 0 | 0 | 0 / 0 |

All other original numeric agent diagnostics:

| Metric | Actual original value |
| --- | ---: |
| classesSeen | 19,261 |
| classesIncluded / classesExcluded / classesIgnored | 30 / 15,255 / 3,976 |
| transformationErrors | 0 |
| transformerTotalNanos | 42,528,933 |
| agentStartNanos | 789,403,573,177,666 |
| classLoaderPresent / classLoaderMissing | 16,916 / 2,345 |
| protectionDomainPresent / protectionDomainMissing | 16,691 / 2,570 |
| classesTransformed | 23 |
| methodsConsidered / methodsInstrumented / methodsSkipped | 121 / 116 / 5 |
| alreadyInstrumentedClasses | 0 |
| methodIdCollisions | 0 |
| rawMethodHits / uniqueMethodHits | 3,816 / 102 |
| runtimeRecordingNanos | 52,217,220 |
| runtime test records | 69 |
| sum of leaf rawMethodHits / uniqueMethodHits | 3,079 / 1,343 |
| agentErrors entries | 0 |

The per-method global hit list, method catalog and each leaf's metrics remain available in the original diagnostic. `bytecodeModified` is true. **Setup observations and typed setup diagnostics are not serialized in this diagnostic's `runtimeEvents` envelope.** They must not be described as zero-valued counters merely because fields are absent: `RuntimeJsonSerializer` emits tests and unattributed arrays, whereas `RuntimeEventAggregator.snapshot()` also supplies setup observations/diagnostics to projection. The published candidate's setup scopes are separate retained evidence.

The initial hypothesis “no leaf context therefore NO_ACTIVE_TEST therefore discarded hits” is **abandoned**. Source inspection re-derives the thread hypothesis with the missing condition: no active leaf is not the same as no eligible owner. In `RuntimeContextService.record`, an active captured container takes the `recordSetup` branch before the final `NO_ACTIVE_TEST` branch. The fresh observation directly confirms that branch, rather than inferring it from an empty diagnostic.

The count reconciliation is exact in both runs: 3,816 raw method hits, of which 3,079 are leaf hits; the observer records the remaining **737 actual setup writes**. There are zero unattributed hits. These are invocation counts, not the 74 distinct ledger edges.

## 1. What the five tests actually do

The pinned subject sources are retained under [raw/subject](raw/subject/). None of the five tests uses MockMvc.

| Test | SpringBootTest environment | Request path | Threads explicitly created by the test |
| --- | --- | --- | --- |
| `PetClinicConcurrencyTests#testDuplicatePetNameRaceConditionIsBlocked` | `RANDOM_PORT` | `RestTemplate.postForEntity` to `http://localhost:<port>/owners/1/pets/new`; the client can also follow the response redirect to owner details | Two client workers via `Executors.newFixedThreadPool(2)`, two `ExecutorService.submit` calls; ready/start/done latches coordinate them |
| `PetClinicIntegrationTests#ownerDetails` | `RANDOM_PORT` | `RestTemplate.exchange`, GET `/owners/1`, real embedded server | None; the caller is the JUnit thread, the server uses its own workers |
| `PetClinicIntegrationTests#ownerList` | `RANDOM_PORT` | `RestTemplate.exchange`, GET `/owners?lastName=`, real embedded server | None |
| `CrashControllerIntegrationTests#triggerExceptionHtml` | `RANDOM_PORT` | `TestRestTemplate.exchange`, GET `/oups`, Accept HTML | None |
| `CrashControllerIntegrationTests#triggerExceptionJson` | `RANDOM_PORT` | `TestRestTemplate.exchange`, GET `/oups`, JSON response | None |

Sources: [concurrency](raw/subject/PetClinicConcurrencyTests.java), [owner HTTP tests](raw/subject/PetClinicIntegrationTests.java), [crash HTTP tests](raw/subject/CrashControllerIntegrationTests.java). The concurrency test also accesses `OwnerRepository` on the calling thread; its existing leaf coverage reflects that. It is not one of the four empty ASM leaves.

## 2. Direct routing evidence and positive controls

[context.tsv](raw/observed/context.tsv) records the existing method hit, actual executing thread, observer test interval, active STP leaf, captured/current container, and actual setup writes. [edge-routing.json](raw/edge-routing.json) correlates **each of the original 74 method edges** with exact HIT and SETUP row numbers and threads. It separately checks all 20 class edges against the original map's correctly owned affected-container scopes. No edge was classified solely from a test count or a class-name guess.

| Original ledger test | Distinct method edges explained | Observed route for every listed edge |
| --- | ---: | --- |
| concurrency test | 30 | `http-nio-auto-1-exec-*`, no active leaf, captured `PetClinicConcurrencyTests`, actual setup writes |
| `ownerDetails` | 21 | `http-nio-auto-2-exec-*`, no active leaf, captured `PetClinicIntegrationTests`, actual setup writes |
| `ownerList` | 21 | `http-nio-auto-2-exec-*`, no active leaf, captured `PetClinicIntegrationTests`, actual setup writes |
| `triggerExceptionHtml` | 1 | `http-nio-auto-3-exec-1`, no active leaf, captured `CrashControllerIntegrationTests`, actual setup write |
| `triggerExceptionJson` | 1 | `http-nio-auto-3-exec-2`, no active leaf, captured `CrashControllerIntegrationTests`, actual setup write |

Concrete paired examples:

- Row 1512: `OwnerController#showOwner(I)ModelAndView` runs on `http-nio-auto-2-exec-2` during `ownerDetails`, active leaf `none`, captured container `PetClinicIntegrationTests`.
- Row 1676: **the same method** runs on `main` during `OwnerControllerTests#showOwner`, with that leaf active. This existing `@WebMvcTest` uses `mockMvc.perform(...)` in the calling thread.
- Rows 4737 and 4742: `CrashController#triggerException()String` runs on HTTP workers during the two HTTP tests, active leaf `none`, captured crash integration container.
- Row 4748: **the same method** runs on `main` with `CrashControllerTests#triggerException` active; this control calls the controller directly.

[Positive controls](raw/positive-controls.json) retain 70 method-hit observations across those two control tests, including the exact controller methods above. Thus the observer demonstrably sees a leaf context when one exists; absence on the HTTP worker is not an untested empty result. The audit also verifies all **3,816** runtime hits were observed, all 69 test intervals close without overlap, and every ledger HIT has a corresponding SETUP write. [Audit console](audit.log), [summary](raw/observation-summary.json).

### Where the container owner comes from

[Capture stacks](raw/capture-chain.json) show Tomcat starting its executor while Spring initializes the JUnit class's application context, before a leaf begins. For example, row 124 contains:

```text
NioEndpoint.startInternal
  -> AbstractEndpoint.createExecutor
  -> ThreadPoolExecutor.prestartAllCoreThreads
  -> TaskThreadFactory.newThread
  -> TaskThread.<init>
  -> RuntimeHooks.wrap
  -> RuntimeContextService.wrap
```

The thread is `main`, active leaf is `none`, and current JUnit container is `PetClinicConcurrencyTests`. Ten prestarted Tomcat workers capture that container; the poller and acceptor capture startup ownership too. Equivalent stacks appear for the other two server contexts. This is the **existing generic Thread-constructor call-site transformation**, not newly added Tomcat instrumentation. `ExecutorCallSiteTransformer`'s exclusion list is separate from the production method-entry scope; it can transform these non-JDK framework call sites.

That wrapper surrounds the worker's long-lived runnable. While the owning JUnit container remains open, subsequent application method hits on that worker have the captured container available. No correlation information in the HTTP request attaches the initiating leaf. `RuntimeContextService.record` therefore calls `aggregator.recordSetup` for that container. `AsmCoverageFragmentProjector` serializes the observed setup methods as **class-level CONTAINER scopes**, retaining the affected container but not per-method setup edges.

For the two client submissions, rows 155–156 separately show the concurrency test's **active leaf** being captured at its supported `ExecutorService.submit` call sites. That does not transfer a Java ThreadLocal across an HTTP socket to Tomcat's pre-existing worker. The client executor boundary and server request boundary are different operations; there is no evidence here that the supported executor capture failed.

## 3. Boundary versus defect

The [documented propagation boundary](../../docs/asm-research-summary.md) explicitly excludes uninstrumented request-specific handoffs. The [current ASM description](../../docs/asm-coverage-mapping-current-state.md) makes the same distinction. The [setup contract](../../docs/task25-method-identity-and-setup-contract.md) and existing `RuntimeContextServiceTest.setupTaskUsesConcreteContainerWhileOpenAndFailsClosedAfterItCloses` establish bounded container ownership and rejection after closure.

The observation matches those implemented paths:

1. Generic startup Thread wrapping captures the open container.
2. Generic test-side executor submission captures the leaf where one exists.
3. A real HTTP request does not establish a server-side leaf owner; that propagation is not promised.
4. Application method entry still fires, and the live captured container owns the hit.
5. Projection preserves its class dependency in a setup scope.

**Classification: A for unavailable precise HTTP leaf attribution; C for the resulting container-scoped representation. Not B on this evidence.** There is no supported request propagation path shown failing, no lost method-entry instrumentation and no late-event quarantine explaining these 74 edges. This is also not the “proxy has no application body” case: the controller and entity methods have bodies and are directly observed executing.

“Setup” is the storage/ownership category here, not a claim that the controller ran only during Spring initialization. It includes later request work under the captured open container. That is coarse and can over-select tests in the container. It is not evidence that arbitrary Spring context caching, parallel containers or subsequent users of the same server are safe. Those cases require their own complete affected-container accounting; this task did not exercise them.

The previous 74 unexplained method edges are now explained **for this subject/run** by that routing. The 17 synthetic bridge differences from ASM-10 are a separate established finding and were not reclassified.

## 4. Selection safety without a selection campaign

The requested concrete missing-leaf example is:

```text
change CrashController#triggerException()Ljava/lang/String;
  -> leaf-only matching would not select
     CrashControllerIntegrationTests#triggerExceptionHtml
     CrashControllerIntegrationTests#triggerExceptionJson
```

Both leaves have `PASS/COLLECTED_EMPTY`. **But claiming those tests would be omitted by the current STP selector would be incorrect.** The original map contains `junit-container:...CrashControllerIntegrationTests`, owned by `maven:.`, covering `CrashController` and affecting that test class. `ExplicitPrSelectorFlow.selectionMap` preserves executable setup scopes when projecting to the logical selection map; `SchemaV2TestSelector.select` adds all head tests in affected containers when their scope intersects changed classes. A change to this method makes `CrashController` a changed class and therefore reaches those two tests through the setup scope.

The same static reasoning applies to `OwnerController#showOwner(I)ModelAndView` and `PetClinicIntegrationTests#ownerDetails`: its leaf edge is absent, but its own container scope contains `OwnerController`. The retained-scope audit confirms this representation for **all 74 method and 20 class ledger differences**, with the correct Maven owner and affected test container. This is a data/source inspection, not a call to the selector, a synthetic plan, or a mutation experiment.

In plain terms: a consumer that looks only at leaf edges would miss dependencies; the current product also looks at the relevant container's dependencies, so it has a conservative route for these examples. There is no honest current-selector false-negative example to name from this ledger on the evidence gathered. Removing or losing the matching setup scope, or reusing a server across an incompletely described consumer set, could remove that protection. Those are distinct hypotheses, not measured failures here.

## 5. Options and decision proposal

| Option | Cost and consequence |
| --- | --- |
| **Document the boundary and change nothing** | No implementation cost. Advertise class/container-level coverage for these real-HTTP tests, not precise request-to-leaf attribution. For this PetClinic scope the retained setup dependencies provide a conservative selection route. A Spring Boot adopter must not generalize that to reused/shared server contexts without qualification. |
| Keep generic ASM and accept bounded container selection | Preserve the current mechanism; any future qualification should establish the full affected-container set for the application's server lifecycle. More tests can be selected than precise request attribution would require. This is a contract/qualification choice, not a proposed map-equality filter. |
| Use full-suite execution for an unqualified HTTP/shared-context scope, or retain the existing JaCoCo mapping path | Operationally simpler than a propagation adapter but costs test time. JaCoCo's time-interval attribution is not an exact ownership oracle and retains its documented reset/late-event differences. No new fallback or selection policy was implemented here. |
| Use in-thread MockMvc where that test's intended contract permits it | Existing positive control proves the context route. It changes what a test validates and cannot replace transport/container/concurrency integration tests merely to improve mapping. Not applied to the subject. |
| **Explicit semantic request adapter**, if exact leaf precision is necessary | Correlate the initiating test/request and attach/restore ownership at a supported server request integration point, with explicit lifecycle, concurrency, errors and late-response semantics. Requires design and qualification; shared contexts and cross-process requests need deliberate treatment. Prefer this over framework bytecode patches. No adapter design is adopted by this report. |
| Framework-specific bytecode interception as last resort | Highest maintenance and compatibility cost across HTTP clients, servlet containers, dispatch/error/async paths and framework versions. Justifiable only if an explicit semantic boundary cannot provide the required ownership. Not implemented or recommended as the immediate next step. |

**Proposed decision:** retain generic ASM and the existing setup semantics; document the precise HTTP boundary and the demonstrated container-scoped protection. Do not patch instrumentation to chase JaCoCo edge equality. If a production requirement demands precise HTTP leaf attribution or reuse across test containers, scope a separate semantic ownership task and qualification measurement. This investigation does not authorize or implement that work.

## Reproduction and checks

The observer follows the disposable agent pattern used by MAVEN-ASM-07's `ProbeObserver`, but targets STP's own existing routing observations. It adds no persistent tracing subsystem or public API.

```bash
export ASM11_WORK="$(mktemp -d /tmp/stp-asm11.XXXXXX)"
export ASM11_SEED=/absolute/path/to/pinned/petclinic/checkout
export ASM11_M2=/absolute/path/to/task-local/maven/repository
export ASM11_ASM=/absolute/path/to/asm-9.8.jar
export ASM11_GSON=/absolute/path/to/gson-2.10.1.jar
# Same built STP functional-test repository prerequisites as ASM-10.
bash verification/maven-asm-11/run.sh
javac -cp "$ASM11_GSON" -d "$ASM11_WORK/classes" verification/maven-asm-11/Audit.java
java -cp "$ASM11_WORK/classes:$ASM11_GSON" Audit \
  verification/maven-asm-10 verification/maven-asm-11/raw
```

Use a disposable checkout/evidence destination when reproducing; the runner writes `raw/observed/`. It rejects an existing subject directory. The actual run used `/private/tmp/stp-asm11.qlDtEo`, the previous task's pinned seed and task-local Maven repository, and a newly compiled observer. Full console and raw evidence remain in this task directory; all files are below 5 MiB.

Fresh validation consists of the **69-test diagnostic Maven mapping run**, exact identity/outcome comparison, production-codec accounting, unchanged map comparison, per-edge routing assertions and positive controls. Shell syntax, observer/helper compilation and report links were checked. **Baseline STP suites were not rerun: no production code changed. ASM-10's suite counts are historical evidence, not fresh results for this task.** No mutation, inclusiveness campaign, Jenkins, distributed shards, AE, S7 or per-fork merging was run.
