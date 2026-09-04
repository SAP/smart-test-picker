# STP ASM architecture and determinism review — round 1

## Executive conclusion

Primary recommendation: **REDESIGN THE EXPERIMENT BEFORE IMPLEMENTATION**.

Framework-support answer: **ONLY AS FALLBACK** should framework support use framework-specific ASM instrumentation.

The branch contains a credible, deliberately narrow proof that generic ASM can record entry to selected, already-loadable application method bodies and associate synchronous same-thread hits with a JUnit Platform leaf. It also contains useful evidence that method entry does not supply Spring Data caller semantics: a repository interface may have no application method body, generated proxy code is intentionally excluded, and an inner repository execution hook can disappear on a cache hit. The implemented Spring Data solution therefore uses public Spring/Spring Data APIs and an audited Spring AOP advisor, not framework-specific ASM. That evidence supports generic ASM for application bodies plus semantic adapters at reliable public boundaries. It does not support “one ASM instrumentation adapter per framework.”

The branch does **not** yet establish that ASM is a more deterministic or sufficiently complete replacement for the existing JaCoCo per-test mapper. The retained experiments test ASM stability across a few selected, synchronous tests and both Java-agent orders, but do not perform the required same-execution, descriptor-preserving JaCoCo-versus-ASM comparison, do not exercise 1/3/4-shard map production, and do not measure false-negative dependency edges. Conversely, the existing JaCoCo path has concrete attribution and merge hazards independent of JaCoCo probe generation: probe state and dump/reset are JVM-global while listener bookkeeping is thread-local; the two JUnit integration paths use different lifecycle intervals; readable test identity collapses parameterized invocations; method descriptors are discarded; and map merge uses last-writer `putAll` for duplicate test keys, with unsorted input enumeration. These facts make “JaCoCo is the root cause” premature.

The smallest next step is an experiment-only, descriptor-preserving comparator that runs both collectors in the same **sequential** test JVM, captures lifecycle phase, preserves raw output, and compares `test -> binary class -> method name + JVM descriptor`. Run it repeatedly as one JVM before adding shards; then run ASM-only with the identical test order. No production collector or selection behavior should change yet.

## Scope, sources, and confidence language

This review covers the implementation at branch head `b55fd3a` (`ASM POC, initial commit`), the pre-existing JaCoCo mapping implementation and its relevant history, module tests, retained spike scripts/results, and current documentation. The ASM work is one large POC commit, so commit boundaries do not independently corroborate its individual design claims. No retained artifact compares the reported 1-agent, 3-agent, and 4-agent mappings, so the drift analysis below enumerates code-supported hypotheses rather than naming a measured root cause.

The classifications used throughout are:

- **PROVEN BY TEST/EXPERIMENT** — directly exercised by a maintained unit/integration/acceptance test or retained experimental output, within the stated versions and scenario.
- **REASONABLE BUT NOT PROVEN** — technically coherent but not validated against the present mapping question or its important operating conditions.
- **HISTORICAL SPIKE DECISION** — an intentionally narrow choice made to get the experiment running; it is not a production conclusion.
- **QUESTIONABLE** — evidence or implementation exposes a material correctness/safety weakness.
- **SHOULD BE REVISITED** — may have been appropriate for the spike but must be decided again before use for RTS.

“Proven” never implies general version compatibility or safe RTS completeness beyond the actual experiment.

## 1. Existing `asm-poc` architecture audit

### 1.1 Module boundaries and ownership

| Decision / implementation | Finding | Classification |
|---|---|---|
| `stp-runtime` owns the framework-neutral event records, aggregation, same-thread context, registry, hook, and deterministic serializer | Clean dependency direction; it has no ASM, JUnit, or Spring dependency. | **PROVEN BY TEST/EXPERIMENT** for isolation, aggregation, sorting, and registry behavior. |
| `stp-agent` owns `premain`, configuration, filtering, transformation, method catalog, runtime creation, and shutdown output | There is exactly one process-local runtime installed by the agent. | **PROVEN BY TEST/EXPERIMENT** for the tested system/application-classloader fixture; **SHOULD BE REVISITED** for other loader topologies. |
| `stp-junit-adapter` translates Platform leaf start/finish callbacks into runtime scopes | It resolves the agent-owned runtime and creates no fallback aggregator. | **PROVEN BY TEST/EXPERIMENT** for the tested Platform launcher and sequential/synchronous fixtures. |
| `stp-spring-data-adapter` is optional and separately classpathed | It owns Spring Data metadata, eligibility, advisor insertion/audit, and repository events, but not the runtime. | **PROVEN BY TEST/EXPERIMENT** for pinned Boot 4.1/Spring 7/Spring Data 4.1 JDK proxies; **REASONABLE BUT NOT PROVEN** as a durable module boundary. |
| The ASM path is isolated from existing STP map generation and selection | Nothing converts `spike-2` runtime JSON to the production coverage map or consumes it for selection. | **PROVEN BY TEST/EXPERIMENT** by dependency/build inspection; **HISTORICAL SPIKE DECISION**. |
| Agent JAR shades ASM and embeds runtime plus JUnit listener | Avoids exposing original ASM packages and ensures `premain` precedes service-loaded listener construction in tested launchers. | **PROVEN BY TEST/EXPERIMENT** for the fixture JAR. |

This is a sensible experiment decomposition. The broader runtime vocabulary (`SpringBeanEvent`, `EndpointEvent`, entity/table events, evidence/certainty) is larger than the immediate mapping problem and several categories have no producer. Retaining them as an experimental schema is harmless, but treating that vocabulary as a required future platform would be a **HISTORICAL SPIKE DECISION** and **SHOULD BE REVISITED**.

### 1.2 Runtime ownership and event model

`AgentRuntime` creates one `RuntimeEventAggregator(runId, "pid-" + pid)`, installs it in `RuntimeContextRegistry`, and installs its method-ID consumer in `RuntimeHooks`. Both registries reject a second installation. The JUnit and Spring adapters discover the same runtime. A JVM shutdown hook serializes global agent metrics, the method catalog/hit counts, and nested runtime events, then closes registrations.

The event model is evidence-labelled and typed. Method identity is `binaryClassName#methodName(JVM descriptor)`. Repository identity separately includes interface, canonical bean name, erased JVM descriptor, domain type, outcome, evidence source, and certainty. Aggregation is synchronized, per-test event facts are deduplicated with counts, and serialization sorts tests and facts. These identity and deterministic-output decisions are **PROVEN BY TEST/EXPERIMENT** for unit fixtures and retained E2E output.

Important caveats:

