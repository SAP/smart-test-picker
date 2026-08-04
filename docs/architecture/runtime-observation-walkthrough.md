# Experimental runtime observation architecture walkthrough

## 1. System overview

The implemented experimental path enriches ordinary per-test method evidence
with caller-level Spring Data repository evidence. It runs only in a test JVM
and does not participate in Smart Test Picker's regression-test selection.

```text
test JVM starts
  → STP Java agent premain
  → shared RuntimeContextService installed
  → JUnit listener opens test context
  → ASM hooks record application methods
  → Spring Data advisor records repository calls
  → events enter one RuntimeEventAggregator
  → agent writes deterministic JSON at JVM shutdown
```

The system has four distinct lifecycles:

1. **JVM lifecycle:** `premain` creates and registers the runtime, installs the
   method hook and transformer, and registers the shutdown writer.
2. **Spring application-context lifecycle:** the optional adapter activates,
   collects repository metadata, classifies final repository products, and only
   after all regular singletons exist inserts and audits its advisor.
3. **JUnit lifecycle:** a Platform listener opens one same-thread scope for a
   leaf test and closes it with its terminal test result.
4. **Repository invocation lifecycle:** an audited caller advisor calls
   `proceed()` once and records exactly one terminal success or failure event.

### Component diagram

```text
┌──────────────────────────── test JVM ─────────────────────────────┐
│                                                                   │
│  stp-agent                                                        │
│  ┌─────────────┐    installs       ┌───────────────────────────┐  │
│  │ premain +   │───────────────────▶│ RuntimeContextRegistry    │  │
│  │ ASM xformer │                    │ RuntimeContextService     │  │
│  └──────┬──────┘                    │ RuntimeEventAggregator    │  │
│         │ method-entry hook         └─────────────▲─────────────┘  │
│         ▼                                         │ record         │
│  application methods ──▶ RuntimeHooks ────────────┤                │
│                                                   │                │
│  JUnit Platform ──▶ stp-junit-adapter ── context ─┤                │
│                                                   │                │
│  Spring context ─▶ stp-spring-data-adapter ─ event┘                │
│                                                                   │
│  shutdown: agent serializer ─────────────────────▶ spike-2 JSON    │
└───────────────────────────────────────────────────────────────────┘

Outside the reusable path:
  stp-spring-data-e2e-fixture  = internal executable proof
  stp-petclinic-spike          = pinned external reproduction/evidence
```

## 2. Module responsibilities

### `stp-runtime`

- **Responsibility:** framework-neutral test identity, runtime context,
  immutable events, deterministic aggregation, and canonical JSON.
- **Important public API:** `RuntimeContextRegistry`,
  `RuntimeContextService`, `RuntimeEventAggregator`, `RuntimeHooks`,
  `RuntimeJsonSerializer`, and immutable types under `runtime.model`.
- **Package-private implementation:** event-fact conversion, snapshots, bucket
  state, comparisons, and JSON helper details.
- **Dependencies:** JDK only.
- **Owns:** the single attribution service contract and `spike-2` event model.
- **Must never own:** JUnit, Spring, ASM, agent startup, framework discovery, or
  test selection.
- **Status:** production candidate with an explicitly experimental schema/API.

### `stp-agent`

- **Responsibility:** JVM entry, configuration, class filtering, ASM
  transformation, method catalog, agent metrics, runtime ownership, and final
  output.
- **Important public API:** only `StpAgent`, because the JVM manifest needs its
  public `premain` entry.
- **Package-private implementation:** configuration, transformers, hashing,
  catalog, metrics, output writer, and `AgentRuntime`.
- **Dependencies:** `stp-runtime`, ASM 9.8, JDK instrumentation API; the shaded
  agent also includes the JUnit listener classes/service descriptor.
- **Owns:** creation and shutdown of the one runtime instance and the ASM method
  hook sink.
- **Must never own:** Spring metadata, repository semantics, application
  contexts, or repository-based selection.
- **Status:** experimental production candidate; not a stable published agent.

### `stp-junit-adapter`

- **Responsibility:** translate JUnit Platform leaf-test callbacks into
  `beginTest`/`endTest` calls and terminal test status.
- **Important public API:** `StpRuntimeTestExecutionListener`.
- **Package-private implementation:** none of architectural significance; its
  behavior is intentionally small and direct.
