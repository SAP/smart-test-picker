# Spring PetClinic ASM runtime-dependency spike plan

Status: planning only; no agent or collector code is implemented by this task.

## Scope and evidence baseline

This plan evaluates whether Smart Test Picker can augment per-test JaCoCo coverage with auditable runtime dependencies. It does not design regression-test selection, and it does not change existing Smart Test Picker behavior.

The findings below are tied to these clean working-tree revisions inspected on 2026-08-02:

- Smart Test Picker: `b7ab3b85ec69cef89c02b0b0af58f757c8f65ca7` (`https://github.com/SAP/smart-test-picker.git`).
- Spring PetClinic: `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` (`https://github.com/spring-projects/spring-petclinic.git`). This was the current local checkout of the official repository, with a commit dated 2026-07-22.

All class, method, endpoint, entity, and table names in this document come from that PetClinic revision. Results must be re-baselined if the target revision changes.

## 1. PetClinic findings

### Runtime and build

| Item | Finding |
| --- | --- |
| Java | Java 17 (`java.version` in Maven; Java 17 toolchain in Gradle). |
| Spring Boot | 4.1.0. |
| Application version | `org.springframework.samples:spring-petclinic:4.0.0-SNAPSHOT`. |
| Primary build | Both Maven Wrapper (`./mvnw`) and Gradle Wrapper (`./gradlew`) are maintained. Maven runs JaCoCo automatically; Gradle's project build does not apply JaCoCo. |
| Main test commands | `./mvnw test` or `./gradlew test`. A single Maven test is `./mvnw -Dtest=<Class>#<method> test`; the Gradle equivalent is `./gradlew test --tests '<fqcn>.<method>'`. Integration-named classes are ordinary JUnit tests, not a separate Failsafe source set. |
| Test engine | JUnit Jupiter on the JUnit Platform. Gradle explicitly calls `useJUnitPlatform()`; Boot dependency management supplies the JUnit versions. |

The build also contains optional MySQL and PostgreSQL/Testcontainers tests. The selected spike tests use the default H2 configuration and need no container.

### Test frameworks and test types

- JUnit Jupiter and AssertJ are used throughout.
- MVC slices use `@WebMvcTest`, `MockMvc`, and Spring's `@MockitoBean`; repositories in these slices are Mockito mocks.
- Persistence tests use `@DataJpaTest` with real Spring Data JPA repositories, Hibernate, transactions, and embedded H2.
- Broad tests use `@SpringBootTest`; some start a random-port web server and use `RestTemplate`.
- Dedicated MySQL and PostgreSQL tests use Testcontainers or Docker Compose integration.
- Smaller tests use plain JUnit, Jakarta Bean Validation, or `MockitoExtension`.
- `PetClinicConcurrencyTests` explicitly creates a two-thread executor and makes concurrent random-port HTTP requests.

### Packages and application components

The application root is `org.springframework.samples.petclinic`. Its relevant packages are:

- `model`: `BaseEntity`, `NamedEntity`, and `Person` base domain types.
- `owner`: owner, pet, pet-type, and visit entities; their controllers; repositories; `PetValidator`; and the Spring-managed `PetTypeFormatter`.
- `vet`: vet and specialty entities, `VetRepository`, `VetController`, and the `Vets` response wrapper.
- `system`: `WelcomeController`, `CrashController`, `CacheConfiguration`, and `WebConfiguration`.

There is no service layer in `src/main`: controllers call Spring Data repositories directly. `ClinicServiceTests` is a historical name for a repository-focused `@DataJpaTest`, not a test of a `ClinicService` class.

Spring-managed application components are the six MVC controllers (`OwnerController`, `PetController`, `VisitController`, `VetController`, `WelcomeController`, and the deliberate-error `CrashController`), `PetTypeFormatter`, and the two configuration classes. Spring also creates repository proxies and infrastructure beans. `PetValidator` is instantiated by `PetController`; it is not annotated as a Spring component.

### MVC endpoints

| HTTP mapping | Handler |
| --- | --- |
| `GET /` | `WelcomeController.welcome()` |
| `GET /oups` | `CrashController.triggerException()` |
| `GET /owners/new` | `OwnerController.initCreationForm()` |
| `POST /owners/new` | `OwnerController.processCreationForm(...)` |
| `GET /owners/find` | `OwnerController.initFindForm()` |
| `GET /owners` | `OwnerController.processFindForm(...)` |
| `GET /owners/{ownerId}/edit` | `OwnerController.initUpdateOwnerForm()` |
| `POST /owners/{ownerId}/edit` | `OwnerController.processUpdateOwnerForm(...)` |
| `GET /owners/{ownerId}` | `OwnerController.showOwner(int)` |
| `GET /owners/{ownerId}/pets/new` | `PetController.initCreationForm(...)` |
| `POST /owners/{ownerId}/pets/new` | `PetController.processCreationForm(...)` |
| `GET /owners/{ownerId}/pets/{petId}/edit` | `PetController.initUpdateForm()` |
| `POST /owners/{ownerId}/pets/{petId}/edit` | `PetController.processUpdateForm(...)` |
| `GET /owners/{ownerId}/pets/{petId}/visits/new` | `VisitController.initNewVisitForm()` |
| `POST /owners/{ownerId}/pets/{petId}/visits/new` | `VisitController.processNewVisitForm(...)` |
| `GET /vets.html` | `VetController.showVetList(...)` |
| `GET /vets` | `VetController.showResourcesVetList()` |