- A PID is unique only in a limited execution environment and can repeat across sequential JVMs. `runId + pid + Platform unique ID` is therefore **REASONABLE BUT NOT PROVEN** as cross-fragment identity and **SHOULD BE REVISITED** before joining distributed output.
- Method identity omits defining classloader/module/code-source. Two loaders defining the same binary name and descriptor collapse into one logical method. That is **QUESTIONABLE** for completeness/diagnostics in plugin, container, or reloadable applications, although it may be acceptable for a single ordinary application classpath.
- Shutdown-only persistence can lose the entire result on crash, forced termination, or output failure. The writer reports failure to stderr but cannot persist the new error. This is a **HISTORICAL SPIKE DECISION** and **SHOULD BE REVISITED** for a production collector.
- Global raw hits are counted even when the catalog cannot resolve a unique identity. A collision becomes an unattributed event rather than a false edge. This fail-closed behavior is **PROVEN BY TEST/EXPERIMENT**, but a run with any collision cannot be accepted as a complete RTS map.

### 1.3 JUnit attribution and lifecycle

`StpRuntimeTestExecutionListener` opens a scope for every `TestIdentifier.isTest()` at Platform `executionStarted` and closes it at `executionFinished`. Canonical test identity is the Platform unique ID plus run/JVM scope; `MethodSource` is only descriptive. Parameterized, repeated, nested, and dynamic leaves therefore remain distinct. This is **PROVEN BY TEST/EXPERIMENT**.

Attribution is an ordinary `ThreadLocal`. It deliberately has no executor, reactive, virtual-thread, HTTP/server-thread, or other carrier. Another thread records global `NO_ACTIVE_TEST`. This is an explicitly documented **HISTORICAL SPIKE DECISION**; for a replacement collector it is a **QUESTIONABLE** completeness limitation and **SHOULD BE REVISITED** only where real target tests cross threads.

For Jupiter, the Platform leaf interval includes `@BeforeEach` and `@AfterEach`, as the test suite demonstrates. `@BeforeAll`/`@AfterAll`, Spring context startup, and shutdown are outside a leaf. An immediate same-thread event after a test has finished is routed toward the last finished identity, where the aggregator classifies it as `LATE_EVENT`; it is not included in dependencies. Beginning the next test clears the last-finished marker. This late-event quarantine is **PROVEN BY TEST/EXPERIMENT** for sequential same-thread callbacks.

There are still material boundaries:

- A worker event cannot be attributed even if the test waits for the worker before returning. **QUESTIONABLE** for any async target tests.
- A worker event after completion is globally `NO_ACTIVE_TEST`, not tied to the originating test, because `lastFinished` is also thread-local. The documented “late” distinction is only same-thread. **SHOULD BE REVISITED** if late async events occur in the target suite.
- Parallel leaves on distinct threads can each have a context, and the synchronized aggregator can store them, but parallel Platform behavior has no maintained test. **REASONABLE BUT NOT PROVEN**.
- A no-argument listener constructed before the agent runtime remains permanently inert; it does not resolve lazily later. The agent fixture proves intended startup order, not all launchers/classloaders. **REASONABLE BUT NOT PROVEN** and **SHOULD BE REVISITED**.

### 1.4 ASM instrumentation

The transformer runs only at initial class definition (`addTransformer(..., false)`; manifest disallows redefinition/retransformation). It uses configured package prefixes, mandatory framework/STP exclusions, test-output code-source detection, and name-pattern exclusions for common generated proxy forms. Accepted concrete classes receive two instructions at the first instruction of each accepted method: load a stable `long` and invoke `RuntimeHooks.methodHit(J)V`. The hook catches all collector failures so they do not escape into application code.

The fixture proves transformation and behavior preservation for ordinary, static, private, overloaded, synchronized, branching, try/catch, and throwing methods; ASM verification; JVM `-Xverify:all`; marker idempotence; collision reporting; stable normalized fixture output; relocated ASM; and both JaCoCo agent orders in selected E2E/PetClinic tests. Those exact claims are **PROVEN BY TEST/EXPERIMENT**.

The transformer skips whole interfaces/annotations and skips constructors, class initializers, `$jacocoInit`, abstract methods, and native methods. It does not inspect `ACC_SYNTHETIC` or `ACC_BRIDGE`, so synthetic/bridge/lambda-body methods in ordinary accepted classes are instrumented. It writes no flags into the catalog, so they cannot be normalized from output without separately reading bytecode. These are **HISTORICAL SPIKE DECISIONS**; the missing flags are **SHOULD BE REVISITED** for fair JaCoCo comparison.

It uses `ClassWriter(reader, COMPUTE_MAXS)` and preserves existing frames. Because the inserted straight-line entry instructions introduce no branch or new merge, this is reasonable and the tested bytecode verifies. It is **PROVEN BY TEST/EXPERIMENT** for the fixture/PetClinic bytecode and **REASONABLE BUT NOT PROVEN** across all supported classfile producers. `COMPUTE_FRAMES` is not automatically safer: it introduces loader-aware hierarchy resolution problems and is unnecessary unless transformation changes control flow.

### 1.5 Filtering, generated classes, and proxies

The default include is PetClinic-specific. Mandatory exclusions include Java/Jakarta/JDK, JUnit, Spring, Hibernate, Mockito, Byte Buddy, JaCoCo, and STP packages. Explicit user exclusions override includes. Test classes are excluded by protection-domain paths containing `/test-classes/`; generated-looking names containing `$$`, `$MockitoMock$`, `$ByteBuddy$`, `$HibernateProxy`, or `$HibernateInstantiator` are ignored.

Prefix normalization and core decisions are **PROVEN BY TEST/EXPERIMENT**. The policy itself is a **HISTORICAL SPIKE DECISION** and **SHOULD BE REVISITED** because:

- test-output detection is build-layout-specific and misses Gradle `build/classes/java/test`, Kotlin test layouts, unconventional outputs, in-memory definitions, and null code sources;
- substring heuristics can exclude legitimate application classes and miss other generated forms, hidden classes, JDK proxy names, Kotlin/Scala/Groovy generators, and framework-specific generators;
- excluding all interfaces drops Java default and private interface method bodies;
- excluding generated bodies is safe only if the desired mapping is explicitly application-source methods and semantic dependencies are obtained elsewhere. Otherwise it creates false negatives.

The POC correctly avoids assigning stable semantic identity from generated proxy class names. That principle is **PROVEN BY TEST/EXPERIMENT** in the Spring Data work and should remain.

### 1.6 Spring Data approach and framework adapter model