- **Dependencies:** `stp-runtime` and JUnit Platform Launcher/Engine APIs.
- **Owns:** JUnit identity extraction and listener-local duplicate/finished
  tracking.
- **Must never own:** a runtime registry, aggregator, fallback service, ASM,
  Spring, or async propagation.
- **Status:** experimental production candidate, bundled into the agent for
  service-loader discovery.

### `stp-spring-data-adapter`

- **Responsibility:** optional Spring discovery, public repository metadata,
  eligibility, safe in-place caller-advisor insertion, structural audit,
  terminal repository events, and bounded diagnostics/metrics.
- **Important public API:** `StpSpringDataApplicationContextInitializer` and
  `SpringDataAdapterProperties` (`stp.spring-data.enabled`).
- **Package-private implementation:** activation state, metadata/eligibility
  registries, processors, fingerprints, advisor/advice, audit results,
  diagnostics, method descriptor builder, recorder, and metrics.
- **Dependencies:** `stp-runtime`; compile-time public APIs from Spring Beans,
  Context, AOP, and Spring Data Commons. It embeds none of them.
- **Owns:** repository semantic observation inside one application context.
- **Must never own:** the shared runtime, test lifecycle, agent, another proxy,
  mock support, SQL/Hibernate semantics, or RTS selection.
- **Status:** optional, unpublished experimental adapter.

### `stp-spring-data-e2e-fixture`

- **Responsibility:** executable proof that one JVM combines JUnit identity,
  ASM methods, Spring Data repository facts, caching, failure behavior,
  deterministic JSON, disabled mode, and both JaCoCo orders.
- **Public API/package-private implementation:** none; it is test fixture code.
- **Dependencies:** Spring Boot/Data JPA/Cache 4.1.0, H2, and all real STP
  experimental modules needed by the test JVM.
- **Owns:** fixture application, golden normalization, counters, and validator.
- **Must never own:** reusable runtime or adapter behavior.
- **Status:** unpublished experimental test fixture.

### `stp-petclinic-spike`

- **Responsibility:** pin PetClinic commit
  `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, run the acceptance matrix,
  normalize evidence, validate expected facts, and retain performance results.
- **Public API/package-private implementation:** none; Ruby runners and data
  contracts are reproduction tooling.
- **Dependencies:** the PetClinic Maven Wrapper, built experimental JARs,
  diagnostic fixture JAR, and JaCoCo 0.8.15.
- **Owns:** external-project commands and reviewed evidence.
- **Must never own:** reusable application or adapter code, and it never changes
  PetClinic source or `pom.xml`.
- **Status:** reproduction code and research evidence, not an artifact.

No production-candidate module depends on either fixture/spike module.

## 3. Shared runtime ownership

`AgentRuntime` creates one `RuntimeEventAggregator(runId, jvmId)`, wraps it in
one `RuntimeContextService`, installs that service through
`RuntimeContextRegistry.install`, and installs its method-ID consumer through
`RuntimeHooks.install`.

Consumers are deliberately asymmetric:

- the agent **owns** and installs the service;
- the service-loaded JUnit listener reads it once in its no-argument
  constructor;
- Spring Data advice resolves it lazily at event-recording time;
- ASM-injected code calls only `RuntimeHooks.methodHit(long)`; the agent-owned
  sink converts the ID into a `MethodHitEvent` and uses the same service.

There must be exactly one service because two aggregators would allow a JUnit
listener to open a context in one runtime while ASM or Spring recorded into
another. The resulting JSON would appear valid but lose attribution.

`RuntimeContextRegistry` is process-local and thread-safe. Installation uses an
atomic compare-and-set and rejects a second active registration. Closing a
registration removes only the exact service it installed, so a stale close
cannot remove a newer owner. `current()` returns `Optional.empty()` when no
service exists and never constructs a fallback.

On normal shutdown, the agent snapshots method hits and the aggregator, writes
the complete output, and closes registry and hook registrations in `finally`.
Failed initialization closes any partial registrations before rethrowing. This
ordering keeps the runtime available until output construction is complete and
prevents stale state in sequential JVM runs.

### JVM startup and shutdown sequence

```text
JVM             StpAgent/AgentRuntime       Registry/Hooks       Transformer/output
 │ -javaagent            │                        │                       │
 ├──────────────────────▶│ premain                │                       │
 │                       │ parse + validate path  │                       │
 │                       │ create aggregator/service                      │
 │                       ├───────────────────────▶│ install service       │
 │                       ├───────────────────────▶│ install method sink   │
 │                       ├───────────────────────────────────────────────▶│ add transformer
 │                       │ register shutdown hook │                       │
 │ application/tests run │                        │                       │
 │ shutdown              │                        │                       │
 ├──────────────────────▶│ snapshot + serialize ────────────────────────▶│ write JSON
 │                       ├───────────────────────▶│ close registrations   │
 │ exits                 │                        │                       │
