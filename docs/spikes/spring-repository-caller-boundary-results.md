# Spring repository caller-boundary feasibility results

> Archived research. Rejected wrapper/auto-proxy alternatives and their
> normalizer were removed during stabilization. Evidence remains under
> `docs/spikes/archive/spring-data-observability/petclinic-confirmation/`.

## Scope

This narrow experiment asks one question: can a public Spring extension point
observe both caller-level invocations of PetClinic's cached
`VetRepository.findAll()` without adding another proxy or changing behavior?

Only this test was executed:

```text
org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll
```

The target was the clean Spring PetClinic checkout at commit
`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, using Spring Boot 4.1.0,
Spring Framework 7.0.8, Spring Data Commons/JPA 4.1.0, Java 21.0.12 and the
Maven Wrapper 3.9.16.

The disposable code remains in the existing non-published
`stp-spring-data-observability-spike` module. It does not use an STP runtime,
agent, ASM, JUnit adapter, Hibernate/JDBC observation, internal Spring API, or
PetClinic source change.

## Baseline

The test contains two consecutive and unconditional caller invocations:

```java
vets.findAll();
vets.findAll(); // served from cache
```

The exposed `vetRepository` is already a two-layer public `Advised` structure
before this experiment adds observation:

```text
depth 0: existing JDK proxy
         CacheInterceptor
           ↓
depth 1: existing Spring Data JDK repository proxy
         CrudMethodMetadataPopulatingMethodInterceptor
         PersistenceExceptionTranslationInterceptor
         TransactionInterceptor
         QueryExecutorMethodInterceptor
         ImplementationMethodExecutionInterceptor
           ↓
         SimpleJpaRepository
```

This explains the prior result: listener/advice inside the depth-1 Spring Data
proxy sees only the cache miss. Caller observation must precede
`CacheInterceptor` at depth 0.

Two lookups of `vetRepository` returned the same object. A diagnostic consumer
injected through `@Qualifier("vetRepository")` received that exact exposed
singleton. The exposed interfaces were:

```text
org.springframework.samples.petclinic.vet.VetRepository
org.springframework.data.repository.Repository
org.springframework.aop.framework.Advised
org.springframework.core.DecoratingProxy
org.springframework.transaction.interceptor.TransactionalProxy
```

## Mechanisms tested

### 1. In-place advisor insertion by a late public `BeanPostProcessor`

An `Ordered` `BeanPostProcessor` inspected the final `vetRepository` product in
`postProcessAfterInitialization`. When the bean was already public
`org.springframework.aop.framework.Advised`, it called public
`Advised.addAdvisor(0, advisor)` and returned the identical bean reference.

It did not create a `ProxyFactory`, call an internal API, replace the bean, or
wrap it. Advisor order after insertion was:

```text
depth 0:
  0 caller-boundary advice
  1 CacheInterceptor

depth 1:
  unchanged Spring Data advisor chain
```

This mechanism observed exactly:

```text
BEFORE VetRepository.findAll()Ljava/util/Collection;
AFTER  VetRepository.findAll()Ljava/util/Collection; SUCCESS
BEFORE VetRepository.findAll()Ljava/util/Collection;
AFTER  VetRepository.findAll()Ljava/util/Collection; SUCCESS
```

That is two logical caller invocations and four phase callbacks, with no
duplicate logical observation.

### 2. Public auto-proxy `Advisor`

A regular `DefaultPointcutAdvisor` with `Ordered.HIGHEST_PRECEDENCE` and a
static method matcher for the exact zero-argument `VetRepository.findAll()`
method was registered as a bean. Spring's existing public auto-proxy machinery
combined it with the cache advisor.

The resulting order and callback counts were identical to mechanism 1:

```text
depth 0:
  0 caller-boundary advice
  1 CacheInterceptor