The implemented adapter is not ASM. It uses public `RepositoryFactoryBeanSupport.addRepositoryFactoryCustomizer`, a public repository proxy post-processor to obtain `RepositoryInformation`, Spring bean lifecycle APIs, public `Advised` inspection/mutation, and an AOP Alliance interceptor. It normalizes identity to repository interface, canonical bean name, domain type, and reflective JVM descriptor. It installs one index-zero advisor only after `SmartInitializingSingleton`, audits bean identity, proxy kind, interfaces, target source, proxy depth, existing advisor identity/order, and cache positions, and enables recording only after the audit passes. Failure removes the exact STP advisor and verifies restoration.

For Boot 4.1/Spring 7/Spring Data 4.1, real synchronous unambiguous JDK repository proxies, the internal fixture and pinned PetClinic prove:

- caller-level calls are observed before caching, including a cache hit that does not execute the underlying repository;
- the existing bean/proxy and advisor chain are preserved except for the owned advisor;
- exact return/throwable behavior is preserved in fixtures;
- descriptor-aware overloads are distinct;
- ASM and repository events share the same leaf identity;
- startup calls remain unattributed and sequential test facts do not cross;
- normalized output is stable across repeat/order tests and both JaCoCo agent orders.

Those bounded claims are **PROVEN BY TEST/EXPERIMENT**. General Spring Data compatibility is **NOT proven**. Mocked repositories, class-based proxies, non-`Advised` products, products created after the insertion phase, reactive/async completion, Boot 3, other stores, SQL/table facts, and repository-aware RTS are explicitly unsupported. Restricting support and diagnosing ambiguity is **REASONABLE BUT NOT PROVEN** as a product policy; accepting only JDK proxies and one pinned generation is a **HISTORICAL SPIKE DECISION** and **SHOULD BE REVISITED** only if actual target applications need more.

The investigation is particularly valuable because it falsified simplistic mechanisms. Spring Data’s `RepositoryMethodInvocationListener` and advice inside the inner repository proxy see underlying execution; they need not see a caller-level cache hit. The useful boundary was outside the cache interceptor on the already-exposed proxy. An early bean-post-processing insertion was also unstable because a later public post-processor appended an advisor. These are **PROVEN BY TEST/EXPERIMENT** for the pinned contexts and directly argue for semantic public hooks/lifecycle audits over bytecode instrumentation of framework internals.

### 1.7 JaCoCo coexistence

The POC ran selected fixture/PetClinic tests with JaCoCo before ASM and ASM before JaCoCo. It observed passing tests, output from both agents, stable normalized ASM/repository facts, no duplicate hooks, no verification/linkage failures, and no reported transformation errors/collisions. This limited compatibility is **PROVEN BY TEST/EXPERIMENT**.

It does not prove observational non-interference. The retained checks do not compare JaCoCo per-test method edges across agent orders, compare ASM-only with dual collection over a broad suite, or determine whether transformer order changes JaCoCo class IDs/probe analysis for all bytecode. “The agents can coexist” is proven narrowly; “neither perturbs the other collector” is **REASONABLE BUT NOT PROVEN** and must be measured.

### 1.8 Current experimental limitations

The following are explicit or code-visible limitations, not production-supported features:

- only initial loads, selected application packages, and ordinary class methods;
- no constructor, class-initializer, or interface-body observation;
- no cross-thread context propagation;
- shutdown-only output and no fragment/join format for ASM;
- no classloader dimension in method identity;
- no selection-engine integration or false-negative safety policy;
- no 1/3/4-shard comparison;
- no same-execution comparison with STP per-test JaCoCo data;
- generated/proxy bodies deliberately excluded;
- a Spring Data adapter pinned to one version family and narrow proxy/lifecycle conditions;
- event types for MVC, JPA/Hibernate, SQL/tables, and beans exist without corresponding implemented collectors;
- output stability was often checked after normalizing volatile fields and over small selected tests, not as repeated full-suite dependency stability.

Treating any of these as solved would be **QUESTIONABLE**.

## 2. Is one ASM instrumentation adapter per framework sound?

**No.** It is not a sound primary long-term architecture for STP. The evidence supports a narrower design: generic ASM may observe included application method bodies; framework semantics should be collected at the least invasive reliable public semantic boundary; framework-specific ASM is a fallback only when a necessary RTS dependency cannot be obtained safely through public mechanisms and the fallback is version-gated, fail-closed, and empirically complete enough.

### 2.1 Strategy comparison for safe RTS evidence

| Criterion | Strategy A: generic ASM instruments everything possible | Strategy B: ASM for application methods; public hooks/interceptors/listeners for semantics | Strategy C: framework-specific ASM |
|---|---|---|---|
| Correctness | Good for the literal fact “this bytecode body was entered”; poor at inferring logical repository, cache, transaction, endpoint, or ORM semantics. Generated delegation can be mistaken for the dependency of interest. | Best when a hook is at the semantic boundary and stable identity comes from framework metadata. Evidence type can remain explicit rather than inferred from a class name. | Can see otherwise hidden internal paths, but correctness depends on exact internal control flow and instrumentation point. Easy to confuse execution with caller semantics. |
| False-negative risk | High whenever work has no included application body: interfaces/proxies, reflection into framework code, cached calls, generated ORM access, async threads, or excluded language/framework bytecode. Instrumenting more packages reduces some gaps but adds noise and fragility. | Lowest of the three when public hooks cover actual logical calls and attribution is propagated. Still requires a declared unsupported/fail-safe policy where hooks do not cover mocks, async, or versions. | Potentially low for one pinned version/path, but often high across versions, alternative engines/proxy modes, reordered interceptors, or untested execution paths. Silent misses are dangerous for RTS. |
| Determinism | The same executed body produces a stable key, but execution/order/classloading and attribution can still vary. Instrumenting everything does not make test behavior deterministic. | Stable semantic identifiers (declared interface, handler method, entity metadata) often avoid generated-name drift. Runtime behavior and attribution still need controls. | May be deterministic on a pinned version, but internal bytecode shape and generated names can change across patch versions/configuration. |
| Version compatibility | Application bytecode is relatively stable; indiscriminate framework instrumentation is not. | Public APIs/SPIs have the strongest compatibility contract; adapters can declare supported ranges. | Weakest: coupled to method owners, descriptors, bytecode shape, and internal lifecycle. |
| Maintenance | Low for the generic application transformer, high if “everything” requires filters and semantic reconstruction. | Moderate, focused on only semantics required by RTS and framework-supported extension points. | Highest: per-version matchers, transformation tests, agent-order tests, fail-safe behavior, and rapid response to internals. |
| Runtime overhead | Entry hooks on every possible method can be substantial and produce huge volumes. | Bounded application entry hooks plus relatively few semantic boundary events. | Can be narrow, but internal hot paths may be extremely frequent and transformation costs/version checks accumulate. |
| Coupling | Generic application observation is low-coupling; framework-wide ASM becomes internal coupling in disguise. | Lowest feasible coupling when APIs are public. | Direct coupling to framework internals. |
| RTS usefulness | Useful for concrete application method edges, but incomplete alone; framework-body noise often does not map cleanly to changed application source. | Highest if emitted facts can be mapped conservatively to change units and unsupported gaps trigger safe fallback. | Useful only for a narrowly necessary semantic edge with strong compatibility and completeness gates. |