```

`RuntimeContextService` uses `ThreadLocal<TestIdentity>`. It rejects conflicting
nested activation, clears the active test in `finally`, and remembers the last
finished test on that thread only so a subsequent event is classified as late
rather than assigned to the next test. It does not propagate context to
executors, reactive chains, HTTP server threads, or other thread handoffs.

## 4. JUnit attribution

The agent JAR contains:

```text
META-INF/services/org.junit.platform.launcher.TestExecutionListener
  → com.sap.oss.smarttestpicker.junit.StpRuntimeTestExecutionListener
```

Only identifiers for which `TestIdentifier.isTest()` is true open a context.
Engines, classes, nested containers, parameterized/repeated containers, and
dynamic-test factories do not.

The JUnit Platform unique ID is authoritative. Canonical test equality consists
of `platformUniqueId`, `runId`, and `jvmId`; display name, class, method, and
engine are readable metadata. Consequently ordinary tests, each parameterized
invocation, each repetition, nested leaf tests, and dynamic leaf tests remain
distinct even when readable class/method values are absent or shared.

The supported startup assumption is explicit: agent `premain` installs
`RuntimeContextRegistry` before JUnit Platform constructs the service-loaded
listener. The no-argument listener captures the service during construction.
If constructed with an empty registry it remains a no-op even if a runtime is
installed later; there is no lazy lookup or fallback. An isolated child-JVM test
launches with the real `-javaagent`, loads the listener through `ServiceLoader`,
and proves it opens a context in the premain-owned service.

Tests verified the Jupiter callback interval used by the fixture: leaf
`executionStarted` occurs before `@BeforeEach`, and leaf `executionFinished`
occurs after `@AfterEach`. Same-thread events in both lifecycle methods are
therefore inside the leaf context. `@BeforeAll`, `@AfterAll`, discovery, engine
startup, and container callbacks remain outside it.

### JUnit test lifecycle sequence

```text
JUnit Platform       STP listener       RuntimeContextService      Aggregator
     │ executionStarted(leaf) │                   │                    │
     ├───────────────────────▶│ beginTest(id)     │                    │
     │                        ├──────────────────▶│ beginTest(id)      │
     │ @BeforeEach            │                   ├───────────────────▶│
     │ test body              │        record(event, active id) ─────▶│
     │ @AfterEach             │                   │                    │
     │ executionFinished      │                   │                    │
     ├───────────────────────▶│ endTest(id,result)│                    │
     │                        ├──────────────────▶│ mark finished ────▶│
     │                        │                   │ clear active       │
