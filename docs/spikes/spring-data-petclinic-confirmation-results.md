# Spring Data PetClinic confirmation results

> Archived inner repository-execution research. Its normalizer was removed
> during stabilization; reviewed evidence remains under
> `docs/spikes/archive/spring-data-observability/petclinic-confirmation/`.

## Scope and decision context

This is the PetClinic-only confirmation phase for real Spring Data repositories.
It evaluates two public mechanisms independently:

1. `RepositoryMethodInvocationListener` registered with
   `RepositoryFactorySupport.addInvocationListener(...)`;
2. Spring AOP `MethodInterceptor` added to the existing repository proxy at
   advisor index zero by a public `RepositoryProxyPostProcessor`.

The experiment adds a disposable diagnostics JAR to the PetClinic test
classpath. It does not modify PetClinic source or `pom.xml`, implement an STP
adapter, use STP runtime/agent/JUnit modules, observe mocks, or collect
arguments, return values, SQL events, Hibernate events, tables, or MVC events.
Only one invocation mechanism was active in each canonical run.

## Target and environment

```text
repository:       https://github.com/spring-projects/spring-petclinic.git
commit:           88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
working tree:     clean before and after the experiment
Spring Boot:      4.1.0
Spring Data:      Commons 4.1.0, JPA 4.1.0
Spring Framework: 7.0.8
Hibernate ORM:    7.4.1.Final
JaCoCo:           0.8.15 (PetClinic's existing Maven configuration)
Java:             SapMachine 21.0.12
Maven Wrapper:    3.9.16
OS:               macOS 26.5.1, aarch64
```

The Spring versions above came from PetClinic's parent POM and generated
CycloneDX dependency report, not from the disposable fixture's dependency
graph.

## Registration mechanism

The disposable JAR contains a public Spring
`ApplicationContextInitializer`, discovered through its own
`META-INF/spring.factories`. The initializer registers a small diagnostic
configuration when `stp.confirmation.mode` is `listener` or `advice`.

A `BeanPostProcessor` detects the public
`RepositoryFactoryBeanSupport` type and calls
`addRepositoryFactoryCustomizer(...)`. The customizer uses a public
`RepositoryProxyPostProcessor` to capture `RepositoryInformation`. In listener
mode it registers only `RepositoryMethodInvocationListener`; in advice mode it
adds only the AOP interceptor. No exposed repository bean is wrapped or
replaced. The same post-processor records the repository proxy's advisor order
after initialization.

The JAR was supplied without a PetClinic change by Maven Surefire's public
`maven.test.additionalClasspath` property. Diagnostics are closed and written
when the Spring context is destroyed.

## Commands

Build the disposable confirmation JAR:

```bash
cd /Users/D061177/work/moje/asm-poc/smart-test-picker
GRADLE_USER_HOME=/tmp/stp-gradle-home ./gradlew \
  :stp-spring-data-observability-spike:petclinicConfirmationJar --rerun-tasks
```

Each of the following commands was run separately from the pinned PetClinic
checkout. `MODE` and `CASE` below were replaced by the four pairs
`listener/listener-owner`, `advice/advice-owner`, `listener/listener-vet`, and
`advice/advice-vet`:

```bash
cd /Users/D061177/work/moje/asm-poc/spring-petclinic-asm
MAVEN_USER_HOME=/tmp/stp-petclinic-maven-home ./mvnw \
  -Dmaven.repo.local=/tmp/stp-petclinic-m2 \
  -Dmaven.test.additionalClasspath=/Users/D061177/work/moje/asm-poc/smart-test-picker/stp-spring-data-observability-spike/build/libs/spring-data-petclinic-confirmation.jar \
  -Dstp.confirmation.mode=MODE \
  -Dstp.confirmation.output=/Users/D061177/work/moje/asm-poc/smart-test-picker/stp-spring-data-observability-spike/petclinic-confirmation/CASE.json \
  -Dtest='org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner' test
```

For the two Vet cases, the final property was instead:

```text
-Dtest=org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll
```

The normalized, deterministic callback evidence was then validated and created
with:

```bash
ruby stp-spring-data-observability-spike/normalize_petclinic_confirmation.rb
```

## Normalized callback records

The reviewed records are in
`stp-spring-data-observability-spike/petclinic-confirmation/normalized-callback-records.json`.
Registration-only advisor records are omitted there, and each run is resequenced
from one. Raw per-run documents remain beside it.

### `ClinicServiceTests#shouldInsertOwner`

The listener produced three terminal callbacks in this exact order:

| # | Repository method | Declaring class | Exact erased JVM descriptor | Domain | State | Transaction active |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | `OwnerRepository.findByLastNameStartingWith` | `OwnerRepository` | `(Ljava/lang/String;Lorg/springframework/data/domain/Pageable;)Lorg/springframework/data/domain/Page;` | `Owner` | `SUCCESS` | true |
| 2 | `OwnerRepository.save` | `CrudRepository` | `(Ljava/lang/Object;)Ljava/lang/Object;` | `Owner` | `SUCCESS` | true |
| 3 | `OwnerRepository.findByLastNameStartingWith` | `OwnerRepository` | `(Ljava/lang/String;Lorg/springframework/data/domain/Pageable;)Lorg/springframework/data/domain/Page;` | `Owner` | `SUCCESS` | true |