### 2.2 Framework-specific assessment

- **Spring Framework:** Application `@Component` method bodies can be observed generically when they execute. Bean identity, MVC handler mapping, transaction/cache boundaries, and proxy topology cannot be inferred reliably from entry alone. Spring exposes bean lifecycle, handler interceptors/mappings, AOP advisors, application events, and task decorators. Strategy B is preferable. Instrumenting Spring internals is version-coupled and noisy.
- **Spring Data:** The branch itself is decisive evidence. Repository interfaces have no application implementation body; generated proxies are excluded; an inner listener/advice can miss cache-hit caller calls. Public repository metadata plus audited outer proxy advice captured the desired logical call. Strategy B won here. Framework ASM might be considered only for a required unsupported proxy/version after proving no public boundary works.
- **Hibernate/JPA:** Entity application methods may not run because Hibernate can access fields or generated accessors. JPA mapping metadata is inferred structure, not observed SQL. Hibernate offers event/listener/interceptor/statistics and JDBC integration points, though version-specific details remain. Use public ORM/JPA/JDBC hooks and label mapped versus observed evidence. Instrumenting Hibernate internals risks dialect/version/enhancement coupling and should not be the default.
- **JDK proxies, CGLIB, and Byte Buddy:** Generated class/method identities are unstable and usually implementation detail. Generic ASM may see them only if filtering permits and load timing allows, but those edges are poor change keys. Normalize through declared interface/target/framework metadata. Bytecode fallback must never depend on generated names as the semantic key.
- **Caching/interceptors:** Whether the RTS dependency means “caller requested repository operation” or “underlying database execution occurred” is semantic. Entry under a cache can vanish on a hit. Entry outside it may require proxy/lifecycle knowledge. Public interceptor ordering is the appropriate mechanism where available; the Spring Data spike demonstrates this.
- **Async execution:** Instrumentation can observe worker method entry but cannot assign it to a test without context propagation. The solution belongs at executor/reactive/request context boundaries, ideally public task decorators/hooks or explicit carriers, not a new method transformer per framework. If safe attribution cannot be proven, the test/map must be marked incomplete and selection must fall back conservatively.
- **Reflection:** Once reflection invokes an included application method body, generic ASM sees entry; no special reflection adapter is needed. If reflection only manipulates fields, constructors, generated accessors, or framework code, method-entry coverage is incomplete. Instrumenting reflection internals does not recover a trustworthy application dependency by itself.
- **Language-generated bytecode:** Kotlin/Scala/Groovy compilers create synthetic bridges, defaults, companions, state machines, and lambdas with language-specific mapping rules. Generic entry can observe them but the current catalog lacks source/synthetic/bridge metadata. A source-mapping normalization layer is more maintainable than framework ASM. Preserve raw facts and do not silently discard categories until equivalence is measured.

The goal is not semantic omniscience. Add a semantic adapter only when missing evidence can cause an unsafe RTS decision or when it materially improves mapping to changed code. Unsupported cases must be observable and must cause conservative selection, not a silently sparse “stable” map.

## 3. ASM replacement audit by issue

| Topic | Current behavior and assessment | Primary issue | Decision |
|---|---|---|---|
| Method-entry semantics | One hit proves control entered an instrumented body, even if it immediately throws. It differs from JaCoCo instruction coverage thresholds and says nothing about caller semantics. | Completeness / semantic equivalence | **PROVEN BY TEST/EXPERIMENT** as entry observation; **SHOULD BE REVISITED** as replacement equivalence. |
| Constructors `<init>` | Always skipped. JaCoCo may report constructor instruction coverage. Constructor-only application behavior becomes invisible to ASM. | Completeness | **QUESTIONABLE** if constructors are RTS change units; measure before adding instrumentation complexity. |
| Static initializers `<clinit>` | Always skipped. Class-loading/lazy-init dependencies visible to JaCoCo can disappear. | Completeness, and source of apparent determinism | **QUESTIONABLE** for a fair comparison. Keep raw JaCoCo category; decide normalized policy explicitly. |
| Interface default/private methods | Whole interfaces are skipped, even executable default/private bodies. | Completeness | **QUESTIONABLE**; a likely avoidable false negative if application interfaces are in scope. |
| Bridge methods | Instrumented in accepted classes but not flagged. A call may hit bridge plus target and create noisy extra edges. | Semantic equivalence / performance | **SHOULD BE REVISITED** for normalization, not necessarily excluded. |
| Synthetic methods | Instrumented in accepted classes but not flagged. Includes compiler artifacts. | Semantic equivalence / performance | **SHOULD BE REVISITED**; preserve raw flags first. |
| Lambdas | Named synthetic lambda bodies in an accepted class are likely instrumented; runtime-generated lambda classes may be absent, filtered, hidden, or loaded outside the include. No direct test proves lambda behavior. | Completeness | **REASONABLE BUT NOT PROVEN**. |
| Generated classes | Common proxy-name patterns are excluded; coverage is incomplete if a meaningful body exists only there. Stable semantic identity is also difficult. | Completeness | **HISTORICAL SPIKE DECISION**; semantic adapter preferred. |
| FNV-1a 64 IDs | Versioned deterministic hash over UTF-8 canonical key. Stable across load order. | Mostly irrelevant to mapping determinism | **PROVEN BY TEST/EXPERIMENT** by unit test. Full keys, not hashes, must be comparison truth. |
| Collision handling | Catalog retains all colliding keys; any hit with non-unique resolution becomes unattributed and an error is emitted. | Completeness | **PROVEN BY TEST/EXPERIMENT** fail-closed behavior. Any collision invalidates safe completeness unless resolved. |
| Classloader identity | Absent from canonical key/catalog. Same named method from different defining loaders collapses. | Completeness / attribution | **SHOULD BE REVISITED** based on target runtime; do not complicate the first controlled experiment unless duplicate definitions are observed. |
| Marker-field idempotence | Any existing field named `$stp$instrumented$v1` causes the class to be skipped; marker is injected only if at least one method is instrumented. A user/compiler field can false-positive; retransformation of original bytes is not addressed. | Completeness | **PROVEN BY TEST/EXPERIMENT** for transforming already-transformed fixture bytes; **QUESTIONABLE** as general idempotence. |
| `COMPUTE_MAXS` vs frames | Straight-line entry insertion leaves control-flow/frame topology unchanged; max stack is recomputed. | Compatibility, not current drift | **PROVEN BY TEST/EXPERIMENT** for tested bytecode; use `COMPUTE_FRAMES` only if future transformations require it. |
| Retransformation | Disabled. | Completeness | **HISTORICAL SPIKE DECISION**. Avoid adding it until an actual preloaded-class gap is measured. |
| Classes loaded before transformer | Agent installs transformer in `premain`, but agent/runtime and any classes loaded before registration cannot be transformed. Dynamic attach is unsupported. | Completeness | **REASONABLE BUT NOT PROVEN** that desired application classes load later. Measure catalog against class inventory. |
| Worker/async attribution | Hits occur globally but are recorded `NO_ACTIVE_TEST` on worker threads. | Attribution / completeness | **QUESTIONABLE** for safe RTS wherever target tests use cross-thread work. |
| Leaf lifecycle | Platform start/finish includes Jupiter `BeforeEach`/`AfterEach`; container lifecycle is excluded. | Attribution | **PROVEN BY TEST/EXPERIMENT** for fixture; lifecycle must be aligned with the JaCoCo comparison. |
| Setup attribution | Per-test setup is included by ASM listener. Existing JaCoCo Platform listener also starts at leaf start, while its Jupiter fallback starts at `beforeTestExecution` and excludes `BeforeEach`. | Attribution | **SHOULD BE REVISITED**; likely comparison confounder. |
| Teardown attribution | Per-test teardown is included by ASM listener/JaCoCo Platform listener, but excluded by JaCoCo Jupiter fallback (`afterTestExecution` precedes `AfterEach`). | Attribution | **SHOULD BE REVISITED**. |
| Late events | Same-thread post-finish facts are quarantined under the finished test; other-thread late facts are merely global. | Attribution | **PROVEN BY TEST/EXPERIMENT** narrowly; incomplete origin diagnostics for async. |
| Parallel execution | Runtime buckets can synchronize, but global hook/catalog interactions and listener scopes have not been tested under parallel leaves. | Attribution / performance | **REASONABLE BUT NOT PROVEN**. |
| JaCoCo coexistence | Selected tests pass in both transformer orders and ASM normalized facts match. | Compatibility | **PROVEN BY TEST/EXPERIMENT** narrowly; non-perturbation of JaCoCo mapping remains unproven. |