```

Success, failure, and abort map to immutable `SUCCESSFUL`, `FAILED`, and
`ABORTED` results. Only failure type and message are retained, not the throwable
or stack trace. Cleanup still occurs on failure.

With no active or recently finished test on the current thread, an event becomes
a global `NO_ACTIVE_TEST`. An event after closure is sent to the finished test
bucket; the aggregator sees that the bucket is closed and stores a per-test
`LATE_EVENT`. Beginning the next test clears the thread's last-finished marker,
preventing silent reuse. Async work is unsupported because another thread has
neither the active nor last-finished `ThreadLocal` state.

## 5. ASM method observation

The agent accepts normalized application package prefixes. The spike default is
`org.springframework.samples.petclinic.`. Exclusions always include JDK
packages, JUnit, Spring, Hibernate, Mockito, Byte Buddy, JaCoCo, and STP itself.
The transformer also ignores test-class output locations and generated names
such as `$$`, Mockito, Byte Buddy, and Hibernate proxy/instantiator forms.

For each accepted concrete ordinary method, ASM injects one call at entry:

```text
RuntimeHooks.methodHit(<stable 64-bit ID>)
```

The canonical method key is:

```text
binaryClassName#methodName(JVM descriptor)
```

The versioned `fnv1a64-v1` algorithm hashes the UTF-8 key with the standard
FNV-1a 64-bit offset basis `0xcbf29ce484222325` and prime
`0x100000001b3`. `MethodCatalog` retains the full `MethodIdentity` for every ID.
If two distinct keys collide, the catalog keeps both, reports the collision,
and the ambiguous hit becomes unattributed instead of being merged.

Constructors, class initializers, `$jacocoInit`, abstract methods, native
methods, interfaces, annotations, and `module-info` are skipped. A private
synthetic static final field named `$stp$instrumented$v1` marks transformed
classes; seeing it prevents a second hook. ASM computes maximum stack size while
preserving existing frames. Tests use ASM verification and `-Xverify:all`.

ASM 9.8 is shaded and relocated to
`com.sap.oss.smarttestpicker.internal.asm`; application-visible
`org.objectweb.asm` packages are absent from the agent JAR. Both JaCoCo-before-
STP and STP-before-JaCoCo orders were exercised without duplicate hooks or
verification/linkage failures.

At runtime, the method ID is counted globally, resolved through the catalog,
and converted to an `ASM_METHOD_ENTRY`/`OBSERVED` event. The active JUnit scope
then determines its test bucket. Equal method facts deduplicate while retaining
the raw `count`.

ASM supplies execution identity, not framework semantics. It cannot reliably
name a Spring bean, MVC endpoint, repository interface/domain, SQL table, or
configuration property by itself.

Task 6 demonstrated this boundary on PetClinic: the controller test produced
actual `OwnerController` and model method facts, while a repository-only
integration test could produce zero PetClinic method bodies because work ran in
excluded generated/framework code. That was a correct ASM result and the reason
the Spring Data semantic adapter was needed.

## 6. Spring Data adapter lifecycle

The adapter JAR is an explicit test-classpath artifact. Spring discovers
`StpSpringDataApplicationContextInitializer` from `META-INF/spring.factories`.
The initializer is disabled unless `stp.spring-data.enabled` is exactly `true`.
Malformed values fail clearly. Missing Spring Data or missing shared runtime
produces a bounded disabled state; it never creates a fallback.

### Repository initialization sequence

```text
ApplicationContextInitializer
  │ activation checks: property + Spring Data + shared runtime
  ▼
RepositoryMetadataCollector (before factory initialization)
  │ RepositoryFactoryBeanSupport public customizer
  │ RepositoryProxyPostProcessor reads RepositoryInformation only
  ▼
RepositoryEligibilityProcessor (final product after initialization)
  │ classify; return exact same bean; no mutation
  ▼
SmartInitializingSingleton.afterSingletonsInstantiated()
  │ resolve final canonical bean
  │ verify same eligible identity/context/metadata
  │ capture final proxy fingerprint
  │ advised.addAdvisor(0, context-owned STP advisor)
  │ audit immediately
  ▼
