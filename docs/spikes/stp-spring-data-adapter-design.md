# Experimental `stp-spring-data-adapter` design

## Status and basis

This document began as the design for an optional, unpublished Spring Data
adapter. The narrow design is now implemented and acceptance-validated, but it
remains experimental and does not change current Smart Test Picker behavior.

The design starts from the behavior proven on pinned Spring PetClinic commit
`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`:

```text
factory metadata + eligibility classification
  → SmartInitializingSingleton resolves final repository bean
  → advised.addAdvisor(0, caller-boundary advisor)
  → existing CacheInterceptor
  → existing Spring Data proxy
```

That experiment observed both caller invocations of cached
`VetRepository.findAll()` while the underlying repository/database executed
once. Proxy depth, bean identity, qualified injection, cache behavior,
transaction chain and test result remained unchanged.

The adapter semantic is therefore **caller invocation**, not inner repository
execution. It must never silently fall back to
`RepositoryMethodInvocationListener` semantics.

## Supported first version

Version one supports only:

- real Spring Data repository products created by public repository factories;
- synchronous calls completed on the invoking thread;
- final exposed beans already implementing public Spring `Advised`;
- existing public `Advised` JDK repository proxies that permit safe in-place
  advisor insertion; class-based products remain unclaimed until separately
  proven;
- one unambiguous public repository interface and domain type from
  `RepositoryInformation`;
- same-thread attribution through the existing `RuntimeContextService`;
- one terminal `SUCCEEDED` or `FAILED` event per invocation;
- Java 17 or newer.

It explicitly excludes:

- `@MockitoBean` and every other mocked repository;
- repository beans that are not already `Advised`;
- reactive repositories and publisher/subscription semantics;
- asynchronous completion or cross-thread propagation;
- SQL, Hibernate, JDBC, entity-state and table events;
- arguments, return values and stack traces;
- internal Spring APIs;
- creation of an additional proxy;
- test selection or changes to current STP selection behavior.

These are eligibility boundaries, not opportunities to guess an event.

## Proposed module

```text
stp-spring-data-adapter
```

The module remains experimental, has no publishing configuration and is absent
from stable distributions initially.

### Responsibilities

It is responsible for:

1. source-free Spring discovery when explicitly present on the test classpath;
2. collecting public Spring Data metadata during repository factory creation;
3. classifying repository products in bean post-processing without mutation;
4. strict eligibility checks and bounded diagnostics;
5. in-place installation of exactly one caller-boundary advisor;
6. final advisor-order and identity audit;
7. terminal repository events and adapter-local timing/count metrics;
8. isolating every recording failure from application behavior.

It does not start the agent, open/close tests, instrument classes, propagate
threads, flush the main runtime output or interpret persistence behavior.

### Dependencies

Main dependencies are limited to:

```text
stp-runtime
spring-beans
spring-context
spring-aop
spring-data-commons
```

`spring-context` supplies public cache AOP types for order verification. There
is no direct dependency on Spring Boot, Spring Data JPA, JUnit, Mockito,
Hibernate, JDBC, JaCoCo or ASM.

The adapter JAR must not embed Spring or `stp-runtime`. In agent runs the agent
supplies the one runtime class identity; standalone adapter tests may put the
runtime JAR on their test classpath.

### Public and internal API

The only required public type is:

```java
public final class StpSpringDataApplicationContextInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext>
```

An optional public constants-only `SpringDataAdapterProperties` may expose
property names such as `stp.spring-data.enabled`. No mutable state is public.

Configuration registration, factory metadata collection, eligibility,
post-processing, advisor/interceptor, descriptor generation, context-scoped
installation state, final audit, diagnostics and metrics remain package-private
named types. Named types are preferred over anonymous/lambda advisor markers so
duplicate detection and diagnostics are stable.

## Activation and discovery

### Agent-bundled adapter

Bundling the adapter into the agent JAR is not chosen for version one. Loading
`premain` does not by itself prove that Spring's resource loader sees the agent
JAR's discovery metadata or that Spring-linked adapter classes resolve through
the correct application classloader. It would also couple the otherwise
framework-neutral agent artifact to Spring versions.

This option may be reconsidered only after a separate experiment proves:

- Spring resource discovery sees the agent JAR in Maven and Gradle test JVMs;
- adapter, Spring and runtime types resolve to compatible class identities;
- no Spring classes are bundled or loaded from an agent-private loader;
- discovery resources merge without duplicate services/configuration.

### Explicit adapter JAR on the test classpath

This is the chosen first-version model. The build experiment adds the adapter
JAR to the test runtime classpath and sets:

```text
-Dstp.spring-data.enabled=true
```

The JAR registers the initializer through:

```text
META-INF/spring.factories
```

Spring Boot's `SpringApplication` loads it through the application/test
classloader without changing application source. The initializer registers
infrastructure bean definitions before ordinary singleton creation.

This makes Spring Boot's initializer discovery path part of the first-version
contract. Plain Spring contexts that do not perform this discovery are
unsupported rather than silently assumed to work.

### Both models

Both may be supported later, but the explicit JAR remains the reference model.
Agent bundling is a separate implementation task, never an implicit packaging
change.

The initializer is a no-op unless explicitly enabled. It must avoid eager
static links to Spring Data implementation types until a class-presence check
passes, so a classpath with Spring Framework but no Spring Data disables cleanly
without `NoClassDefFoundError`. Unknown properties reject activation clearly;
they do not partially install advice.

## Runtime and classloader integration

### One runtime instance

Today the no-argument JUnit listener discovers the service through a registry
owned by `stp-junit-adapter`. A Spring adapter must not depend on JUnit simply to
find runtime state. Before Spring implementation, move that replaceable bridge
to `stp-runtime`, for example:

```java
RuntimeContextRegistry.install(RuntimeContextService service)
RuntimeContextRegistry.current()
RuntimeContextRegistry.Registration.close()
```

The agent installs its single `RuntimeContextService` during `premain`. ASM
hooks, the JUnit listener and Spring advice then use that same service and
aggregator. The existing JUnit registry may temporarily remain as a deprecated
forwarding facade for an independently reviewable migration.

The adapter never constructs `RuntimeEventAggregator` or
`RuntimeContextService`. The advice resolves the current registry service lazily
at terminal recording time, avoiding a stale reference after worker/agent
shutdown. If none is installed, repository behavior proceeds and a bounded
diagnostic counter increments; no private runtime is created.

### Classloader rule

The explicit adapter and agent runtime must be visible in one compatible
application/system classloader lineage. The adapter declares but does not
package runtime classes. Startup verifies the adapter-visible registry and
`RuntimeContextService` class identity. A split identity disables installation
with one bounded diagnostic; reflective cross-loader bridging is forbidden.

### Agent interaction

The agent remains Spring-free. It creates and registers the runtime, installs
ASM/JUnit integrations, serializes the shared aggregator at shutdown, then
closes its registry registration. The adapter only contributes ordinary runtime
events. With the adapter disabled/absent, agent behavior and JSON are unchanged.

## Lifecycle

1. The initializer checks enablement, Spring Data presence and runtime class
   identity, then registers reserved infrastructure bean names.
2. An early metadata collector observes public
   `RepositoryFactoryBeanSupport`. Through public
   `addRepositoryFactoryCustomizer` and `RepositoryProxyPostProcessor`, it
   captures immutable `RepositoryInformation` keyed by canonical bean name. It
   adds no inner invocation hook.
3. A late post-processor receives repository products, resolves metadata and
   records eligibility only. It never inserts an advisor.
4. `SmartInitializingSingleton.afterSingletonsInstantiated()` resolves all
   unambiguous canonical repository names from factory metadata, allowing any
   not-yet-requested product to be classified. It then captures the final
   fingerprint, adds one disabled advisor at index zero and audits immediately.
   Only a passing bean is enabled.
5. Synchronous calls emit terminal events through the shared runtime.
6. Context close discards context-scoped metadata, identity sets, metrics and
   diagnostics. It does not unwrap proxies during shutdown.

Keeping advice disabled until final audit prevents initialization-time calls
from being accepted before caller ordering is proven. Such calls may increment
a bounded setup counter, but do not become guessed test dependencies.

A cached Spring TestContext reuses the same context registry and bean, so it
cannot reinstall advice. A real context refresh creates new beans and a new
context-scoped registry; no state is static across contexts.