The issues most likely to make ASM look deceptively stable are the skipped `<init>`, `<clinit>`, and interface bodies; generated-body exclusions; and lost worker-thread events. They reduce the observed surface. Stability comparisons are invalid unless completeness and category counts remain visible in both RAW and normalized views.

## 4. Plausible causes of current JaCoCo mapping drift

The signature is: global union effectively complete, while a small number of per-test method edges move between mappings as agent/shard count changes. That signature strongly favors **attribution ownership of shared/lazy work** over random loss of instrumentation. It is compatible with an edge being executed once globally but charged to whichever test/JVM first triggers it, with reset/dump races, or with duplicate-key overwrite during merge. It is less compatible with a stable set of classes being uninstrumented everywhere in one configuration, which should normally alter the global union too.

### 4.1 JaCoCo instrumentation/probe behavior

- JaCoCo records probes in per-class runtime arrays; probe placement can make a method “covered” when a representative instruction/probe executes, while ASM entry records every entered body. This explains collector disagreement, but by itself does not explain why the same JaCoCo configuration shifts across shard counts.
- Java-agent transformer order changes the byte array seen by later transformers. The POC confirms both orders load and execute, not that JaCoCo execution-data class IDs and per-method analysis are invariant. If agent order differs across workers, or analysis uses class bytes inconsistent with instrumented IDs, missing/mismatched data is possible. Retained experiments reported JaCoCo files but did not compare per-test JaCoCo edges.
- JaCoCo filters compiler-generated constructs during analysis. Bridge, synthetic, lambda, and generated-code reporting may differ from literal method entry. This is mainly equivalence, not evidence of shard drift.
- A broad JaCoCo defect that randomly drops probes is possible but unsupported by current evidence. Do not label it the root cause.

### 4.2 Per-test dump/reset/session behavior

- `dump(true)` operates on the one JaCoCo runtime for the entire JVM: it dumps and resets all probe state, not state belonging to the listener’s `ThreadLocal` test. With parallel tests, the first finishing test can dump probes executed by another active test and reset them before that test finishes. This can move edges while preserving the global union across all session files.
- `setSessionId` sets agent-wide session metadata, while `currentSessionId` is thread-local. Concurrent starts can overwrite the global session ID independently of which test later dumps.
- Every listener instance/thread reads, appends, and deletes the same configured `test.exec`. Concurrent finishes can race in dump, file read/append, or deletion. No synchronization or unique temporary destination is present.
- The code catches and logs reflection/file errors, then continues. Missing dumps/files need not fail the test run or map generation.
- Existing session files are appended, intentionally unioning parameterized invocations. If directories are reused without cleanup, stale data can accumulate and mask or move differences.
- `setSessionId` does not isolate probe buffers. The real isolation operation is the timing of global dump/reset.

These are high-priority code-supported hypotheses.

### 4.3 JUnit lifecycle attribution

- The Platform listener spans leaf `executionStarted` through `executionFinished`, which includes Jupiter `BeforeEach` and `AfterEach` in the POC’s launcher test.
- The Jupiter fallback uses `BeforeTestExecutionCallback`/`AfterTestExecutionCallback`, excluding `BeforeEach` and `AfterEach`. Which path is active depends on discovery/classloader behavior; the static global `JacocoPerTestListener.active` is set only when some Platform test starts.
- If both integrations are present, ordering can be subtle: a fallback callback may run before `active` has been set in some launcher/configuration, and the static flag never resets for later launcher runs in the same JVM. There is no explicit lifecycle-mode field in fragment output.
- Setup/teardown, extension callbacks, dynamic tests, container callbacks, and engine differences can therefore be included, excluded, or charged to an adjacent dump interval.

This is fully compatible with small moving per-test edges and stable global union.

### 4.4 Class loading and lazy initialization