AUDIT_PASSED → enable terminal event recording
```

The fingerprint captures bean and target-source identity, proxy kind, exposed
interfaces, original advisor object identities/order, cache-advisor positions,
and safely inspectable proxy depth. Audit proves that exactly one owned advisor
is at index zero, all original advisors remain exactly once and in relative
order, STP precedes every `CacheInterceptor`, and no bean, target, interface,
proxy-kind, or proxy-depth change occurred.

On audit failure the adapter removes only its exact advisor and proves the
original fingerprint is restored. If restoration cannot be proven, explicitly
enabled context initialization fails rather than continuing with uncertain
semantics. Recording is enabled only after `AUDIT_PASSED`.

The earlier implementation inserted during repository product
`postProcessAfterInitialization`. In PetClinic,
`PersistenceExceptionTranslationPostProcessor` subsequently added a
`PersistenceExceptionTranslationAdvisor`. The safety audit correctly reported
`EXISTING_ADVISORS_CHANGED`; the fingerprint had been taken before Spring
finished the chain. Lifecycle diagnostics proved
`SmartInitializingSingleton.afterSingletonsInstantiated()` was the earliest
stable public point for both Owner and Vet contexts, before their first relevant
calls. The implementation was moved there; there is no second insertion path.

### Spring Data observation state machine

These architectural names do not introduce new public enums. Concrete audit
states remain `INSERTED_DISABLED`, `AUDIT_PASSED`,
`AUDIT_FAILED_RESTORED`, and `AUDIT_FAILED_RESTORE_FAILED`.

| State / transition | Owning component | Lifecycle phase | Proxy mutated? | Recording allowed? |
|---|---|---|---:|---:|
| `DISCOVERED` | `StpSpringDataApplicationContextInitializer` | context initialization/infrastructure registration | No | No |
| `DISCOVERED → METADATA_VALID` | `RepositoryMetadataCollector` and metadata registry | factory before-initialization and public proxy-post-processor callback | No | No |
| `METADATA_VALID → ELIGIBLE` | `RepositoryEligibilityProcessor` and eligibility registry | final product `postProcessAfterInitialization` | No; exact bean returned | No |
| `ELIGIBLE → INSERTED_DISABLED` | `RepositoryAdvisorInstallationRegistry` | `afterSingletonsInstantiated`, after final lookup/fingerprint | Yes: exactly `addAdvisor(0)` | No |
| `INSERTED_DISABLED → AUDIT_PASSED` | installation registry audit | immediately after insertion in the same callback | Already mutated; structure verified | Enabled only after transition |
| `AUDIT_PASSED → RECORDING` | `CallerBoundaryAdvice` | each later synchronous invocation | No further mutation | Yes, one terminal attempt per call |
| `UNSUPPORTED` | metadata/eligibility registries | metadata validation or product classification | No | No |
| `AUDIT_FAILED_RESTORED` | installation registry | immediate audit failure and verified removal | Attempted, then fully restored | No |
| `AUDIT_FAILED_RESTORE_FAILED` | installation registry | audit failure with unprovable restoration | Uncertain; context startup fails | No |
| `LATE_CREATED_UNSUPPORTED` | eligibility registry (`REPOSITORY_CREATED_AFTER_INSERTION_PHASE`) | product appears after insertion phase closes | No | No |

There is no fallback transition from a failure state to inner repository-
execution observation.

## 7. Repository metadata and eligibility

The metadata collector observes public `RepositoryFactoryBeanSupport` before
initialization. It installs a public repository factory customizer and public
`RepositoryProxyPostProcessor` solely to obtain `RepositoryInformation`; it
adds no invocation advice at that inner factory boundary.

The immutable context registry stores:

- canonical bean name, after removing FactoryBean dereference syntax;
- sorted aliases resolving to that canonical name;
- the authoritative public repository interface from
  `RepositoryInformation.getRepositoryInterface()`;
- domain type from `RepositoryInformation.getDomainType()`;
- provenance (`RepositoryInformation` via proxy post-processing);
- application-context identity.

It never derives identity from generated proxy classes, proxy-interface order,
arguments, return values, or entity annotations. Equal duplicate metadata is
idempotent. Conflicting interface, domain, alias, or context metadata becomes a
diagnostic; the adapter does not choose an arbitrary candidate.

Product eligibility additionally requires matching real-factory metadata,
consistent canonical name/context, exposed repository interface, unambiguous
domain metadata, public `Advised`, a Spring AOP proxy, an inspectable target
source, no existing STP marker, and an unprocessed exact object identity.

This factory-provenance requirement naturally excludes `@MockitoBean` and other
mock replacements without a Mockito dependency or mock-class inspection.
Current code recognizes JDK, class-based, and non-AOP proxy kinds but accepts
only JDK proxies. Class-based support is therefore **not implemented**.

Repositories first created after `afterSingletonsInstantiated()` remain
unsupported. There is no lazy insertion on first invocation because that would
weaken audit-before-recording and could lose or recursively duplicate the first
call.

Diagnostics have deterministic enum names and bounded canonical-bean samples.
They cover invalid/missing/conflicting metadata, alias/customizer failures,
missing factory provenance, non-`Advised`/unsupported proxies, interface or
context mismatch, duplicate identity, existing STP marker, infrastructure
beans, late creation, audit structure/order failures, restoration failure, and
recording failure metrics. They retain no bean graph, argument, result, stack
trace, or generated proxy name as canonical identity and are cleared with the
context.

## 8. Caller-boundary semantics

Repository facts mean **the exposed repository bean was invoked by its caller**.
They do not claim a repository implementation or database execution occurred.

The advisor is inserted at index zero of the existing proxy:

```text
caller
  → STP caller advisor
  → CacheInterceptor
  → Spring Data repository proxy/interceptors
  → TransactionInterceptor (where configured in the existing chain)
  → repository implementation/database
