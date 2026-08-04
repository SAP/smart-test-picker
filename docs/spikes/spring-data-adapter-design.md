# Spring Data Observability Spike

## Status

This document replaces the premature Spring Data adapter design. It defines a
one-day API exploration whose purpose is to collect evidence before choosing an
architecture.

No `stp-spring-data-adapter` module is designed or implemented in this phase.
No integration mechanism is selected in advance.

## Goal

Determine the smallest public Spring or Spring Data API that can reliably
observe:

```text
repository interface
→ repository method
→ domain entity
→ invocation outcome
```

The exploration must establish what Spring Data actually emits for concrete
repository calls before Smart Test Picker chooses a listener, advisor, proxy,
bean post-processor, mock-specific mechanism, or combination of mechanisms.

This phase is successful when it produces reproducible observations and a
decision matrix. It is not successful merely because one candidate API can be
made to run.

## Explicit non-goals

Do not add or integrate:

- Smart Test Picker agent code;
- ASM instrumentation;
- `stp-runtime` events or JSON;
- JUnit listener integration;
- an `stp-spring-data-adapter` module;
- Spring Boot auto-configuration for STP;
- SQL parsing;
- Hibernate or JDBC listeners;
- table or MVC endpoint events;
- async, reactive, HTTP, or executor context propagation;
- argument or return-value capture;
- stack traces;
- test-selection behavior.

The exploration may use a disposable Spring test fixture. Its raw observations
must not be presented as the final STP event schema.

## Questions that must be answered first

The spike must answer these questions with observed output, not design
assumptions:

1. Which public hooks observe a Spring Data repository invocation?
2. Which repository methods does each hook observe: declared query methods,
   inherited CRUD methods, default methods, custom fragments, and overloaded
   methods?
3. Does a hook run before invocation, after invocation, or both?
4. What exact repository interface and reflective `Method` does it expose?
5. What exact erased JVM descriptor follows from that `Method`?
6. Does it expose repository metadata and domain type directly?
7. Does it expose successful and failed outcomes?
8. Does it see a call served from Spring Cache, or only underlying repository
   execution?
9. Where does it run relative to cache and transaction advice?
10. Does it observe invocations made during context startup?
11. Does it observe a real Spring Data proxy and an `@MockitoBean` replacement?
12. If it sees a mock, can it distinguish application invocations from stubbing
    and verification?
13. Does observation alter proxy identity, transaction behavior, cache behavior,
    Mockito behavior, or exception propagation?
14. What is the order when multiple public hooks observe the same invocation?

No adapter architecture should be approved until these questions have recorded
answers for the target versions.

## Target versions and applications

Use the pinned Spring PetClinic revision:

```text
88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
```

Its relevant resolved versions are:

```text
Spring Boot 4.1.0
Spring Data Commons 4.1.0
Spring Framework 7.0.8
Mockito 5.23.0
JaCoCo 0.8.15
```

Use two targets:

1. a tiny disposable Spring fixture for controlled API exploration;
2. the three pinned PetClinic tests for confirmation after the fixture results
   are understood.

The fixture isolates API behavior. PetClinic confirms that the behavior holds
for the actual application. Neither target contains STP runtime integration.

## Public integration points to explore

The following are candidates to measure, not selected solutions.

### 1. `RepositoryMethodInvocationListener`

Known public surface in Spring Data Commons 4.1.0:

- `RepositoryFactorySupport.addInvocationListener(...)`;
- callback after an invocation;
- repository interface;
- reflective method;
- invocation result state and error;
- duration.

The experiment must determine which PetClinic method categories reach this
listener, whether inherited CRUD calls are included, and whether a cached
second `VetRepository.findAll()` reaches it.

### 2. Spring AOP advice on a repository proxy

Explore advice installed using public Spring Data/Spring AOP surfaces. Record:

- whether advice is outside or inside cache and transaction advice;
- which method object is visible;
- whether both cached and uncached calls are visible;
- exact advisor ordering;
- whether adding advice changes behavior.

Do not assume an advisor is required merely because it can observe more calls.

### 3. `RepositoryProxyPostProcessor`

Explore it only as a public point for adding diagnostic advice during
repository proxy construction. Record the `RepositoryInformation` it exposes
and the resulting advisor order. Do not turn this experiment into the adapter
architecture.

### 4. Repository factory customization

Explore the public `RepositoryFactoryCustomizer` and
`RepositoryFactoryBeanSupport.addRepositoryFactoryCustomizer(...)` route.
Record exactly how a customizer can be attached to the fixture repository
factory and whether it applies to all intended repositories.

The experiment must distinguish:

- what the invocation hook observes;
- how the hook is registered.

A registration mechanism is not itself evidence that the invocation mechanism
is correct.

### 5. Bean post-processing or external proxy wrapping

Explore this only to measure behavior that the repository-specific public APIs
cannot expose. Record whether wrapping changes:

- bean type and identity;
- interface selection;
- qualifiers and canonical bean name;
- transaction and cache behavior;
- equality and serialization;
- mock reset, stubbing, and verification.