## Exact bean eligibility

A bean is eligible only when all checks pass:

1. Its canonical name has metadata captured from a real public
   `RepositoryFactoryBeanSupport` creation path in this context.
2. `RepositoryInformation.getRepositoryInterface()` is a public interface that
   extends Spring Data `Repository` and is exposed by the bean.
3. `RepositoryInformation.getDomainType()` is non-null with a stable binary
   name.
4. The final bean implements public `Advised`.
5. It is a Spring AOP JDK/class proxy inspectable through public `Advised`
   advisor and target-source methods.
6. Metadata identifies exactly one canonical repository interface/domain pair.
7. It is not an STP infrastructure bean or reserved STP name/type.
8. No STP caller advisor is already installed.
9. The exact object identity has not already been processed in this context.
10. Index-zero insertion and final structural audit both succeed.

Real-factory metadata provenance is also the mock exclusion. A mock replacement
does not have a matching real repository factory product entry. The adapter
must not depend on Mockito or guess from generated class names.

### Names and aliases

The collector removes factory dereference syntax and resolves the public bean
factory canonical name. Events always use that name. Aliases map to the same
metadata and exact object identity, so encountering an alias cannot create a
second advisor or alias-specific fact.

### Multiple repository interfaces

Framework interfaces (`Repository`, `Advised`, `DecoratingProxy`,
`TransactionalProxy`) are ignored. The authoritative user interface is the one
from `RepositoryInformation`.

Additional user interfaces are acceptable only when they form one assignable
hierarchy with that authoritative interface and do not introduce competing
repository metadata. Unrelated repository interfaces, multiple domains,
multiple canonical factories for one object, or metadata/exposed-interface
mismatch is `AMBIGUOUS_REPOSITORY_METADATA`. The entire bean is skipped; no
first-match or alphabetical choice is allowed.

### Unsupported behavior

- Non-`Advised`: bounded diagnostic, no event, no `ProxyFactory`.
- Missing real-factory metadata: unsupported, including mock replacements.
- Ambiguous metadata: skip whole bean.
- Existing context-owned advisor: count duplicate encounter, do not add.
- Same bean twice/alias: identity no-op.
- New context refresh: new isolated state, one installation on each new bean.

Diagnostics retain counters by a closed reason enum and at most 20 canonical
bean-name samples per reason. They do not retain bean objects, generated proxy
names, exceptions or unlimited application data. One deterministic summary may
be written in debug mode. Unsupported beans never become runtime dependency
events.

## Advisor insertion and order

### Post-processor order

The product BPP implements public `Ordered` with `LOWEST_PRECEDENCE`, but only
classifies. The lifecycle spike proved numeric BPP order was not a safe
insertion boundary: full PetClinic subsequently added a
`PersistenceExceptionTranslationAdvisor`. Insertion now occurs only from the
context-scoped `SmartInitializingSingleton`, after normal singleton
post-processing.

### Proof before insertion

Using public APIs, capture a temporary fingerprint:

- exact exposed bean reference;
- JDK/class proxy kind and exposed interfaces;
- current advisor object identities and order;
- all positions whose public advice is `CacheInterceptor`;
- target-source identity and proxy-layer count for before/after comparison.

If cache advice is present, seeing it on the final exposed `Advised` proves the
installer reached the cache boundary rather than only the inner repository
proxy. An otherwise eligible repository without cache advice may still be
supported because index zero is its outermost existing boundary; the adapter
does not claim that repository is cached.

### Insertion and final audit

The only permitted insertion is:

```java
advised.addAdvisor(0, callerAdvisor);
```

Existing advisor object identities and relative order must remain unchanged.
The only allowed structural delta is one new advisor at index zero.

Immediately after smart-singleton insertion the audit proves:

1. bean-factory lookup returns the identical object;
2. proxy kind, interfaces, target and layer count are unchanged;
3. exactly one STP advisor exists and remains index zero;
4. every existing advisor retains relative order;
5. STP precedes every `CacheInterceptor`;
6. all normal singleton post-processors have completed.

Only then is advice enabled. On failure, remove the exact context-owned advisor,
verify full restoration and mark the bean unsupported. Never try another index,
move cache advice, wrap the bean, or switch to inner execution events. If exact
restoration itself fails while the adapter was explicitly enabled, fail context
initialization clearly rather than continue with corrupted proxy semantics.