callbacks: 2 BEFORE + 2 AFTER = 2 logical calls
```

The proxy depth remained two, so Spring did not add a third wrapper proxy in
this PetClinic configuration.

### 3. Additional wrapper proxy

Not executed. Both public mechanisms above succeeded without it. Creating a
new outer `ProxyFactory` would have violated the explicit stop criterion and
would not provide additional feasibility evidence.

## Measurement summary

| Measurement | Baseline | Existing `Advised` mutation | Auto-proxy advisor |
| --- | ---: | ---: | ---: |
| Caller-level `findAll` calls observed | 0 diagnostic callbacks | **2** | **2** |
| Phase callbacks | 0 | 4 | 4 |
| Underlying vets SELECT executions | 1 | **1** | **1** |
| Specialty association-load SELECTs | 1 | 1 | 1 |
| Exposed proxy depth | 2 | **2** | **2** |
| Observation before cache | N/A | yes | yes |
| Same bean on repeated lookup | yes | yes | yes |
| Injected object is exposed bean | yes | yes | yes |
| Exposed interfaces changed | no | no | no |
| Test result | PASS | PASS | PASS |
| Duplicate logical observation | N/A | none | none |

The association query against `vet_specialties`/`specialties` belongs to the
single underlying repository execution and is not a second `findAll` database
execution.

## Cache and database evidence

All three runs retained PetClinic's original cache configuration. Each emitted
one query of the vets table and one association-load query:

```text
select ... from vets ...
select ... from vet_specialties ... join specialties ...
```

No second vets SELECT appeared. Both successful mechanisms therefore observed
the cache-hit caller invocation without forcing a second repository or database
execution.

## Identity and behavior checks

- Both successful runs retained the baseline two-layer proxy structure.
- The exposed proxy class and complete interface set were unchanged.
- Repeated bean lookups returned the same reference.
- The qualified diagnostic injection received the exact exposed bean
  reference, proving that injection did not bypass or replace it.
- The existing cache advisor remained at depth 0 and the Spring Data advisor
  chain at depth 1 remained unchanged.
- Observation ran before cache and outside a transaction, reporting
  `transactionActive=false` for both calls. It did not move or replace the
  inner Spring Data `TransactionInterceptor`.
- The cache miss still executed normally and the cache hit still avoided the
  repository/database.
- Each `AFTER` callback reported `SUCCESS`; no exception occurred in this fixed
  PetClinic test.
- The interceptor returns the exact object received from `proceed()` and does
  not inspect or replace it. Both test executions passed. Since this selected
  test has no failing path and discards the returned collections, exception
  identity and return-object identity were not independently asserted by
  PetClinic; they are not claimed as separate empirical results.
- No arguments, return values, SQL data, or entity values were recorded.
- PetClinic's JaCoCo 0.8.15 agent remained active and the test completed
  normally.

Spring logged a `BeanPostProcessorChecker` warning for the deliberately early
disposable diagnostic recorder. It did not affect proxy depth, callbacks,
cache behavior, transactions, injection, or test results. A future
implementation would register infrastructure beans more cleanly, but that work
is outside this feasibility spike.

## Evidence and commands

The deterministic reviewed evidence is:

```text
stp-spring-data-observability-spike/petclinic-confirmation/caller-boundary-normalized.json
```

It is produced from the three raw run documents by a validator that asserts two
logical calls, exact JVM descriptors, advisor order, unchanged proxy depth,
unchanged interfaces and unchanged lookup/injection identity:

```bash
ruby stp-spring-data-observability-spike/normalize_caller_boundary.rb
```

The disposable JAR was built with:

```bash
GRADLE_USER_HOME=/tmp/stp-gradle-home ./gradlew \
  :stp-spring-data-observability-spike:petclinicConfirmationJar --rerun-tasks
```

Each PetClinic run used this command shape, with `MODE` and `OUTPUT` set to
`caller-baseline`, `caller-existing-advised`, or `caller-auto-proxy`:

```bash
MAVEN_USER_HOME=/tmp/stp-petclinic-maven-home ./mvnw \
  -Dmaven.repo.local=/tmp/stp-petclinic-m2 \
  -Dmaven.test.additionalClasspath=/Users/D061177/work/moje/asm-poc/smart-test-picker/stp-spring-data-observability-spike/build/libs/spring-data-petclinic-confirmation.jar \
  -Dstp.confirmation.mode=MODE \
  -Dstp.confirmation.output=/Users/D061177/work/moje/asm-poc/smart-test-picker/stp-spring-data-observability-spike/petclinic-confirmation/OUTPUT.json \
  -Dtest='org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll' test
```

## Recommendation

The narrow semantic is achievable through public APIs. The least expansive
mechanism is the late `BeanPostProcessor`: it mutates an already exposed
`Advised` bean in place and cannot silently create another proxy. The ordinary
advisor bean also worked in this cache-enabled PetClinic case, but could cause
auto-proxying in a different application where no outer proxy already exists;
that broader case was not tested here.

This is feasibility evidence, not approval to build a production adapter. A
future design must still define safe ordering, bean eligibility, failure
isolation and behavior for repositories that are not already `Advised`.

**Decision: caller-level observation is feasible.**