Do not accept wrapping if it changes any target test result or observable
application behavior.

### 6. Mockito public listeners and mock metadata

Explore only public Mockito APIs, including mock creation settings, invocation
listeners, mock creation listeners, and `MockingDetails`.

Record:

- whether a listener can be attached to an existing `@MockitoBean`;
- whether it must be supplied when the mock is created;
- whether it reports stubbing declarations;
- whether it reports verification calls;
- whether it reports the real controller invocation;
- method and failure information exposed;
- whether Spring's public `@MockitoBean` configuration allows the required
  listener without changing PetClinic source.

Do not use Mockito internal packages or Spring Test's package-private bean
override handlers.

### 7. ASM instrumentation

Confirm only the already observed limitation:

- repository interfaces contain no concrete invocation body to instrument;
- real implementations are framework-generated proxies;
- Mockito implementations are Byte Buddy-generated mocks;
- domain type and bean identity are not reliable bytecode semantics.

Do not instrument interfaces, repository proxies, Spring, Mockito, or Byte
Buddy during this spike.

## Disposable fixture

Create a minimal temporary Spring Data JPA fixture using an in-memory database.
It should contain only what is needed to exercise the public APIs:

```text
SampleEntity
SampleRepository
one custom repository fragment
one cache configuration
one transaction configuration
one @MockitoBean test slice
diagnostic hook implementations
```

`SampleRepository` must contain or inherit calls equivalent to:

```java
save(entity)
findById(id)
findAll()
findAll(pageable)
delete(entity)
findByName(name)
failingCustomMethod()
```

Add a default interface method and one custom fragment method so the experiment
can distinguish repository method categories.

The fixture is experimental evidence code only. It must not depend on
`stp-agent`, `stp-runtime`, or `stp-junit-adapter`.

## Diagnostic record

Every explored hook writes a small raw diagnostic record. This is not the STP
schema. It exists only to compare public APIs.

Each record should contain:

```text
sequence number local to the experiment
hook name
callback phase: BEFORE or AFTER
repository interface reported by the hook
method declaring class
method name
JVM descriptor derived from Method
domain type reported by public metadata, if any
result state, if any
exception type, if any
current transaction active: true/false
cache experiment invocation number
bean name, if available from the registration path
proxy/advisor type names needed to explain ordering
```

Do not record:

- method arguments;
- argument `toString()` values;
- return values;
- SQL;
- entity field values;
- stack traces;
- arbitrary application properties.

Sequence numbers are diagnostic only. They must not become part of the future
canonical dependency identity.

## Experiment matrix

### Experiment A: method coverage by public hook

For each candidate hook, invoke independently:

```text
save
findById
findAll
findAll(Pageable)
delete
derived query method
default interface method
custom fragment method
```

Record whether the hook fires, how often, which `Method` is exposed, and which
repository interface and domain type are available.

This experiment answers whether one public listener covers most repository
semantics before any proxy advice is considered.

### Experiment B: successful and failed calls

Run one successful method and one method that throws a known fixture exception.
Record:

- callback phases;
- terminal state;
- exposed exception type;
- whether the original exception reaches the caller unchanged;
- whether transaction rollback behavior is unchanged.

### Experiment C: cache boundary

Call the same `@Cacheable` zero-argument repository method twice.

Prove separately that:

- the repository/database work occurs once;
- the caller invokes the repository interface twice;
- each candidate hook reports either one or two invocations;
- callback and advisor ordering explains the observed count.

Do not label one or two callbacks as correct until the intended dependency
semantics are explicitly chosen. Preserve both observations.

### Experiment D: transaction boundary

Invoke repository methods:

- outside an explicit transaction;
- inside a test transaction;
- through a method with repository transaction metadata;
- through a failing transactional method.

Record whether a transaction is active at each callback phase. Do not add a
transaction event to STP; this experiment only locates the hook.

### Experiment E: domain metadata

For declared, inherited generic, overloaded, default, and fragment methods,
record the domain type available from each public metadata API. Do not derive
the domain from runtime arguments or return values.

Verify erased descriptors explicitly. In particular, an inherited generic CRUD
method may expose:

```text
save(Ljava/lang/Object;)Ljava/lang/Object;
```

The experiment must not invent `save(SampleEntity)` as a JVM descriptor.

### Experiment F: real proxy identity

Record, without depending on generated names:

- declared repository interface;
- interfaces implemented by the proxy;
- canonical Spring bean name;
- whether the proxy is JDK- or class-based;
- public repository metadata;
- advisor ordering before and after adding a diagnostic hook.

Generated proxy class names are diagnostic only and must not become canonical
identity.

### Experiment G: `@MockitoBean`

Use a test slice with a mocked repository bean. Its setup must stub one method,
its application component must invoke another method, and its assertion must
verify that invocation.

Measure separately:

```text
stubbing declaration
application invocation
verification
reset/cleanup
```

For each candidate public Mockito or Spring hook, record which phases it sees.
The desired future dependency is only the application invocation.

Reject any approach that:

- records setup stubbing as an application dependency;
- records verification as another dependency;
- prevents ordinary `given(...)` or `verify(...)` use;
- changes the object expected by Spring's Mockito reset support;
- requires internal Mockito or Spring Test APIs.

### Experiment H: startup and shutdown

Invoke a fixture repository once during context initialization and, if safely
possible, once during context shutdown. Record which hooks see these calls and
whether any test context exists.

This phase has no STP attribution. It only proves that repository hooks can fire
outside test-method execution, which the later adapter must leave unattributed.

### Experiment I: hook coexistence and ordering

Enable the public diagnostic hooks together for one run. Record their exact
order for:

- normal query execution;
- cached first call;
- cached second call;
- transaction success;
- transaction failure.

This run is diagnostic only. It must identify duplicate observation of the same
logical invocation. Do not solve deduplication before choosing a primary hook.

## PetClinic confirmation

After the fixture experiments, activate only the diagnostic mechanism being
measured and run these pinned tests without STP:

```text
OwnerControllerTests#processCreationFormSuccess
ClinicServiceTests#shouldInsertOwner
PetClinicIntegrationTests#findAll
```

No PetClinic source changes are allowed.

### Controller test observations

Determine whether public hooks can isolate the actual application invocation:

```text
OwnerRepository.save(Owner)
```

The test's `@BeforeEach` stubbing must be reported separately in the raw
experiment, not silently counted as the expected dependency. No database,
entity-lifecycle, or table observation may be inferred from this mock.

### Repository test observations

Determine which hooks observe, and how they describe:

```text
OwnerRepository.findByLastNameStartingWith(...)
OwnerRepository.save(Owner)
OwnerRepository.findByLastNameStartingWith(...)
```

Record exact method declarations, descriptors, callback counts, domain metadata,
transaction state, and result state.

### Integration test observations

Determine which hooks observe:

```text
VetRepository.findAll()
VetRepository.findAll()
```

Record whether each hook emits one or two callbacks and demonstrate whether the
second call is served from cache. Do not force the expected count by changing
cache configuration.

## Evidence tables

The spike report must include a completed table like this for every method
category:

| Hook | Public API | Registration API | Real proxy | Mock | Before/after | Interface | Exact Method | Domain type | Success/failure | Cache call 1 | Cache call 2 | Behavior changed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `RepositoryMethodInvocationListener` | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result |
| repository AOP diagnostic | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result |
| bean wrapper diagnostic | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result |
| Mockito public listener | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result | observed result |

Use `not available` when a public API does not expose a field. Do not fill gaps
with inference unless the report labels the inference and demonstrates its
source.

Also include an ordering trace for one successful, one failed, and two cached
calls:

```text
sequence
→ hook
→ phase
→ transaction active
→ underlying execution happened
```

## Measurements

This is an API exploration, not a performance benchmark. Still record enough
information to reject obviously intrusive hooks:

- test result with and without each diagnostic hook;
- total fixture duration;
- callback count per repository call;
- duplicate callback count when hooks coexist;
- proxy/advisor count before and after registration;
- cache hit behavior unchanged;
- transaction result unchanged;
- mock stubbing, verification, and reset result;
- diagnostic output size.

Do not derive a production overhead percentage from this fixture.

## Decision gates

### Gate 1: one public hook is sufficient

Proceed to adapter design around one public hook only if it reliably provides:

- real repository interface invocation;
- exact method identity and overload distinction;
- domain type from public metadata;
- success/failure;
- required cache-boundary semantics;
- unchanged repository behavior.

Mock support can remain a separate decision.

### Gate 2: real and mock require separate mechanisms

Design a primary real-repository mechanism plus a narrow mock fallback only if:

- no single public hook covers both;
- both selected mechanisms identify the same canonical repository method;
- mock setup and verification can be excluded reliably;
- neither mechanism changes application or test behavior.

### Gate 3: mock support is not reliable

If `@MockitoBean` observation requires internal APIs, source changes, or proxy
behavior changes, do not implement a mock fallback. Document real repositories
as supported and mock repositories as an explicit gap.

### Gate 4: public hooks are insufficient

Stop the adapter phase if real repository calls cannot be observed reliably
through public APIs without changing cache, transaction, exception, or proxy
behavior. Do not fall back to ASM instrumentation of Spring Data, Mockito, or
generated proxies.

## Deliverables from the observability spike

The later one-day implementation spike should produce:

1. a disposable fixture isolated from STP production modules;
2. raw deterministic diagnostic output for every experiment;
3. the completed public-hook capability matrix;
4. callback-order traces for normal, failed, transactional, and cached calls;
5. exact PetClinic observations for the three selected tests;
6. a list of unavailable information, without guessed replacements;
7. a recommendation based on observed results;
8. only then, if justified, a separate adapter architecture document.

Do not create the future `stp-spring-data-adapter` module as part of this
observability spike.

## Recommendation

**Run a smaller API feasibility spike first.** Perform the Spring Data
Observability Spike exactly as defined above, without STP, ASM, or a runtime
event model. Review the emitted callbacks, ordering, cache behavior, transaction
position, domain metadata, and Mockito behavior together. Select an adapter
architecture only after those results exist.