Thus the listener counts are exactly two query calls and one save call.

The repository advice produced the same three logical calls and order. Each
logical call has one `BEFORE/RUNNING` and one `AFTER/SUCCESS` callback: four
callbacks for the two query calls and two callbacks for save. Repository
interface, declaring class, descriptor, domain and bean name match the listener
records exactly.

`shouldInsertOwner` itself is annotated `@Transactional`, so both the outer
repository advice and the terminal listener ran with a transaction already
active. All three database operations in PetClinic's existing Hibernate log
also occurred in the expected SELECT, INSERT, SELECT order.

### `PetClinicIntegrationTests#findAll`

The test source contains two consecutive, unconditional interface calls:

```java
vets.findAll();
vets.findAll(); // served from cache
```

The listener nevertheless produced one terminal callback:

```text
repositoryInterface = org.springframework.samples.petclinic.vet.VetRepository
declaringClass       = org.springframework.samples.petclinic.vet.VetRepository
methodName           = findAll
jvmDescriptor        = ()Ljava/util/Collection;
domainType           = org.springframework.samples.petclinic.vet.Vet
resultState          = SUCCESS
transactionActive    = true
beanName             = vetRepository
callback count       = 1
```

The repository-proxy advice also observed one logical call only: one
`BEFORE/RUNNING` callback and one `AFTER/SUCCESS` callback. Its transaction
state was false at both phases. The listener ran deeper in the proxy, after the
repository transaction interceptor opened the read-only transaction, and
therefore reported true.

## Cache and advisor-order findings

The following independent evidence establishes the cache boundary:

- the test executes two unconditional Java interface calls;
- `VetRepository.findAll()` is annotated `@Cacheable("vets")` in the pinned
  PetClinic source, and cache configuration was not changed;
- each inner repository mechanism observed one logical execution;
- PetClinic's existing `logging.level.sql=DEBUG` emitted one vets query and one
  association-load query:

```text
select ... from vets ...
select ... from vet_specialties ... join specialties ...
```

The second SQL statement loads specialties for the first repository result; it
is not a second `VetRepository.findAll()` execution. No second vets SELECT was
emitted. Therefore both interface calls occurred, the second returned through
Spring Cache, and the underlying repository/database execution occurred once.

In listener mode, the repository proxy advisor order began with:

```text
CrudMethodMetadataPopulatingMethodInterceptor
PersistenceExceptionTranslationInterceptor
TransactionInterceptor
DefaultMethodInvokingMethodInterceptor
QueryExecutorMethodInterceptor
ImplementationMethodExecutionInterceptor
```

In advice mode, the diagnostic interceptor was index zero, before that same
Spring Data chain. Neither inner repository proxy advisor list contained the
cache interceptor. Combined with the one observed repository execution, this
shows that PetClinic's cache advice is outside the Spring Data repository proxy
boundary measured by both mechanisms. Consequently neither mechanism can see
the cache-hit call in this application placement.

## Behavior-change checks

| Test | Listener | Repository advice | Result without PetClinic modification |
| --- | --- | --- | --- |
| `ClinicServiceTests#shouldInsertOwner` | PASS | PASS | expected SELECT/INSERT/SELECT; no exception change |
| `PetClinicIntegrationTests#findAll` | PASS | PASS | server starts/stops normally; one underlying query execution |

All canonical runs ended with Maven `BUILD SUCCESS`; there were no assertion,
transaction, proxy, cache, or application-result changes. JaCoCo remained
enabled by PetClinic and generated `target/jacoco.exec`. PetClinic's Git working
tree remained clean. The diagnostics did emit Spring's benign
`BeanPostProcessorChecker` warning because the disposable recorder is created
early for the diagnostic post-processor; it did not change test behavior.

## Comparison with the disposable fixture

The disposable fixture put `@Cacheable` on a custom fragment implementation.
Both inner mechanisms saw two repository callbacks there while the fragment
implementation executed once. PetClinic puts `@Cacheable` on the repository
interface and creates an effective cache boundary outside the Spring Data
repository proxy. Here both mechanisms see only the cache miss.

The Owner result does confirm the fixture's ordinary real-repository findings:
both public mechanisms preserve the exact erased reflective method, repository
interface, domain metadata, success state, and transaction position. The Vet
result materially contradicts the fixture's apparent cache coverage.

## Unsupported conclusions

This experiment supplies no evidence for mocked repositories, failures,
startup/shutdown attribution, JUnit attribution, async propagation, SQL/table
events, or PetClinic MVC behavior. It does not justify claiming that either
inner Spring Data hook observes every caller-level repository invocation. An
outer cache-aware observation mechanism was deliberately not added because it
would be a third experiment and outside this confirmation phase.

## Recommendation

Neither tested mechanism satisfies the stated requirement to observe both
PetClinic `VetRepository.findAll()` interface calls: both see only the cache
miss. Implementing a future adapter now would silently undercount cached
repository dependencies. A smaller follow-up must first decide whether the
desired semantic fact is caller invocation or underlying repository execution,
and, if caller invocation is required, test a public observation boundary
outside Spring Cache.

**Decision: stop because PetClinic behavior differs materially from the fixture.**