- `<clinit>`, framework bootstrap, lazy bean/repository initialization, service loading, reflection metadata, cache initialization, and first-use code execute in whichever test/JVM reaches them first.
- Sharding changes which tests share a JVM and which test is first in each process. The same initializer may consequently attach to different tests, occur before any leaf, or repeat once per shard.
- The POC itself observed order-dependent global-versus-late configuration hits while leaf application sets stayed stable. That is direct evidence that lifecycle placement moves even in the ASM experiment, not evidence that JaCoCo probes are faulty.

This is one of the best matches for globally complete but locally moving edges.

### 4.5 Static/global state

- Caches, singletons, static guards, framework contexts, generated-accessor caches, and once-only initialization change later execution paths within a process.
- The JaCoCo runtime, listener `active` flag, metrics, and output destination are also process-global while parts of bookkeeping are thread-local or instance-local.
- More shards reset application global state more often, legitimately changing which methods each test executes even with identical tests.

### 4.6 Test ordering

- Different shard counts alter order and neighbors. Order-dependent caches/initialization can move edges without changing the suite union.
- Parameterized/repeated invocations are deliberately collapsed by the current JaCoCo session key to class+method and appended. Invocation distribution/order can alter that union if invocations are not guaranteed to stay together.
- Tests with the same method but dynamic arguments are indistinguishable in the production map; the POC listener, by contrast, retains unique Platform leaf IDs.

### 4.7 Process/shard isolation

- Each shard repeats process startup, class loading, framework context construction, static initialization, and shutdown.
- A test running alone in a shard can own initialization it did not own in a one-JVM run. Conversely, startup before the first leaf may be absent from every per-test map.
- Environment, worker JVM flags, classpaths, agent ordering, fork reuse, parallel settings, and output paths must be proven identical rather than assumed.

### 4.8 Fragment generation

- Session filenames are derived from simple test class, method, and only seven hex characters of Java `hashCode` over FQCN+method. This reduces known collisions but is not collision-free and does not encode Platform unique ID, invocation, engine, shard, or JVM.
- Parameterized invocations append into one session file by design. Concurrent writes are not guarded.
- Empty/no-line-coverage exec files are skipped during XML generation, so expected tests can disappear rather than appear with an empty dependency set.
- The XML mapper scans every `.xml` in the directory, not only a clean manifest of expected session files. Reused directories can introduce stale fragments.
- Class-byte preload keys can overwrite duplicate relative class paths from multiple class directories. Analysis failures for unreadable class files are silently skipped during preload.

### 4.9 Map join/merge

- CLI and Maven merge paths use `mergedMappings.putAll(...)`. Duplicate test keys are overwritten, not unioned or rejected.
- `File.listFiles()` order is unspecified and input files are not sorted. Therefore which fragment wins for a duplicate key can vary by filesystem/run.
- Duplicate keys are plausible when shards rerun/retry tests, parameterized invocations are split, modules share the readable key, or stale fragments are included.
- Method mappings are `class#method` without a descriptor, so overloads collapse before or during comparison. This can hide real differences or make edge accounting misleading.
- Class metrics are merged numerically even when test mappings overwrite, so aggregate-looking metadata and per-test maps can disagree conceptually.

This is a concrete deterministic-code defect for duplicate keys and must be tested before blaming either collector.

### 4.10 Test/runtime nondeterminism

- Timing, randomized data, concurrency, unordered iteration, external services, retries, conditions, and flaky tests can legitimately change executed methods.
- Same-execution dual collection controls most of this when comparing collectors, but repeated identical executions are still needed to quantify run-to-run stability.

### 4.11 Compatibility with the observed signature

| Hypothesis | Global union complete + small moving per-test edges? | Why |
|---|---:|---|
| Global JaCoCo instrumentation failure for a stable class | Usually no | Missing instrumentation tends to remove the edge from the union as well. |
| Probe/dump/reset races under parallel leaves | Yes | An edge can be captured by the wrong finishing test while remaining present somewhere. |
| Platform-vs-Jupiter lifecycle interval | Yes | Setup/teardown edges move or disappear per test while test bodies cover the union. |
| First-use/class initialization/cache ownership | Strong yes | Exactly one/few tests own a globally executed edge; sharding/order changes the owner. |
| Process/global state and changed order | Strong yes | Per-process resets change paths and ownership without losing suite coverage. |
| Duplicate test key overwritten during merge | Strong yes | One fragment wins while other fragments still contribute global/aggregate evidence elsewhere. |
| Deterministic descriptor collapse | Hides drift more than causes it | Distinct overload edges become one reported edge. |
| Genuine test nondeterminism | Yes | Same global capabilities may be exercised by different tests per run. |

## 5. Smallest ordered causal experiment

Do not begin with 1/3/4 shards and a large matrix. First make one execution auditable and separate collector semantics from lifecycle and joining.

### Step 0 — Freeze the comparator contract

Build an experiment-only manifest containing exact test Platform unique IDs, selected order, JVM/fork/parallel settings, application class inventory, collector/agent versions and order, and clean unique output directories. Preserve full `binaryClassName#methodName(descriptor)` keys. Record an explicit lifecycle phase for observations (`before-leaf`, `leaf`, `after-leaf/late`) or at minimum separate leaf dependencies from unattributed/late facts.

Fail the experiment on missing expected tests, duplicate fragment keys, transformation/analyzer errors, hash collisions, or unclean output. Do not silently skip an empty test.

### Step 1 — Same-JVM, same-execution dual collection, sequential leaves

Attach JaCoCo and ASM to one JVM, disable JUnit parallelism and fork reuse ambiguity, and let one common test identity/lifecycle coordinator delimit both collectors. For each leaf, obtain JaCoCo execution data through its runtime API and ASM facts for exactly the same interval. Write immutable per-invocation fragments keyed by Platform unique ID; do not use the production class+method filename as experimental truth.

Run the same fixed order at least five fresh JVM times. Run both agent orders if technically possible, but treat order as a perturbation check, not a way to pool results.

This step controls test execution, order, environment, class-loading history, and process state. A per-test edge present in ASM but absent in JaCoCo on the same execution is a collector/normalization question; an edge placed outside the shared interval is lifecycle; instability shared by both collectors is execution/state, not collector-specific.

### Step 2 — ASM-only, same manifest and order

Repeat at least five fresh JVMs without JaCoCo. Compare ASM RAW and normalized sets with Step 1. If ASM changes only when JaCoCo is present or when agent order changes, the dual setup perturbs it. Also compare application outcomes, loaded/transformed class catalogs, and relevant timing.

Dual instrumentation can perturb results because transformer order changes bytes presented to the second agent, both add method-entry/probe operations, JaCoCo may add `$jacoco*` members/methods, class IDs depend on class bytes, added calls can affect JIT/inlining/timing, and agent/library startup changes load order. Existing tests show compatibility, not absence of perturbation. Step 2 is therefore mandatory before interpreting dual results.