```

### Cached invocation sequence

```text
Test                  STP advisor        CacheInterceptor      Repository
 │ findAll() #1            │                    │                  │
 ├────────────────────────▶│ count caller       │                  │
 │                         ├───────────────────▶│ cache miss       │
 │                         │                    ├─────────────────▶│ execute #1
 │                         │◀───────────────────┴──────────────────┤
 │◀────────────────────────┤ terminal SUCCEEDED                    │
 │ findAll() #2            │                    │                  │
 ├────────────────────────▶│ count caller       │                  │
 │                         ├───────────────────▶│ cache hit        │
 │                         │◀───────────────────┤ (no repository call)
 │◀────────────────────────┤ terminal SUCCEEDED                    │

STP repository count = 2; underlying execution count = 1
```

Pinned PetClinic proves this with `VetRepository.findAll()`: the test invokes
the interface twice, STP records `count=2`, the inner public Spring Data listener
sees one execution, and the second result comes from Spring Cache.

Caller semantics are useful for dependency analysis because the test depends on
the repository contract even when a cache satisfies the call. Silently falling
back to an inner execution semantic would make dependency facts vary with cache
warmth and would miss real caller dependencies.

## 9. Repository event model

One `RepositoryInvocationEvent` contains:

| Field | Meaning |
|---|---|
| `repositoryKind` | Provenance of repository observation; currently only `SPRING_DATA_PROXY`. |
| `repositoryInterface` | Authoritative binary interface name from factory metadata. |
| `beanName` | Canonical Spring bean name, never generated proxy name. |
| `methodName` | Reflective invoked method name. |
| `jvmDescriptor` | Exact erased descriptor of that reflective `Method`. |
| `domainType` | Domain binary name from `RepositoryInformation`. |
| `outcome` | Terminal `SUCCEEDED` or `FAILED`. |
| `evidenceSource` | `SPRING_DATA`. |
| `certainty` | `OBSERVED`. |
| `count` | Aggregated number of otherwise equal caller invocations. |

Generic CRUD methods retain their real erasure, for example:

```text
save(Ljava/lang/Object;)Ljava/lang/Object;
```

`save(Owner)Owner` is never invented; specialization belongs in `domainType`.
Overloads remain distinct because the descriptor participates in identity.

The advice records terminal events only. It calls `proceed()` exactly once,
returns the exact result reference, and rethrows the exact repository throwable.
Runtime lookup, event construction, and recording are isolated in `safeRecord`;
their failure changes metrics but cannot change repository behavior or mask an
original failure. Successful and failed facts do not merge. Arguments, return
values, SQL, entity state, stack traces, and exception objects are never
serialized.

### Throwable-isolation policy

Instrumentation and observation hooks must not change application behavior.
`RuntimeHooks.methodHit`, transformer failure handling, and repository event
construction/recording therefore isolate `Throwable`, not only ordinary
exceptions. At these narrowly scoped best-effort boundaries, a serious JVM
error may also be swallowed and evidence may be lost. This is intentional for
the current experiment. It does not apply to repository/application failures:
`CallerBoundaryAdvice` catches those only to attempt a terminal `FAILED` event
and then rethrows the exact original throwable. Agent initialization failures
also clean up partial state and propagate rather than pretending startup worked.

## 10. Runtime JSON (`spike-2`)

`RuntimeJsonSerializer` emits timestamp-free JSON with stable field order,
canonical test ordering, deterministic semantic-item ordering, and counts for
deduplicated facts. The agent embeds that document as `runtimeEvents` alongside
its configuration, method catalog, hit totals, transformation metrics, and
errors.

A normalized excerpt, shortened from the maintained E2E golden, is:

```json
{
  "schemaVersion": "spike-2",
  "runId": "e2e-run",
  "jvmId": "pid-12345",
  "tests": [
    {
      "testId": "[engine:junit-jupiter]/[class:example.springdatae2e.tests.SpringDataRuntimeE2eTest]/[method:cachedSuccessfulGraph()]",
      "displayName": "cachedSuccessfulGraph()",
      "testClass": "example.springdatae2e.tests.SpringDataRuntimeE2eTest",
      "testMethod": "cachedSuccessfulGraph",
      "engineId": "junit-jupiter",
      "result": {"status":"SUCCESSFUL","failureType":null,"failureMessage":null},
      "methods": [
        {
          "methodId": "3888295155728124686",
          "method": "example.springdatae2e.app.SampleService#cachedLookupTwice()V",
          "evidenceSource": "ASM_METHOD_ENTRY",
          "certainty": "OBSERVED",
          "count": 1
        }
      ],
      "springBeans": [],
      "endpoints": [],
      "repositories": [
        {
          "repositoryKind": "SPRING_DATA_PROXY",
          "repositoryInterface": "example.springdatae2e.app.SampleRepository",
          "beanName": "sampleRepository",
          "methodName": "findCachedByName",
          "jvmDescriptor": "(Ljava/lang/String;)Ljava/util/List;",
          "domainType": "example.springdatae2e.app.SampleEntity",
          "outcome": "SUCCEEDED",
          "evidenceSource": "SPRING_DATA",
          "certainty": "OBSERVED",
          "count": 2
        }
      ],
      "entities": [],
      "tables": {"mapped": [], "observed": []},
      "unattributedEvents": [],
      "metrics": {"rawMethodHits": 2, "uniqueMethodHits": 2}
    }
  ],
  "unattributedEvents": [
    {
      "reason": "NO_ACTIVE_TEST",
      "eventType": "REPOSITORY",
      "eventIdentity": "example.springdatae2e.app.SampleRepository#save(Ljava/lang/Object;)Ljava/lang/Object;",
      "evidenceSource": "SPRING_DATA",
      "certainty": "OBSERVED",
      "count": 1
    }
  ]
}
```

The method ID shown above is taken from the maintained E2E run and matches its
agent catalog; the excerpt omits the second method fact only for brevity. The
schema remains experimental: it is not a stable
compatibility promise, is not consumed by RTS selection, and still contains
future event categories that have models but no implemented Spring MVC,
persistence, or table adapters.

If shutdown output writing fails, the agent appends an in-memory error and
reports the failure on stderr. The output file that failed cannot also carry the
newly discovered write error, so stderr is the authoritative notification for
that path; persistence is not retried or redirected in this experiment.

## 11. Proven validation

### Internal ASM fixture

The agent fixture validates ordinary/static/private/overloaded/synchronized/
branching/try-catch/throwing methods, behavior preservation, collision handling,
idempotence, ASM verification, `-Xverify:all`, deterministic output, relocation,
and absence of original ASM packages.

### Spring Data E2E fixture

Five fresh test JVM modes prove:

- ASM service method and Spring Data repository event share one test ID;
- cached caller count is two while underlying execution is one;
- a failed repository invocation produces one `FAILED` fact and preserves the
  exact throwable;
- startup `save` and seed method stay globally `NO_ACTIVE_TEST`;
- sequential Alpha/Beta facts do not contaminate each other;
- repeated output normalizes identically;
- adapter-disabled mode preserves method-only output;
- JaCoCo before STP and STP before JaCoCo both pass.

### Pinned Spring PetClinic

At commit `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, individual and combined Owner/
Vet runs, alphabetical and reverse order, both JaCoCo orders, and disabled mode
validated:

```text
OwnerRepository.findByLastNameStartingWith count=2
OwnerRepository.save count=1
VetRepository.findAll count=2
underlying Vet execution count=1
```

Each audited repository retained the same bean, JDK proxy, target, interfaces,
proxy depth, and original advisor identities/order; exactly one STP advisor was
at index zero. No repository facts crossed tests, no startup repository event
entered a test, no late event entered another test, and disabled mode emitted no
repository facts.

Measured fresh-JVM medians were 9.497 s baseline and 9.944 s for ASM plus the
adapter: approximately **4.71% combined median overhead**. This clears the spike
gate but is not a production benchmark: it covers two tests in one application,
includes JVM/build noise, and had a 15.434 s outlier.

## 12. Bugs found during development

### Eligibility bug: factory mistaken for repository product

- **Symptom:** the real repository proxy was not eligible in the E2E fixture;
  metadata existed, but classification produced a non-`Advised`/duplicate path.
- **Root cause:** `RepositoryFactoryBeanSupport` itself passed through the late
  eligibility processor and was treated as if it were the repository product.
  The actual exposed product arrived later and conflicted with that result.
- **Why tests caught it:** the E2E test required a real repository event under
  the same JUnit identity as an ASM service method; none could be recorded.
- **Correction:** the eligibility BPP explicitly leaves factory objects to the
  metadata collector and classifies only final repository products.
- **Lesson:** factory metadata provenance and product eligibility are separate
  phases and must not share object identity assumptions.

### Lifecycle bug: fingerprint captured before advisor chain stabilized