`PetController` has a class-level `/owners/{ownerId}` mapping. `OwnerController.findOwner(...)` and controller model/binder methods can run before mapped handler methods; method-hit output should retain these real invocations rather than pretending the handler is the only application method.

### Repositories and persistence

Persistence is Spring Data JPA backed by Hibernate:

- `OwnerRepository extends JpaRepository<Owner, Integer>` and declares `findByLastNameStartingWith(...)` plus an `Integer`-specific `findById(...)` overload.
- `PetTypeRepository extends JpaRepository<PetType, Integer>` and declares a JPQL `findPetTypes()` query.
- `VetRepository extends Repository<Vet, Integer>` and declares cached `findAll()` and `findAll(Pageable)` operations.
- There are no handwritten repository implementation classes. Runtime implementations are Spring-generated proxies; therefore ASM instrumentation of PetClinic application classes alone cannot observe repository method bodies.

Entities and declared tables are:

| Entity | Primary table | Relevant related table(s) |
| --- | --- | --- |
| `Owner` | `owners` | one-to-many pets through `pets.owner_id` |
| `Pet` | `pets` | `types` through `type_id`; `visits` through `visits.pet_id` |
| `PetType` | `types` | — |
| `Visit` | `visits` | — |
| `Vet` | `vets` | `specialties` through join table `vet_specialties` |
| `Specialty` | `specialties` | `vet_specialties` |

The H2 schema defines exactly `owners`, `pets`, `types`, `visits`, `vets`, `specialties`, and `vet_specialties`. MySQL and PostgreSQL profiles have equivalent logical tables. Entity-to-primary-table mapping is reliable metadata. A claim that a particular SQL statement touched every related table is not reliable without observing and interpreting Hibernate/JDBC activity.

### Configuration properties

Application resources define:

- custom selector `database`;
- `spring.sql.init.schema-locations` and `spring.sql.init.data-locations`, which interpolate `${database}`;
- datasource URL/user/password and `spring.sql.init.mode` in the MySQL/PostgreSQL profiles, with environment placeholders such as `MYSQL_URL` and `POSTGRES_URL`;
- `spring.thymeleaf.mode`;
- `spring.jpa.hibernate.ddl-auto`, `spring.jpa.open-in-view`, `spring.jpa.hibernate.naming.physical-strategy`, and `spring.jpa.properties.hibernate.default_batch_fetch_size`;
- `spring.messages.basename`;
- `management.endpoints.web.exposure.include`;
- `logging.level.org.springframework`;
- `spring.web.resources.cache.cachecontrol.max-age`.