If needed, add a JaCoCo-only control after Step 2 to measure its repeated stability under the shared lifecycle coordinator. That is a small control, not a new matrix.

### Step 3 — Reverse only test order in one JVM

After collector stability is known, run one fixed reverse (or deliberately chosen first-use) order with dual collection and ASM-only. Edges that move in both collectors identify order/lazy-loading/global-state ownership. Edges that move only in one collector identify collector/lifecycle interaction. Do not compare only suite unions.

### Step 4 — Fragment and join proof without sharding

Take one already captured canonical run and deterministically partition its immutable fragments into synthetic 3- and 4-fragment inputs. Join them. The joined map must equal the original bit-for-bit after canonical serialization. Inject a duplicate test key deliberately: the join must union according to declared semantics or fail; it must never last-writer-win. This isolates join logic without rerunning tests.

### Step 5 — Real 3- then 4-shard execution

Only after Steps 1–4 pass, run the exact manifest split across three processes, then four. Compare each test only when its test inputs/scenario are semantically the same. Separate differences in raw shard fragments from differences introduced by joining. Compare per-shard startup/unattributed facts and loaded class catalogs. If process isolation legitimately changes leaf execution, that is an execution-model result rather than collector drift.

### Required measurements

For every run and view report:

- total expected Platform leaf invocations, collected leaves, missing leaves, unexpected leaves, and duplicate identities;
- count and percentage of tests with identical dependency sets;
- changed tests, with named missing and extra method edges;
- class-edge and descriptor-preserving method-edge counts/differences;
- global union difference (but never as the primary result);
- per-test Jaccard similarity, including an explicit convention for two empty sets;
- repeated-run stability for each collector: edge support frequency per test and exact-map match rate;
- setup/teardown/lifecycle-only, global unattributed, and late-event differences;
- raw fragment checksums, expected/actual fragment counts, empty/failed fragments, and duplicate keys;
- join equality against the canonical union and input-order permutation tests;
- method catalog/class inventory, skipped categories, transformation/analyzer errors, ID collisions, and agent order;
- application test outcomes and timing sufficient to detect gross perturbation.

The primary comparison unit is the set of `(Platform test unique ID, binary class, method name, JVM descriptor)` edges. A secondary production-compatible class+method projection may be shown, but it must not replace the descriptor-preserving truth.

## 6. Semantic equivalence policy

Maintain two views for every collector.

### RAW VIEW

Preserve exactly what each collector observes, with provenance and metadata:

- JaCoCo: analyzed class/method descriptor, instruction/line/branch counters, session/fragment identity, and compiler/filter status where available;
- ASM: entry hit, full method identity, access flags (`synthetic`, `bridge`, interface/default, constructor, class initializer), defining-loader fingerprint if later needed, and attribution phase;
- globally unattributed and late observations remain visible rather than discarded;
- no category is removed merely to improve similarity.

The current production JaCoCo mapper cannot supply this view because it drops `JacocoMethod.desc`. The experiment must read the XML/model descriptor or JaCoCo analysis API directly.

### COMPARABLE NORMALIZED VIEW

The default normalized unit is an application-owned executable method body with at least one relevant JaCoCo instruction covered versus at least one ASM entry, preserving descriptor. Apply these rules symmetrically and publish counts removed by each rule:

1. Restrict both collectors to the same application class inventory/code source, not merely similar package filters.
2. Exclude test classes and collector-generated artifacts such as `$jacocoInit`, `$jacocoData`, and STP marker/hook implementation from both views.
3. `<init>` and `<clinit>`: exclude from the primary comparable view because current ASM deliberately does not instrument them, but keep separate RAW category comparisons. Their per-test instability must not be used to claim ASM superiority; their absence must be counted as potential ASM completeness loss.
4. Interface default/private methods: include only after ASM instruments executable application interface bodies. Until then, exclude them from the primary comparable score and report them as a quantified ASM gap. Abstract interface declarations are never executable edges.
5. Bridge/synthetic methods: retain in RAW. For one normalized view, map a bridge to its uniquely resolvable non-bridge target using bytecode metadata; if resolution is ambiguous, retain it. Never discard all synthetic methods wholesale because lambda bodies can contain application logic.
6. Lambda-generated methods: retain application-owned `lambda$...` bodies as distinct raw methods. Optionally provide a source-parent projection, but do not merge it into the primary descriptor view until the mapping is deterministic for the target language. Hidden/generated lambda classes outside the common inventory are a completeness gap.
7. Generated methods/classes: exclude framework/proxy-generated bodies from the application-method comparable view only when both collectors exclude them and a separate semantic adapter/support policy accounts for required RTS evidence. Report their raw counts. Application build-time generated source/classes stay included if they are selectable change units.
8. JaCoCo artifacts are never application dependencies. Ignore their methods/fields in normalized comparison but preserve their presence in diagnostics for agent-order analysis.
9. Worker-thread events: do not drop them. They belong in an `executed-but-unattributed` completeness ledger. They enter a per-test comparable view only when both collectors use the same proven context carrier. A collector gets no determinism credit for losing them.
10. Method entry versus instruction coverage: count a JaCoCo method edge when its instruction covered count is greater than zero. Document JaCoCo filtering. A discrepancy should be inspected at bytecode level before calling either collector wrong.

Report exact stability and false-negative conclusions from both RAW and normalized views. A normalized view is for like-for-like diagnosis, not permission to erase unsupported execution from RTS risk analysis.

## 7. Evidence thresholds for conclusions

Use an independent execution oracle for a focused sentinel suite: explicit calls/counters or bytecode-known expected paths, plus both collectors. Neither collector may serve as the sole ground truth for the other. “Repeated” below means at least 10 fresh JVM runs per final candidate configuration after the initial five-run diagnosis, with fixed sequential order plus one reversed-order check; add representative async/proxy/interface/constructor sentinels only where those categories exist in the target suite.

### Conclusion 1 — JaCoCo is the primary cause and ASM is better

Require all of the following:

- In same-execution dual runs with a common lifecycle, application outcomes and expected leaves are identical, ASM exact per-test normalized maps match in 10/10 runs, and JaCoCo maps do not.
- The differing JaCoCo edges are observed by the execution oracle/ASM in a stable test interval but are missing or assigned to another test by JaCoCo; raw dump/fragment evidence locates the divergence before join.
- ASM-only matches dual ASM in 10/10 runs, and both agent orders do not change ASM sets, ruling out JaCoCo perturbation of ASM.
- Synthetic partition/join is exact, and real shard raw fragments show the JaCoCo divergence before joining, ruling out join as primary cause.
- For all safety-critical sentinel categories in scope, ASM has **zero observed false-negative test-to-application-method edges**. All transformations/collisions/missing classes are accounted for; unsupported tests trigger conservative fallback.
- The improvement persists in one-process and real 3/4-shard runs; do not accept only a better average Jaccard.

