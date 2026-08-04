# Spring Data PetClinic acceptance results

## Result

The pinned acceptance passes after moving insertion and immediate audit to
`SmartInitializingSingleton.afterSingletonsInstantiated()`.

The previous product-BPP insertion was premature. In the full PetClinic context
`PersistenceExceptionTranslationPostProcessor` subsequently appended a
`PersistenceExceptionTranslationAdvisor`, invalidating the earlier fingerprint.
The corrected flow resolves every canonical factory-metadata name at the stable
phase, lets the eligibility BPP classify the final product, captures the final
advisor chain, inserts STP at index zero, audits immediately and only then
enables recording.

## Environment

| Item | Value |
|---|---|
| PetClinic commit | `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` |
| Spring Boot / Spring Data | 4.1.0 / 4.1.0 |
| Spring Framework | 7.0.8 |
| JVM | SapMachine 21.0.12, aarch64 |
| Maven Wrapper | 3.9.16 |
| JaCoCo | 0.8.15 |

PetClinic source and `pom.xml` remained unchanged. The adapter was an explicit
test-runtime classpath JAR and was not bundled in the agent.

## Commands and matrix

The reproducible matrix is implemented by:

```bash
MAVEN_USER_HOME=/tmp/stp-petclinic-maven-home \
MAVEN_REPO_LOCAL=/tmp/stp-petclinic-m2 \
ruby stp-petclinic-spike/spring-data-acceptance/run_acceptance.rb
```

Each enabled run used:

```text
-Dmaven.test.additionalClasspath=<adapter.jar>,<diagnostics.jar>
-Dstp.spring-data.enabled=true
-javaagent:<stp-agent.jar>=output=<stp.json>;includes=org.springframework.samples.petclinic.;instrumentation=on
```

The six passing fresh-JVM runs were Owner individual, Vet individual, combined
alphabetical, combined reverse-alphabetical, combined STP-before-JaCoCo, and
combined adapter-disabled. JaCoCo-before-STP was used for the first four.

## Runtime and repository facts

The agent installed the sole runtime registry service. The service-loader JUnit
listener, ASM hooks and Spring Data advice used that instance; agent shutdown
JSON contains both ASM and repository facts.

`ClinicServiceTests#shouldInsertOwner` produced:

```text
OwnerRepository#findByLastNameStartingWith(
  Ljava/lang/String;
  Lorg/springframework/data/domain/Pageable;
)Lorg/springframework/data/domain/Page; count=2

OwnerRepository#save(Ljava/lang/Object;)Ljava/lang/Object; count=1
```

Both facts have `SPRING_DATA_PROXY`, `domainType=Owner`, `SUCCEEDED`,
`SPRING_DATA`, and `OBSERVED`.

`PetClinicIntegrationTests#findAll` produced:

```text
VetRepository#findAll()Ljava/util/Collection; count=2
```

The public inner Spring Data invocation listener observed `findAll` once and SQL
showed one underlying Vet load. The second caller event was served by Spring
Cache.

## Structural audit

All three real PetClinic repositories reached `AUDIT_PASSED`. For each:

- exactly one STP advisor exists at index zero;
- canonical lookup returns the identical object;
- existing advisor identities and relative order are unchanged;
- proxy kind, interfaces, target source and proxy depth are unchanged.

In the full context, final `ownerRepository` fingerprint includes the existing
`PersistenceExceptionTranslationAdvisor` behind STP. Final `vetRepository` has
STP at index zero and `CacheInterceptor` at index one. No additional proxy was
created by STP.

## Attribution, order and JaCoCo

Owner facts occur only under the Owner test and Vet facts only under the Vet
test. There are zero unattributed repository events, zero late events and no
cross-test contamination. Alphabetical, reverse-alphabetical and reversed
Java-agent order have equivalent dependency sets.

Both JaCoCo-before-STP and STP-before-JaCoCo pass, produce `jacoco.exec` and STP
JSON, and retain identical repository facts. There are no duplicate hooks or
advisors and no verification, linkage, Mockito or Byte Buddy failures.

Adapter-disabled output contains ASM method facts and no repository facts.

## Exploratory performance

One warm-up plus five measured fresh JVMs were run for every configuration:

| Configuration | Samples (seconds) | Median | p95 | Median overhead |
|---|---|---:|---:|---:|
| Baseline | 9.497, 9.436, 10.099, 9.501, 9.290 | 9.497 | 10.099 | 0.00% |
| ASM only | 9.500, 10.376, 10.542, 9.503, 9.752 | 9.752 | 10.542 | 2.69% |
| Adapter only | 9.734, 9.812, 10.281, 9.322, 9.333 | 9.734 | 10.281 | 2.50% |
| ASM + adapter | 10.604, 9.588, 9.460, 9.944, 15.434 | 9.944 | 15.434 | 4.71% |

The combined median passes the exploratory below-10% gate. The 15.434-second
sample is retained as an outlier; no production performance claim is made.
Combined measured runs transformed 19 classes/91 methods, collected 10 raw and
10 unique method hits, five repository invocations, and wrote 25,185-byte JSON.

## Evidence and validator

Reviewed deterministic evidence:

```text
stp-petclinic-spike/spring-data-acceptance/normalized-acceptance.json
stp-petclinic-spike/spring-data-acceptance/performance-summary.json
```

The no-argument validator checks exact descriptors/counts, evidence semantics,
two-order and agent-order stability, contamination, late/unattributed events,
one underlying Vet execution, advisor count/index/cache order, disabled mode and
JaCoCo files:

```bash
ruby stp-petclinic-spike/spring-data-acceptance/validate_acceptance.rb
```

## First-version limitation

Factory metadata known at the smart-singleton phase is resolved there, so lazy
PetClinic repository products are included. A repository factory/product first
registered after that phase remains unsupported with bounded reason
`REPOSITORY_CREATED_AFTER_INSERTION_PHASE`. There is no lazy mutation on first
invocation and no guessed event.

## Recommendation

Proceed to stabilization within the existing narrow scope: real synchronous
Spring Data `Advised` repositories only. Mock, async/reactive, SQL/table and MVC
support remain excluded.