No production PetClinic Java class directly uses `@Value`, `@ConfigurationProperties`, `Environment#getProperty`, or `PropertyResolver#getProperty`. These properties are consumed by Spring Boot/framework auto-configuration, often during context startup before a test begins. Consequently, a first spike restricted to application code cannot honestly report per-test configuration-property reads. Test-only property use (for example `@LocalServerPort`, a test `@Value`, or the PostgreSQL test's environment inspection) is not evidence of an application-code read.

### Execution and existing transformers

- No JUnit parallel-execution property, Surefire parallel setting, or Gradle parallel test setting exists. JUnit Jupiter therefore runs test methods sequentially by default within each test JVM. Build tools may still use separate processes when explicitly configured by a caller; the collector must partition output by JVM/run ID.
- `PetClinicConcurrencyTests` creates application concurrency inside one test.
- Random-port `@SpringBootTest` HTTP handlers run on server threads even when JUnit itself is sequential.
- Maven configures `jacoco-maven-plugin` 0.8.15 with `prepare-agent`, so the spike must coexist with a JaCoCo transformer on Maven runs. Gradle does not configure PetClinic JaCoCo.
- PetClinic does not explicitly declare an ASM agent, Byte Buddy agent, or `mockito-inline`. Spring test starters bring Mockito transitively, and modern Mockito uses Byte Buddy (including runtime instrumentation for inline mocking). The exact resolved versions must be recorded in the implementation PR rather than inferred from transitive declarations.
- Testcontainers and Spring infrastructure may generate proxies but are not reasons to instrument third-party bytecode.

## 2. Selected tests and manually expected paths

The paths below distinguish an invocation from its implementation. A mocked repository invocation is still a useful repository dependency, but it is not a database interaction.

### Controller slice: `OwnerControllerTests#processCreationFormSuccess`

Expected path:

```text
org.springframework.samples.petclinic.owner.OwnerControllerTests#processCreationFormSuccess
→ MockMvc POST /owners/new
→ OwnerController.findOwner(null) [@ModelAttribute; creates Owner]
→ OwnerController.setAllowedFields(...) [@InitBinder]
→ OwnerController.processCreationForm(...)
→ OwnerRepository.save(Owner) [Mockito-backed Spring bean invocation]
→ entity Owner [repository domain metadata]
```

Expected endpoint: `POST /owners/new`, handler `OwnerController.processCreationForm(...)`, bean name normally `ownerController`. Expected table set is empty: `OwnerRepository` is an `@MockitoBean`, so no SQL runs and claiming `owners` as an observed table would be false. The output may expose `owners` only as separately labelled static/domain mapping metadata, not as a touched table.

### Repository slice: `ClinicServiceTests#shouldInsertOwner`

Expected path:

```text
org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner
→ OwnerRepository.findByLastNameStartingWith("Schultz", PageRequest)
→ entity Owner
→ table owners [SELECT]
→ OwnerRepository.save(Owner)
→ entity Owner
→ table owners [INSERT; flush may occur before the second query]
→ OwnerRepository.findByLastNameStartingWith("Schultz", PageRequest)
→ entity Owner
→ table owners [SELECT]
```

This `@DataJpaTest` has no MVC endpoint and no application controller/service method. Repository identity must therefore come from Spring Data integration, not from application-class method-entry instrumentation. Exact flush timing is an observed-result detail; the validation should require the insert to be attributed to this test, not a particular position among buffered events.

### Broad integration: `PetClinicIntegrationTests#findAll`

Expected path:

```text
org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll
→ VetRepository.findAll()
→ cache "vets" miss
→ entity Vet
→ tables vets, vet_specialties, specialties [Hibernate load of eager specialties]
→ VetRepository.findAll()
→ cache "vets" hit; no second database query
```

This test loads the complete `@SpringBootTest` context and real persistence/cache infrastructure but performs no HTTP request. It is deliberately preferred over `ownerDetails()` for the first three-test gate because it avoids cross-thread propagation while proving broad-context compatibility and a persistence-specific dependency. `ownerDetails()` should be the first follow-up context-propagation experiment; it executes `OwnerController.showOwner(int)` on a web-server thread and loads `Owner`/`Pet`/`PetType`/`Visit` data.

## 3. Smallest useful spike and data-source matrix

The minimal spike records deduplicated dependencies, not call edges or invocation order. It uses one method-entry hook in selected PetClinic classes and semantic adapters at framework extension points.

| Information | ASM alone | Required integration | First-spike confidence and boundary |
| --- | --- | --- | --- |
| JUnit test identity | No | JUnit Platform `TestExecutionListener` | Reliable for leaf test identifiers; lifecycle callbacks outside a leaf test remain unattributed. |
| PetClinic method entry | Yes | Agent transformer plus runtime hook | Reliable for loaded, included application methods. It proves execution, not causality beyond the active test context. |
| Spring bean identity | No | Spring bean lifecycle/registry integration; runtime maps receiver identity or target class to bean metadata | Reliable for unique application bean instances. Proxies, aliases, scoped beans, and multiple beans of one class require instance-aware mapping and ambiguity reporting. |
| MVC endpoint | No | Spring MVC `HandlerInterceptor` using `HandlerMethod` and `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` | Reliable for handler, HTTP verb, and matched route pattern when request context is attributed. Do not derive routes only by scanning annotations. |
| Repository interaction | No | Spring Data `RepositoryMethodInvocationListener` (or a narrowly scoped Spring AOP interceptor if unavailable) | Reliable method and repository/domain identity. It must capture mocked repository beans too, or explicitly report that gap. |
| Entity identity | Not as runtime semantics | Spring Data repository metadata and/or JPA/Hibernate metamodel/events | Reliable when exposed by repository metadata or Hibernate entity events. Annotation scanning alone is static metadata. |
| Database table | No | Hibernate/JPA metadata plus Hibernate events; optional captured SQL for audit | Primary entity table is reliable as mapping metadata. Actual tables touched by reads, joins, native SQL, or schema initialization are not generally reliable from entity metadata alone. Conservative PetClinic SQL correlation can be experimental and must retain raw SQL/evidence. |
| Configuration-property read | No | Spring `Environment`/property-resolution integration | Not reliable per selected test: most reads occur during shared context startup and intercepting all framework property access is noisy/version-sensitive. Defer from success gate and record the limitation. |
| Cross-thread attribution | No | Explicit context carrier/instrumented framework boundary | Not needed for the selected three tests, but required for random-port HTTP and executor tests. |

### Explicit non-goals for the first spike

- No basic-block, line, branch, data-value, allocation, or complete call tracing.
- No general SQL parser and no claim that an entity mapping equals an observed table access.
- No per-test configuration dependencies until a focused experiment shows a real application-time read.
- No regression selection; all three selected tests run completely.
- No ML, dashboard, flaky-test analysis, mutation testing, remote storage, or commercial functionality.

## 4. Proposed architecture

The schema and APIs below are provisional. The first golden outputs must be reviewed before they are stabilized.

| Module | Responsibility and initial public API | Dependencies | Home and lifetime |
| --- | --- | --- | --- |
| `stp-runtime` | Bootstrap-visible, low-overhead event sink and attribution context. API sketch: `hit(long methodId, Object receiver)`, `record(Event)`, `beginTest(TestKey)`, `endTest(TestKey)`, `captureContext()`, `withContext(ContextToken, Runnable)`, `flush(Path)`. Owns deduplication, run/JVM identity, unattributed events, and JSON DTOs. | JDK only in the hook path; a tiny JSON writer or isolated serialization layer outside the hot path. No ASM, Spring, JUnit, or Hibernate. | Main Smart Test Picker repository; production candidate, initially marked experimental. |
| `stp-agent` | `premain`, argument parsing, class filtering, method catalog generation, ASM transformation, runtime bootstrap wiring, metrics, and shutdown flush. Public surface is the agent manifest and documented agent arguments; transformer internals remain private. | ASM, shaded/relocated; `stp-runtime`; JDK instrumentation API. | Main repository; production candidate, experimental until spike passes. |
| `stp-junit-adapter` | Converts JUnit Platform leaf-test start/finish callbacks to `beginTest`/`endTest`, including unique ID, engine, class, and method/source metadata. SPI registration is its public integration surface. | `stp-runtime`, JUnit Platform Launcher API. | Main repository; production candidate. Keep separate from existing JaCoCo listener during the spike to avoid behavior changes. |
| `stp-spring-adapter` | Registers bean-instance metadata, MVC handler events, and Spring Data repository-invocation events. API consists of opt-in Spring configuration/registrars and adapter event types; no PetClinic package references. | `stp-runtime`; compile-time Spring Framework/Data APIs. Hibernate-specific code must not leak into this module. | Main repository; production candidate, initially experimental and optional. |
| `stp-persistence-hibernate-adapter` | Optional Hibernate-version-specific entity/load/SQL evidence integration and entity-to-table metamodel resolution. Preserves evidence and confidence instead of emitting unqualified table strings. | `stp-runtime`, Hibernate/JPA SPI matching Boot 4.1. | Main repository only if the experiment is portable; otherwise keep temporary under the spike module. Experimental. |
| `stp-petclinic-spike` | PetClinic revision lock, launch configuration, selected-test fixtures, expected/golden JSON assertions, and measurement scripts. It may provide opt-in test configuration without changing PetClinic production sources. | All spike modules plus PetClinic test/runtime dependencies. | Main Smart Test Picker repository; temporary spike code, never a published production artifact. |

Do not fold the spike into `smart-test-picker-core` initially. That module currently controls per-test JaCoCo sessions through both a JUnit Platform listener and Jupiter extension. Keeping the new collector opt-in prevents changed behavior and makes agent-vs-JaCoCo comparisons possible. Reuse of test identity concepts can be evaluated after the spike.

### Provisional runtime model

Start with an internal evidence-rich model, then render the requested simple projections. A candidate output is:

```json
{
  "schemaVersion": "spike-1",
  "runId": "per-test-jvm-run-id",
  "testId": "[engine:junit-jupiter]/[class:...]/[method:...]",
  "displayName": "fully.qualified.Class#method",
  "methods": [],
  "springBeans": [],
  "endpoints": [],
  "repositories": [],
  "entities": [],
  "tables": [],
  "configurationProperties": [],
  "unattributedEvents": [],
  "metrics": {}
}
```

Each semantic item should carry `source` (for example `asm-method-entry`, `spring-mvc`, `spring-data`, `hibernate-event`), a stable identity, and where relevant `confidence`/`evidence`. Collections are sorted deterministically. Counts can be retained separately; the dependency set must not grow once per repeated hit. Do not finalize field shapes until actual outputs from all three tests expose proxy names, overloads, table evidence, and startup events.

## 5. Instrumentation design

### Included classes

Instrument only loaded, non-interface application classes whose binary names start with `org.springframework.samples.petclinic.`. In the first experiment:

- include production controllers, domain classes, formatter, validator, configuration, application bootstrap, and runtime-hints classes;
- exclude `package-info` and interfaces because they have no executable method bodies;
- do not instrument test classes for dependency collection. JUnit supplies test identity, and timing the tests does not require rewriting them;
- optionally narrow to `owner`, `vet`, and `system` after a baseline class-count measurement, but keep the explicit include prefix configurable.

Instrument constructors only after the initial controller experiment shows they add useful information. The minimum is concrete, non-abstract method entry, including private methods and synthetic lambda bodies when they are in the application package. Record synthetic/bridge flags in the method catalog so reviewers can filter noise without silently losing evidence.

Repository interfaces (`OwnerRepository`, `PetTypeRepository`, `VetRepository`) are catalogued as metadata but not transformed. Repository invocations come from Spring Data integration.

### Exclusions

Hard-exclude:

- the agent, runtime, and adapter packages, including relocated ASM;
- `java.*`, `javax.*`, `jakarta.*`, `jdk.*`, `sun.*`, and JVM-generated classes;
- `org.objectweb.asm.*` and its relocated namespace;
- JUnit, Spring, Hibernate, JaCoCo, Mockito, Byte Buddy, database drivers, and build-tool classes;
- dynamic proxy names (`jdk.proxy*`, `com.sun.proxy.*`) and generated classes containing markers such as `$$`, unless a later semantic adapter has a narrowly justified transformer;
- array/module descriptors, annotations, interfaces, and classes without code.

Filters must inspect `className`, loader, protection domain, and classfile bytes without loading the class. Transformation failure must fail open for PetClinic (return original bytes), increment a visible error metric, and be a spike failure if it affects an expected class.

### Method-entry hook and IDs

Method entry is sufficient for the selected application-method question. Inject one static call immediately after constructor initialization (if constructors are enabled) or at the first instruction of ordinary methods. The call records a numeric method ID and optionally the receiver needed for bean-instance correlation. It must not allocate on every hit or capture arguments, return values, stack traces, caller edges, or timestamps.

A stable method key is:

```text
binary-class-name + "#" + method-name + JVM-method-descriptor
```

Assign a deterministic 64-bit ID by a specified, versioned hash of the UTF-8 key (for example xxHash64 with a fixed seed), and write the ID-to-key catalog into the output. Detect collisions while transforming; on collision retain the full key or use a deterministic secondary ID and report the collision. IDs are stable for an unchanged signature, independent of class-load order, but are not promised stable after a rename/signature change.

### ASM packaging and valid bytecode

Shade ASM into a private namespace such as `com.sap.oss.smarttestpicker.internal.asm` in the agent JAR. Relocate ASM packages and exclude their metadata/signatures as required by the build; do not expose ASM types in any public API. Pin a version that supports the target Java 17 classfile version and test it against the actual PetClinic bytecode.

Prefer `ClassReader.EXPAND_FRAMES` and `ClassWriter.COMPUTE_FRAMES | COMPUTE_MAXS`, with a loader-aware `getCommonSuperClass` implementation that avoids initializing application classes. Verify transformed output with ASM `CheckClassAdapter` in transformer unit tests and by JVM verification (`-Xverify:all`) in a focused run. If loader-aware frame computation cannot safely resolve types, preserve existing frames and use an `AdviceAdapter`-style entry insertion that does not introduce new control-flow joins; never silently emit guessed invalid frames.

### Coexistence with JaCoCo and Mockito/Byte Buddy

Multiple transformers see the bytes produced by earlier transformers in registration order. The validation matrix must include:

1. STP agent only on Gradle;
2. JaCoCo only on Maven;
3. both agents with `-javaagent:jacoco...` before STP;
4. both agents with STP before JaCoCo if the build can configure that order;
5. MVC tests that create `@MockitoBean` mocks and therefore exercise Mockito/Byte Buddy.

The STP transformer must be idempotent (use a private marker attribute or detect its hook), must not request retransformation in the first spike, and must never transform JaCoCo/Mockito/Byte Buddy classes. Avoid transforming dynamically generated repository/mock classes. Compare outcomes and expected dependencies across orders; duplicate hooks, verification errors, mock failures, or missing expected PetClinic methods fail coexistence.

### Test identity and context propagation

Use a JUnit Platform `TestExecutionListener` and only begin/end context for identifiers where `TestIdentifier.isTest()` is true. Prefer the Platform unique ID as canonical identity and retain `MethodSource` class/method as a readable label. Containers, setup outside a leaf test, context bootstrap, and shutdown events belong in `unattributedEvents` with lifecycle phase—not in the nearest test by guesswork.

An ordinary `ThreadLocal<TestKey>` is adequate only for synchronous work on the JUnit thread. It fails when:

- random-port HTTP handling moves from the test thread to a server thread;
- executors were created before an `InheritableThreadLocal` value existed or pool threads are reused;
- reactive/callback work switches threads;
- work outlives the test and is incorrectly charged to the next test.

The selected controller test uses MockMvc synchronously, and the two selected repository calls stay on the test thread; no selected test requires async propagation. PetClinic as a whole does: `PetClinicIntegrationTests#ownerDetails`/`ownerList` cross an HTTP boundary, and `PetClinicConcurrencyTests` uses an executor plus server threads.

After the three-test gate, propagate an opaque run/test context explicitly at supported boundaries: capture/wrap executor tasks and add a test-only HTTP correlation header on the client, then restore/clear it in a Spring server interceptor. Never expose the full test name as an HTTP header, never trust such a header outside an explicitly enabled test process, and record late/unknown tokens as unattributed. This is a separate experiment, not hidden use of `InheritableThreadLocal`.

## 6. Risks and unknowns

| Risk/unknown | Consequence | Planned check or containment |
| --- | --- | --- |
| Spring Data listener does not observe a Mockito replacement repository | Controller test misses repository semantics. | Verify explicitly; if necessary add a narrowly scoped Spring AOP advisor around beans implementing known repository interfaces and tag its source. Do not instrument Mockito internals. |
| Repository proxy target/bean names vary | Unstable JSON and false bean identities. | Normalize to declared repository interface plus Spring bean name; preserve raw proxy class only as evidence. |
| Hibernate event SPI/version changes | Adapter becomes Boot/Hibernate-version-specific. | Isolate it in its own optional module and pin/test the PetClinic version. Stop promotion if only internal APIs work. |
| Entity table mapping overstates actual SQL | False table dependencies, especially cached calls and joins. | Separate `mappedTable` from `observedTable`; require Hibernate/SQL evidence for `observedTable`. Preserve cache-hit/no-query distinction. |
| SQL parsing is dialect-sensitive | Missed/incorrect aliases, quoted names, CTEs, native SQL. | No general parser in spike. A conservative PetClinic-only parser may emit experimental evidence and must never upgrade uncertainty silently. |
| Shared Spring context starts outside tests | Configuration, schema-init, cache, and class-load events contaminate first test. | Keep a process-level unattributed bucket; never assign pre-test events to the first test. |
| Work completes after test end | Late events leak into another test or disappear. | Tokens include test/run generation; closed tokens route to unattributed `late-event`. Assert contamination sentinels. |
| JUnit listener ordering with existing STP listeners | Duplicate or incorrectly nested lifecycle handling. | Spike adapter owns a separate context only; test with and without `smart-test-picker-core`, then design consolidation separately. |
| Agent order changes transformed bytes | JaCoCo probes or hooks disappear/duplicate. | Run both agent orders and compare method catalog/outcomes. No retransformation initially. |
| Method hook receiver retention leaks contexts | Memory growth and distorted timing. | Use weak identity registration or immutable class/bean IDs; never retain arbitrary bean graphs from hit events. |
| First-run/classloading noise dominates timing | Misleading overhead over three small tests. | Warm up, use repeated forked runs, report median and p95 plus raw samples. Separate JVM startup, transformation, and test duration. |
| Configuration reads are startup-scoped | Per-test property output would be misleading. | Leave selected-test property arrays empty and document unattributed startup reads only if a later focused interceptor is justified. |

## 7. Validation experiments

### Common protocol and measurements

Create an explicit include file for exactly the three complete tests; do not filter individual invocations inside them and do not perform regression selection. For each configuration, run at least one warm-up and five fresh-JVM measured repetitions. Preserve command, JVM/build versions, resolved dependency versions, agent order, exit code, and raw timing data.

For baseline and agent runs record:

- test pass/fail/skip outcomes and assertion counts when available;
- wall-clock total and per-test duration;
- cumulative transformer time and per-class distribution;
- number of considered, transformed, skipped, and failed classes;
- raw and unique method hits per test;
- repository, bean, endpoint, entity, and table event counts;
- JSON byte size per test and total;
- unattributed event count by reason/lifecycle phase;
- late events, unknown-context events, and cross-test contamination sentinels.

Overhead is `(median agent duration - median baseline duration) / median baseline duration`. Report p95 and samples as context. The `<10%` gate applies to the aggregate selected-test workload for this exploratory spike, not production.

Manual verification uses the cited source methods, controller annotations plus Spring MVC's runtime `HandlerMethod`, repository declarations, entity annotations, H2 schema, Hibernate SQL logs/statistics, and cache statistics. A human reviewer must be able to trace every semantic JSON item to one of those evidence sources.

### Experiment A: controller slice

Run only `OwnerControllerTests#processCreationFormSuccess` with Spring MVC and repository adapters enabled.

- Expected methods: at minimum `OwnerController.findOwner(...)`, `OwnerController.setAllowedFields(...)`, and `OwnerController.processCreationForm(...)`. Extra executed PetClinic model getters/setters are valid when catalogued; framework methods must not appear in `methods`.
- Expected Spring metadata: `ownerController`; repository mock normalized to `OwnerRepository`/its bean name. Ambiguous bean mapping must be reported, not guessed.
- Expected endpoint: exactly `POST /owners/new` with the `OwnerController.processCreationForm(...)` handler.
- Expected persistence metadata: one `OwnerRepository.save(Owner)` interaction and entity `Owner`; zero observed database tables/SQL.
- Incorrect attribution: any event from either other selected test; any observed `owners` table; a GET endpoint; framework methods presented as PetClinic methods; setup activity reported under another test.
- Missing attribution: any of the three controller methods above, endpoint, controller bean, or repository invocation absent.

Verify the mock call with the existing test behavior plus adapter evidence; a small future golden assertion may add Mockito `verify`, but the planning task does not alter PetClinic.

### Experiment B: repository slice

Run only `ClinicServiceTests#shouldInsertOwner` with repository and persistence adapters enabled.

- Expected application-method dependencies: model accessor/mutator methods that actually execute. There is no controller/service method to expect.
- Expected Spring metadata: normalized `OwnerRepository` proxy/bean and three logical repository calls (query, save, query).
- Expected database metadata: `Owner` and observed `owners` SELECT/INSERT evidence. No endpoint. No pet/vet table should be attributed solely because relationships exist.
- Manual verification: compare repository call records with the test source; enable Hibernate SQL/statistics for the run; compare SQL evidence with `Owner`'s `@Table(name="owners")` and H2 schema. Account explicitly for transaction flush timing.
- Incorrect attribution: `pets`, `vets`, an MVC endpoint, a repository call from another test, or an INSERT claimed when SQL/event evidence shows none.
- Missing attribution: either query call, save call, Owner entity, or owners-table read/write evidence absent.

### Experiment C: broad integration and cache

Run only `PetClinicIntegrationTests#findAll` in the full Spring context.

- Expected application methods: PetClinic entity/model methods may execute; no controller handler should execute.
- Expected Spring metadata: normalized `VetRepository` bean/proxy and two `findAll()` invocations; cache `vets` miss then hit if cache event capture is included. Cache identity is useful optional evidence, not a required output field in spike schema v1.
- Expected database metadata: first invocation loads `Vet` and eager specialties, with evidence consistent with `vets`, `vet_specialties`, and `specialties`; second invocation issues no equivalent SQL because of `@Cacheable("vets")`.
- Manual verification: Hibernate SQL log/statistics and JCache statistics, checked against `Vet` mappings and H2 schema.
- Incorrect attribution: an endpoint; owner/pet/visit tables; two database query groups when the second call is demonstrably cached; startup schema-init SQL charged to this test.
- Missing attribution: either repository invocation, Vet identity, or reliable evidence of at least `vets` access absent. Missing join-table evidence is recorded as a persistence-adapter limitation unless the adapter claims complete observed-table extraction.

### Follow-up experiment D: cross-thread HTTP (not in the initial gate)

Run `PetClinicIntegrationTests#ownerDetails` first without, then with explicit HTTP context propagation.

Expected propagated path is `GET /owners/{ownerId}` → `OwnerController.showOwner(int)` → `OwnerRepository.findById(Integer)` → `Owner` and its eagerly loaded graph/tables. Without propagation, server-side events must be unattributed—not silently assigned. With propagation, all handler/repository/persistence events must belong to `ownerDetails`, and the context must be cleared before another request/test. Only after this passes should executor propagation be attempted with `PetClinicConcurrencyTests`.

### Cross-test contamination check

Run all three selected tests in one JVM in at least two deterministic orders. Define each test's exclusive sentinels:

- controller: `POST /owners/new`, no observed SQL;
- repository: owners INSERT/select, no endpoint;
- broad: `VetRepository.findAll`, no endpoint or owners INSERT.

Any sentinel in another test's record is significant contamination. Startup/shutdown work in `unattributedEvents` is acceptable when reasoned and bounded; moving it to a test to reduce the unattributed count is not.

## 8. Success and stop criteria

### Proceed criteria

The initial spike passes only when all are true:

1. Every expected synchronous event is attributed to the correct Platform test identity in isolated and combined runs.
2. Baseline and agent runs have identical outcomes.
3. Output demonstrates more than `test → class`: at least method entry plus repository or endpoint relationships.
4. At least one Spring/persistence dependency is reliable: the MVC matched endpoint and the Spring Data repository identity are mandatory; `owners` table evidence is the persistence target.
5. JSON is deterministic, evidence-labelled, understandable, and manually auditable.
6. Median aggregate selected-test overhead is below 10%, with transformation time reported separately.
7. No significant cross-test contamination occurs; specifically, no exclusive sentinel crosses tests, no startup work is assigned to the first test, and late events are quarantined.

Configuration-property attribution and asynchronous propagation are not initial success requirements because the selected code does not provide a reliable synchronous application property read and the initial test set intentionally isolates same-thread feasibility.

### Narrow criteria

Narrow the production claim to `test → method → Spring bean/endpoint/repository/entity` if method, bean, endpoint, and repository attribution pass but actual table attribution requires dialect-specific SQL parsing or Hibernate internals. Retain mapped entity/table metadata only with an explicit `mapped`, not `observed`, label. Also narrow to synchronous tests until follow-up experiment D proves explicit context propagation.

### Stop criteria

Stop this approach, rather than adding broader instrumentation, if any of these persists after one focused correction:

- reliable leaf-test attribution cannot be achieved without modifying test semantics;
- expected PetClinic methods disappear or tests change outcome under either JaCoCo agent order;
- only class-level information is produced;
- Spring endpoint/repository identity depends on unstable private internals;
- cross-test events cannot be quarantined;
- aggregate overhead remains at or above 10% after deduplication and include-scope tuning;
- the JSON cannot distinguish observed events from inferred/static mapping.

Failure to obtain per-test configuration properties alone is not a stop condition; it is an expected first-spike limitation.

## 9. Sequence of independently reviewable implementation tasks

Each item is intended to be one small pull request. Do not begin later semantic breadth until the preceding evidence is reviewable.

1. **Freeze the experiment contract.** Add the PetClinic revision, three exact test selectors, baseline commands, expected-event manifest, measurement format, and fixture licensing/provenance to `stp-petclinic-spike`. No agent code.
2. **Add the runtime event model.** Introduce experimental `stp-runtime` DTOs, canonical test/method keys, evidence/confidence fields, deterministic JSON ordering, and unattributed-reason tests. No JUnit/Spring dependencies.
3. **Add test context lifecycle.** Implement `stp-junit-adapter` with Platform unique IDs, begin/end semantics, closed-token quarantine, and tests for containers, failures, skips, and sequential methods. Do not integrate JaCoCo lifecycle yet.
4. **Create the minimal agent shell.** Add `premain`, validated arguments, bootstrap/runtime visibility, include/exclude decisions, metrics, and a no-op transformer integration test.
5. **Add ASM method-entry transformation.** Shade/relocate ASM, implement deterministic IDs/catalog/collision handling, inject one allocation-free hook, verify frames and idempotence, and instrument a tiny fixture before PetClinic.
6. **Run PetClinic method-only experiment.** Execute the three tests with JUnit attribution plus PetClinic method entries; commit only scripts/golden summaries and document gaps. This PR is an explicit decision gate.
7. **Add Spring bean and MVC adapter.** Register application bean-instance metadata and a `HandlerInterceptor`; validate matched pattern, verb, and `HandlerMethod` on the controller test without static route guessing.
8. **Add Spring Data repository adapter.** Capture declared repository interface, method descriptor, bean name, outcome, and domain type. Test real proxies and `@MockitoBean`; add the narrow advisor fallback only if the public listener cannot cover mocks.
9. **Add reliable entity/table evidence.** First map repository domain types to JPA primary-table metadata; then add isolated Hibernate load/write evidence sufficient for `shouldInsertOwner`. Keep `mapped` and `observed` distinct and preserve raw evidence IDs.
10. **Validate cache/eager relationship behavior.** Run `PetClinicIntegrationTests#findAll`, establish whether `vet_specialties` and `specialties` can be reported reliably, and document cache-hit behavior. Drop any table claim that cannot be audited.
11. **Run compatibility and performance matrix.** Test Gradle/Maven, agent-only/JaCoCo-only/both agent orders, and Mockito/Byte Buddy cases; collect all required metrics over repeated fresh JVMs and run contamination orders.
12. **Review and version the spike schema.** Compare real outputs, remove proxy/version noise, decide which fields are stable enough for an experimental schema, and publish the proceed/narrow/stop decision. Do not connect it to selection.
13. **Prototype HTTP propagation separately.** Only after the synchronous gate, implement opt-in opaque HTTP context propagation and validate `PetClinicIntegrationTests#ownerDetails`, including missing/forged/late-token handling.
14. **Evaluate executor propagation separately.** If HTTP propagation succeeds and the use case warrants it, wrap supported task submissions and validate `PetClinicConcurrencyTests`; do not globally instrument every JDK concurrency class.
15. **Evaluate configuration reads only with a real use case.** Identify an application-time PetClinic property read or add an isolated fixture. If none exists, close the field as unsupported for this spike instead of intercepting all Boot startup resolution.

## Recommendation

**Proceed with a narrowed spike:** implement synchronous `test → application method → Spring bean/endpoint/repository → entity`, and attempt evidence-labelled primary-table attribution for the real JPA test. Do not make configuration-property reads, arbitrary SQL-table extraction, or cross-thread attribution part of the first gate. This scope is realistically collectible using ASM plus public JUnit/Spring/persistence integration points, while preserving a clear boundary between observed runtime facts and inferred JPA metadata.