Duplicate prevention combines a named advice/advisor type, exact context-owned
advisor identity, a context-scoped identity set, canonical-name state and a
reserved infrastructure bean definition. Multiple adapter copies in distinct
classloaders fail the class-identity check instead of installing twice.

A repository factory/product first registered after
`afterSingletonsInstantiated()` is unsupported and diagnosed as
`REPOSITORY_CREATED_AFTER_INSERTION_PHASE`. The adapter does not mutate it on
first invocation or emit a guessed dependency.

## Repository metadata normalization

`repositoryInterface` is the binary name from authoritative
`RepositoryInformation`, never a generated proxy or `SimpleJpaRepository`.
`beanName` is the canonical Spring name. `domainType` is the binary name from
`RepositoryInformation.getDomainType()`.

The new kind is:

```text
repositoryKind = SPRING_DATA_PROXY
```

It describes metadata provenance/caller boundary, not JDK versus class proxy.

The interceptor uses the exact public reflective `Method` from AOP
`MethodInvocation`. Descriptor generation uses only JDK APIs:

```java
MethodType.methodType(method.getReturnType(), method.getParameterTypes())
    .descriptorString()
```

This preserves erased JVM types and distinguishes overloads. Inherited CRUD
must remain, for example:

```text
repositoryInterface = org.springframework.samples.petclinic.owner.OwnerRepository
methodName = save
jvmDescriptor = (Ljava/lang/Object;)Ljava/lang/Object;
domainType = org.springframework.samples.petclinic.owner.Owner
```

Domain specialization belongs only in `domainType`; never invent
`save(Owner)Owner`. Source parameter names and generic arguments are ignored.

Bridge methods follow the actual invoked public `Method`. If a supported Spring
version causes multiple public interceptions for one logical call, compatibility
fails until a public deterministic normalization rule is proven; name-based
deduplication is forbidden.

## Runtime event semantics

The existing `RepositoryInvocationEvent` already has repository interface,
bean, method, descriptor, domain, outcome and evidence. `SPRING_DATA`,
`OBSERVED`, terminal outcomes and deterministic repeated counts already exist.

Task 2 introduced the required event-schema field:

```java
RepositoryKind repositoryKind
```

with initial value `SPRING_DATA_PROXY`. JSON has a stable `repositoryKind`
field, and repository fact identity/sort order includes it so future kinds do
not merge. Count remains an aggregator/output value, not an event property.
`STARTED` may remain for compatibility, but this adapter never emits it.

Golden runtime JSON receives an intentional schema-version update. No separate
`EntityEvent` is emitted: the authoritative domain already belongs to the
repository fact.

### Terminal-only advice

```java
Object result;
try {
    result = invocation.proceed();
} catch (Throwable repositoryFailure) {
    safeRecord(FAILED);
    throw repositoryFailure;
}
safeRecord(SUCCEEDED);
return result;
```

`STARTED` is unnecessary for synchronous completion and would introduce pair
reconciliation and double-count risk. Every event contains:

```text
repositoryKind = SPRING_DATA_PROXY
evidenceSource = SPRING_DATA
certainty = OBSERVED
outcome = SUCCEEDED | FAILED
```

Two cached Vet successes become one equal dependency fact with `count=2`.
Successful and failed outcomes remain distinct facts. No argument, returned
value, entity instance, cache key or SQL is recorded.

## Attribution and behavioral isolation

At terminal recording time, advice resolves the shared registry and calls only:

```java
runtimeContextService.record(repositoryInvocationEvent);
```

It never calls `beginTest`, `endTest` or changes `currentTest`. Thus existing
rules remain authoritative: active same-thread leaf tests receive events;
startup/no-active calls are unattributed; post-completion calls remain late or
finished; nothing is moved into the next test; async propagation is absent.

The advisor must:

- call `proceed()` exactly once;
- return the exact result reference unchanged;
- rethrow the exact original throwable object;
- catch/isolate only STP event construction, registry and recording failures;
- never mask a repository failure when failed-event recording also fails;
- never invoke repository code while resolving metadata.