### Conclusion 2 — ASM does not improve determinism

Conclude this if, after shared lifecycle and normalization, either:

- ASM’s exact per-test maps vary across repeated ASM-only runs at a rate not materially lower than JaCoCo (predeclare practical improvement as at least a 90% reduction in unstable edges and 100% exact stability for safety-critical sentinels), or
- both collectors move the same edges with test order/process isolation, showing execution ownership rather than collector-specific instability, or
- dual versus ASM-only differences cannot be eliminated and ASM is sensitive to agent order/presence.

Do not call trivial removal of unsupported categories an improvement.

### Conclusion 3 — ASM is more deterministic but too incomplete for safe RTS

Conclude this if ASM meets the stability threshold above but any expected application edge is absent due to skipped constructors/static initializers/default methods, preloaded/untransformed classes, generated-only implementations, hash collision quarantine, filtering, or worker-thread attribution; or if the ASM global union is a strict unexplained subset of the oracle/common JaCoCo application union. One reproducible safety-relevant false negative is sufficient unless the affected test/category is detected and forced to conservative full/class-level selection.

### Conclusion 4 — collector is not the main problem

Require evidence that both collectors agree within the same execution/common interval, while differences appear when order, lifecycle mode, process split, or join is changed. Attribute specifically:

- order/class loading when both collectors move the same first-use edges after order reversal;
- lifecycle when phase-labelled events cross the chosen leaf boundary together;
- sharding/process state when raw per-shard executions differ but each collector agrees inside each process;
- join when canonical raw fragments are stable but merged output changes with input order or loses duplicate-key edges.

The causal perturbation must reproduce the observed edges in at least 3/3 runs; correlation with shard count alone is insufficient.

### Conclusion 5 — hybrid is justified

Require a named, non-overlapping safety benefit:

- ASM provides stable descriptor-level application method entry that JaCoCo attribution cannot provide after lifecycle fixes, **and**
- JaCoCo or a semantic adapter covers independently verified safety-critical edges ASM misses, **and**
- the union policy is deterministic, provenance-preserving, and yields zero oracle false negatives in the sentinel suite, with unsupported conditions forcing conservative fallback, **and**
- combined-agent perturbation tests pass in both orders and ASM-only/JaCoCo-only controls explain any differences.

“More data” or a higher global union is not sufficient. A hybrid must improve safe selection evidence enough to justify two collectors’ operational cost and interaction risk.

## 8. What the adapter evidence actually justifies

It does **not** justify “each framework needs its own ASM adapter.” No implemented framework adapter in this branch uses ASM. The concrete Spring Data path found that generic ASM correctly sees application `Owner`/service/controller bodies, but cannot invent a body for an interface whose execution lives in excluded generated/framework code. It also found that the semantic event of interest—two caller invocations of a cached repository method—is different from underlying execution—one repository/database execution. Bytecode entry alone cannot choose that meaning.

Public Spring Data metadata supplied stable repository interface and domain identity. Public Spring AOP supplied the caller interception boundary. Public lifecycle callbacks supplied a point where the final proxy/advisor structure could be audited. A public inner Spring Data listener was useful but semantically too deep for cache-hit caller evidence. The result is not “generic bytecode failed, therefore instrument framework bytecode”; it is “method entry and framework semantics are different evidence, and the public semantic boundary is better.”

Generic ASM can reliably claim only that an included transformed method body was entered under the current attribution context. It cannot reliably infer from that fact alone:

- which Spring bean/proxy/interface represents the call;
- whether a cached logical call occurred without underlying execution;
- repository domain semantics or derived query identity;
- transaction/interceptor ordering;
- ORM entity/table/SQL semantics;
- origin across an async thread handoff.

Public hooks are preferable where they expose these facts with declared identities. Framework-specific ASM remains defensible only as a narrowly scoped fallback when a required RTS edge has no adequate public hook, and only with explicit version detection, exact bytecode signature checks, transformation failure telemetry, compatibility tests, and a conservative fallback that prevents false-negative selection. It should not become the default adapter template.

The immediate mapping question may not need Spring Data/Hibernate semantics at all if STP selects on changed application method bodies and class-level fallback safely covers interface/configuration changes. That selection policy must be made explicit before expanding adapters. Do not build semantic coverage merely because the event model can represent it.

## 9. Immediate recommendation and next step

### Primary recommendation: REDESIGN THE EXPERIMENT BEFORE IMPLEMENTATION

Keep both existing collectors unchanged while building the narrow causal harness described in Steps 0–2. The present evidence is insufficient to choose ASM as replacement, insufficient to indict JaCoCo itself, and already exposes lifecycle/join confounders capable of producing the reported signature. Fixing production behavior first would destroy the clean causal comparison.

### Framework-specific ASM: ONLY AS FALLBACK

Use generic ASM, if it passes the experiment, for application method bodies. Use stable public framework APIs, listeners, interceptors, metadata, and context carriers for only those semantic edges required for safe RTS. Framework-specific ASM should be version-gated fallback instrumentation after a required gap and absence of a reliable public boundary are both demonstrated. The branch’s Spring Data evidence directly supports this answer.

### Smallest next implementation/experiment step

Add an experiment-only dual-collector fixture/harness—outside production selection—that:

1. uses one shared Platform unique-ID lifecycle and sequential execution;
2. records immutable per-leaf JaCoCo data and ASM entry facts from the same JVM interval;
3. retains method descriptors and RAW category/access metadata;
4. emits expected/collected test manifests, unattributed/late facts, errors, and fragment checksums;
5. compares five repeated fixed-order dual runs, followed by five identical ASM-only runs.

Start with a compact sentinel set containing an ordinary overloaded method, `BeforeEach`/`AfterEach`, constructor and static-initializer activity, an application interface default method, a lambda/synthetic path, one worker-thread method whose test joins the worker, and a first-use cached/lazy path. This is small enough to diagnose mechanisms and rich enough to prevent ASM from “winning” by observing less. Only after this harness is stable should the actual affected suite be run and synthetic join plus real 3/4-shard experiments be added.

Until that evidence exists, the safe operational posture is to retain JaCoCo mapping and conservative class-level/zero-hit fallback behavior, without treating the experimental ASM JSON or Spring Data facts as production RTS dependencies.