- **Symptom:** `vetRepository` could reach `AUDIT_PASSED` in one context, while
  Owner/full contexts lacked an audited advisor or restoration reported
  `EXISTING_ADVISORS_CHANGED`.
- **Root cause:** insertion and the original fingerprint occurred during
  product `postProcessAfterInitialization`. Spring's
  `PersistenceExceptionTranslationPostProcessor` later appended its advisor,
  legitimately changing the chain after STP's snapshot.
- **Why tests caught it:** strict audit compared exact advisor object identity
  and relative order, and pinned PetClinic exercised contexts with different
  infrastructure composition. The failure was not relaxed or hidden.
- **Correction:** eligibility remains in bean post-processing, but final lookup,
  fingerprint, index-zero insertion, immediate audit, and recording enablement
  now run once in `afterSingletonsInstantiated`.
- **Lesson:** observation must begin only after a proven stable public lifecycle
  boundary; audit failure is evidence, not a reason to weaken invariants.

## 13. Supported and unsupported scope

| Experimentally supported | Unsupported / no compatibility claim |
|---|---|
| Java 17+ | Spring Boot 3.x |
| Spring Boot 4.1.x | Mocked repositories, including `@MockitoBean` |
| Spring Framework 7.0.x | Reactive or async repository completion |
| Spring Data Commons/JPA 4.1.x | Repositories created after `afterSingletonsInstantiated` |
| JUnit Platform leaf tests | Non-`Advised` repository products |
| Synchronous same-thread attribution | Class-based proxy support |
| Real, unambiguous Spring Data repositories | MongoDB, Redis, and other Spring Data stores |
| Existing `Advised` JDK repository proxies | SQL, Hibernate/JDBC, entity-state, or table observation |
| Caller-level terminal success/failure facts | Repository-aware RTS selection |

Support is based on the internal fixture and pinned PetClinic, not API
similarity with other framework versions or stores.

## 14. How to run it

Build and validate all maintained internal runtime-observation modules:

```bash
./gradlew validateSpringDataObservation
```

Run only the complete internal Spring Data fixture matrix and golden validator:

```bash
./gradlew :stp-spring-data-e2e-fixture:validateE2eFixture
```

Build artifacts, clone/check out pinned PetClinic when needed, run Owner/Vet
acceptance, normalize, and validate:

```bash
ruby stp-petclinic-spike/spring-data-acceptance/run_acceptance.rb
```

An application test JVM needs two distinct artifacts:

1. `stp-agent/build/libs/stp-agent-experimental.jar` passed with `-javaagent`;
2. `stp-spring-data-adapter/build/libs/stp-spring-data-adapter.jar` explicitly
   on the test runtime classpath.

The adapter is not bundled into the agent. A representative invocation is:

```text
-javaagent:/path/stp-agent-experimental.jar=output=/path/stp.json;includes=com.example.;runId=run-1;debug=false;instrumentation=on
-Dstp.spring-data.enabled=true
```

Agent options are semicolon-separated; include/exclude prefix values are comma-
separated. `instrumentation=off` keeps the agent/runtime shell but disables ASM
transformation. Removing the Spring Data property or setting it to `false`
disables repository observation. The full Gradle/Maven test-classpath examples
are in `docs/spring-data-adapter.md`.

## 15. Design principles established so far

1. **Observed and inferred facts are different.** Evidence source and certainty
   are part of semantic identity; mapped data must not be presented as runtime
   observation.
2. **Unsupported means diagnosed, not guessed.** Ambiguous metadata, unsupported
   proxies, late products, and missing prerequisites produce bounded evidence or
   safe disablement.
3. **Framework adapters provide semantics ASM cannot.** ASM reports executed
   application methods; Spring Data reports repository interface/domain/caller
   semantics.
4. **Instrumentation must not change application behavior.** Hooks isolate
   failures; repository advice proceeds once and preserves exact result and
   throwable identity.
5. **One shared runtime owns attribution.** Agent, JUnit, ASM, and optional
   adapters must never create competing aggregators.
6. **Audit before recording.** An advisor is behavior-only until the final
   structure is proven safe.
7. **No silent fallback to weaker semantics.** Caller-level observation never
   degrades into cache-dependent inner repository-execution semantics.
8. **Adapters follow semantic boundaries.** The useful boundary is “Spring Data
   repository caller,” not one adapter for every Spring module or internal
   implementation class.

The next architectural decision should be based on another real-project
validation before repository facts are considered for RTS selection.