The `proceed()` call is not inside a broad STP-failure catch. In the repository
failure branch, `safeRecord(FAILED)` is isolated and the captured original is
re-thrown directly. There is no per-call logging. Thread-safe adders measure
record count/time, while output remains deterministic.

## Compatibility range

The initially claimed line is deliberately narrow:

```text
Spring Boot 4.1.x
Spring Framework 7.0.x
Spring Data Commons/JPA 4.1.x
Java 17+
```

The proof used Boot 4.1.0, Framework 7.0.8 and Spring Data 4.1.0. Each claimed
patch must pass API, post-processor order, cache position and identity tests.
Boot 3.x, Framework 6.x and Spring Data 3.x are not inferred from similarity.

JDK and class-based proxies are intended, but class-based support is not claimed
until its complete identity/order integration test passes. A failed self-check
skips the bean; compatibility is never manufactured by changed semantics.

## Validation plan

### Unit and fixture tests

| Required case | Exact assertion |
| --- | --- |
| Eligible real repository | Real factory metadata resolves; one in-place advisor enabled. |
| Cached method twice | Exactly two terminal caller observations. |
| Underlying execution | Repository/fragment counter one while event count two. |
| Inherited CRUD | Exact `save(Ljava/lang/Object;)Ljava/lang/Object;` and domain. |
| Overloads | Separate facts by exact descriptors. |
| Success | One `SUCCEEDED` terminal event. |
| Failure | One `FAILED`; caller gets the same throwable object. |
| Repetition | One equal JSON fact with incremented count. |
| Result identity | `assertSame(repositoryResult, advisedResult)`. |
| Bean identity | Same reference and proxy depth before/after insertion. |
| Qualifier | Qualified injection remains the exact exposed singleton. |
| Cache order | Exactly one STP advisor before `CacheInterceptor`. |
| Cached context reuse | No second advisor or doubled callbacks. |
| Non-`Advised` | No wrapper/event; one bounded unsupported diagnostic. |
| Ambiguous metadata | Whole bean skipped; no chosen interface/domain. |
| Spring Data absent | Initializer disables without linkage failure. |
| Disabled adapter | No infrastructure mutation; unchanged runtime JSON. |
| JDK/class proxy | Same assertions before claiming each proxy kind. |

The exception test uses one sentinel throwable, deliberately fails runtime
recording too, and still `assertSame`s the throwable received by the caller. The
return test uses a unique object and asserts the exact reference after advice.

### Runtime/coexistence tests

With real agent registry and JUnit adapter, prove:

- ASM and repository events enter the same aggregator;
- startup calls are unattributed and post-completion calls remain late;
- two sequential tests do not contaminate each other;
- the adapter never opens/closes contexts;
- reversed insertion order yields deterministic normalized JSON;
- JaCoCo before/after STP does not affect advisor behavior;
- no framework/repository proxy bytecode is transformed by this module.

## PetClinic acceptance gate

Use only real-repository tests at the pinned revision:

```text
ClinicServiceTests#shouldInsertOwner
PetClinicIntegrationTests#findAll
```

The mocked controller test is not part of acceptance. Expected normalized
successful facts are:

```text
OwnerRepository#findByLastNameStartingWith(
  Ljava/lang/String;
  Lorg/springframework/data/domain/Pageable;
)Lorg/springframework/data/domain/Page;
domainType=org.springframework.samples.petclinic.owner.Owner
repositoryKind=SPRING_DATA_PROXY
outcome=SUCCEEDED
count=2

OwnerRepository#save(Ljava/lang/Object;)Ljava/lang/Object;
domainType=org.springframework.samples.petclinic.owner.Owner
repositoryKind=SPRING_DATA_PROXY
outcome=SUCCEEDED
count=1

VetRepository#findAll()Ljava/util/Collection;
domainType=org.springframework.samples.petclinic.vet.Vet
repositoryKind=SPRING_DATA_PROXY
outcome=SUCCEEDED
count=2
```

Vet additionally requires two caller events, one underlying execution, one vets
database query path (existing association-load SQL allowed), unchanged cache,
exactly one STP advisor before cache, unchanged proxy/injection/transaction/test
behavior. Owner's outer advice should observe its existing test transaction as
active; Vet caller advice remains outside the repository transaction.

