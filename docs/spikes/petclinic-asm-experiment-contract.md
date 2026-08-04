<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# PetClinic ASM experiment contract

## Scope and revision lock

Task 1 freezes a repeatable, agent-free experiment. It adds no Java code, ASM,
runtime collection, JUnit listener, Spring adapter, persistence adapter, or
regression-test selection. The temporary `stp-petclinic-spike` directory is not
included by `settings.gradle` and cannot be published as a project artifact.

| Repository | Exact revision |
| --- | --- |
| Smart Test Picker | `b7ab3b85ec69cef89c02b0b0af58f757c8f65ca7` |
| Spring PetClinic | `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` |

The authoritative machine-readable lock is
`stp-petclinic-spike/experiment-manifest.yaml`. Never replace the PetClinic
commit with a branch name or a moving `HEAD`.

## Selected tests and expected paths

### Controller slice

```text
OwnerControllerTests#processCreationFormSuccess
→ POST /owners/new
→ OwnerController.findOwner(...)
→ OwnerController.setAllowedFields(...)
→ OwnerController.processCreationForm(...)
→ OwnerRepository.save(Owner)
→ entity Owner
```

This is an `@WebMvcTest`. The repository is an `@MockitoBean`. `owners` is the
entity's mapped table, but the mandatory **observed table set is empty**. Any
observed SQL table is a failure; `vets` dependencies are contamination.

### Repository slice

```text
ClinicServiceTests#shouldInsertOwner
→ OwnerRepository.findByLastNameStartingWith(...)
→ OwnerRepository.save(Owner)
→ OwnerRepository.findByLastNameStartingWith(...)
→ entity Owner
→ observed table owners
```

This is an `@DataJpaTest` using Hibernate and embedded H2. It must have no MVC
endpoint. Baseline SQL is `owners` SELECT, INSERT, SELECT. A vet dependency or
any endpoint is forbidden.

### Broad integration test

```text
PetClinicIntegrationTests#findAll
→ VetRepository.findAll()
→ entity Vet
→ observed table vets
```

This is a random-port `@SpringBootTest`, but this test method makes no HTTP
request. `vet_specialties` and `specialties` are validation targets, not
mandatory guarantees until the future Hibernate collector supplies evidence.
The baseline log did show both in a joined secondary SELECT. An endpoint or an
`owners` INSERT is forbidden.

No selected test requires cross-thread attribution. Random-port startup alone
is not an endpoint event, and `findAll` invokes its repository on the JUnit
thread.

## Baseline commands

Clone once and detach at the exact target:

```bash
git clone https://github.com/spring-projects/spring-petclinic.git spring-petclinic
git -C spring-petclinic fetch origin 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
git -C spring-petclinic checkout --detach 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
git -C spring-petclinic rev-parse HEAD
```

From the pinned PetClinic directory, run tests separately:

```bash
./mvnw -Dtest='org.springframework.samples.petclinic.owner.OwnerControllerTests#processCreationFormSuccess' test
./mvnw -Dtest='org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner' test
./mvnw -Dtest='org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll' test
```

Run all three in deterministic class orders (Surefire orders classes; methods
are individually selected):

```bash
TESTS='org.springframework.samples.petclinic.owner.OwnerControllerTests#processCreationFormSuccess,org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner,org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll'
./mvnw -Dtest="$TESTS" -Dsurefire.runOrder=alphabetical test
./mvnw -Dtest="$TESTS" -Dsurefire.runOrder=reversealphabetical test
```

Capture environment and resolved dependencies with the result:

```bash
java -version
./mvnw --version
./gradlew --version
uname -a
sw_vers
./mvnw dependency:tree -DoutputFile=target/dependency-tree.txt
./gradlew dependencies > target/gradle-dependencies.txt
git rev-parse HEAD
```

The recorded local run used system Maven with an isolated repository and the
equivalent explicit test-phase goals:

```bash
mvn -Dmaven.repo.local=/tmp/stp-petclinic-m2 -o -Dtest='<test>' jacoco:prepare-agent surefire:test
```

## Measurement procedure

1. Verify both revisions and a clean PetClinic checkout. Record Java, Maven,
   Gradle, OS, architecture, dependency tree, timezone, and command.
2. Warm dependency caches before timing. Run without an STP agent, but retain
   PetClinic's JaCoCo configuration. Do not alter application logging or code.
3. Run each selected test in a fresh Maven invocation, then both group orders.
   Read per-class/test duration from Surefire XML and total duration from Maven.
4. Preserve Surefire XML/text, console SQL evidence, `jacoco.exec`, and any
   generated JaCoCo report. Note startup/shutdown events outside test bodies.
5. Copy `metrics-template.yaml` for each future configuration. Agent fields are
   null in the baseline; never convert null to zero.
6. Compare agent-on with agent-off outcomes and durations under the same warmed
   environment. Repeat enough times to report a median before applying the 10%
   spike threshold.

The 2026-08-02 baseline is in `baseline-results.yaml`: all individual tests and
both orders passed. JaCoCo 0.8.15 was active and produced `target/jacoco.exec`;
Surefire produced XML and text reports. The direct `test`-phase invocation did
not execute the later JaCoCo report goal, so no HTML/XML coverage report is
claimed. Mockito dynamically attached Byte Buddy 1.18.10 after JVM startup.

Startup is evidence, not automatically a test dependency. The MVC slice starts
a dispatcher servlet; the JPA slice starts H2/Hikari and an entity manager; the
integration test additionally starts random-port Tomcat and actuator endpoints.
No shutdown lines appeared in the successful isolated-test logs. These events
must be unattributed or lifecycle-labelled unless they occur inside a test's
attribution window.

## Contamination rules

- Controller sentinel: `POST /owners/new`; no observed SQL table.
- Repository sentinel: `owners` SELECT/INSERT; no endpoint.
- Integration sentinel: `VetRepository.findAll`; no endpoint or `owners` INSERT.

An event expected for one test but assigned to another is cross-test
contamination. A startup event assigned to whichever test happens to run first,
an event after test completion assigned to the next test, or a retained event
from the previous deterministic order also counts. Missing attribution is an
expected sentinel absent from its test. Incorrect attribution includes a mapped
table reported as observed without runtime evidence.

## Success and failure interpretation

Task 1 succeeds when the revision/test contract is reproducible, all selected
tests pass without an STP agent, expected and forbidden evidence is explicit,
and current STP behavior remains unchanged. The future spike succeeds only if
it preserves outcomes, attributes sentinels to the correct test, adds useful
method plus Spring/persistence evidence, emits auditable output, has no material
contamination, and stays below the provisional 10% median overhead threshold.

Stop or narrow the future experiment if reliable repository/table evidence
cannot be separated from mapped metadata, lifecycle events cannot be separated
from test events, outcomes change, or contamination remains significant.
Absence of `vet_specialties` or `specialties` alone is not failure in the first
collector iteration; absence of `vets` is.

## Baseline limitations and unresolved questions

- The official Maven wrapper target is 3.9.16, but measured runs used installed
  Maven 3.8.6 because the wrapper distribution was not already cached. Commands
  above remain wrapper-first; remeasure before comparing agent overhead.
- The first dependency warm-up was excluded from timings.
- A restricted-sandbox controller attempt could not execute because Mockito's
  Byte Buddy agent could not self-attach. The same test passed outside that
  restriction; the failed infrastructure attempt is not a product baseline.
- Future runs must decide whether SQL captured during context initialization is
  lifecycle evidence or an unattributed event. It must never be silently charged
  to the first selected test.
- The exact stable representation of overloaded repository methods remains a
  Task 2 concern; this contract records the actual PetClinic method names only.