Run tests individually and together in two deterministic orders. Per-test facts
must match, with no Owner/Vet contamination.

## Exploratory performance gate

Use one warm-up and at least five fresh-JVM measurements for:

1. adapter disabled;
2. adapter enabled;
3. ASM only;
4. ASM plus Spring Data adapter.

Report total and per-test duration, logical callback count, terminal record
attempts/failures, aggregate adapter recording nanoseconds, unique/repeated
facts, duplicate events/advisors, JSON bytes, unattributed and late events.

The exploratory gate is:

```text
unchanged tests
zero duplicate advisors
zero duplicate logical observations
median aggregate overhead below 10% for selected PetClinic tests
```

This is not a production SLO; PetClinic is too small for production claims.
Only obvious per-call allocation, registry, serialization or diagnostic costs
should be investigated before broadening the spike.

## Failure and stop policy

Per-call STP recording failures are swallowed and counted. Configuration-time
mutation is stricter: final audit succeeds, or exact original structure is
restored and the bean is unsupported.

Stop implementation if any supported case requires:

- internal Spring/Spring Data APIs;
- an additional proxy or changed bean/injection identity;
- changed cache, transaction, exception, return or test behavior;
- argument/return inspection;
- ambiguous/heuristic repository metadata;
- moving advice away from index zero after failure;
- silent fallback to inner repository-execution semantics;
- a second runtime/aggregator or reflective classloader bridge.

## Ordered implementation tasks

Each task is suitable for one small pull request:

1. Move the shared replaceable runtime registry into `stp-runtime`; migrate
   agent/JUnit with lifecycle and duplicate-install tests. No Spring dependency.
2. Add `RepositoryKind.SPRING_DATA_PROXY` to the runtime event, fact key,
   deterministic JSON and golden fixtures. No adapter code.
3. Add the unpublished module shell, explicit dependencies, disabled
   initializer discovery, README and packaging tests proving no embedded
   Spring/runtime classes.
4. Implement enablement, reserved registration, duplicate discovery and safe
   Spring Data absence. Still no proxy mutation.
5. Collect immutable public factory metadata, canonical aliases, interface
   hierarchy and ambiguity results. No invocation advisor.
6. Implement strict eligibility and bounded diagnostics for `Advised`, proxy
   kind, metadata provenance, STP exclusion and duplicate encounters.
7. Implement disabled index-zero advisor insertion, fingerprints, final audit,
   exact restoration and audit tests.
8. Implement terminal event semantics with proceed-once, exact return/exception,
   recording-failure isolation, descriptors and counts.
9. Validate cached/uncached fixtures, JDK/class proxies, transactions,
   qualifiers, context reuse, non-`Advised`, ambiguity and deterministic JSON.
10. Integrate the one experimental runtime; prove ASM/JUnit co-attribution,
    unattributed/late behavior and no contamination.
11. Run pinned PetClinic Owner/Vet acceptance and two-order normalization.
12. Run JaCoCo coexistence and disabled/adapter/ASM/combined performance matrix.
13. Separately evaluate agent-JAR discovery/classloading only after the explicit
    JAR model passes; do not bundle merely for convenience.

## Final decisions

1. **Activation:** explicit experimental adapter JAR on the test classpath,
   discovered through `ApplicationContextInitializer`; agent bundling is
   deferred until separately proven.
2. **Supported scope:** synchronous same-thread calls to real, unambiguous
   Spring Data products whose final exposed JDK/class Spring AOP bean already
   implements `Advised` and passes identity/index-zero/cache-order audit. No
   mocks, non-`Advised`, reactive or async repositories.
3. **Runtime schema:** use the now-added
   `RepositoryKind.SPRING_DATA_PROXY`; reuse existing evidence, certainty,
   outcomes and aggregation count. Moving the registry into `stp-runtime` is an
   API/lifecycle correction, not schema expansion.
4. **Implementation order:** the thirteen small tasks above, with runtime
   ownership/schema completed before Spring proxy mutation.
5. **Recommendation:** proceed with the narrowly scoped experiment using
   in-place insertion and immediate audit at `SmartInitializingSingleton`, plus
   explicit classpath activation. Stop if public APIs cannot reproduce the
   proven identity, order and cache invariants.

**Recommendation: proceed narrowly.**
