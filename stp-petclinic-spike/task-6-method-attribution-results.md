# Task 6: PetClinic method attribution results

## Scope and environment

This experiment connects the JUnit Platform listener to ASM method-entry
instrumentation and attributes only PetClinic application-method hits. It adds
no Spring, MVC, repository, persistence, entity, table, configuration, async
propagation, or test-selection integration.

- Smart Test Picker baseline: `b7ab3b85ec69cef89c02b0b0af58f757c8f65ca7`
- Spring PetClinic: `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`
- Spring Boot 4.1.0; SapMachine 21.0.12; Maven 3.9.16
- macOS 26.5.1 arm64; JaCoCo 0.8.15

The exact repeatable runner and validator are `task6_experiment.rb` and
`validate_task6_results.rb` in this directory.

## Integration design and commands

`premain` creates one `RuntimeContextService`, installs it in the isolated
runtime registry (now `RuntimeContextRegistry`), and installs a `RuntimeHooks`
sink backed by that same aggregator. The agent JAR includes the JUnit adapter
and service descriptor.
Surefire discovers the no-argument listener after `premain`; it obtains the
already installed service. At shutdown the agent serializes the aggregator and
then closes both registrations. There is no second aggregator.

Only `includes=org.springframework.samples.petclinic.` is enabled. Test output
directories, STP/JDK/framework packages, and generated proxy-like names are
excluded. Constructors and class initializers remain excluded. Common args:

```text
instrumentation=on;includes=org.springframework.samples.petclinic.;output=<file>;runId=<name>;debug=false
```

Commands used include:

```bash
GRADLE_USER_HOME=/tmp/stp-gradle-home ./gradlew :stp-agent:agentJar
MAVEN_USER_HOME=/tmp/stp-petclinic-maven-home ./mvnw -Dmaven.repo.local=/tmp/stp-petclinic-m2 -Dtest='OwnerControllerTests#processCreationFormSuccess' test
MAVEN_USER_HOME=/tmp/stp-petclinic-maven-home ./mvnw -Dmaven.repo.local=/tmp/stp-petclinic-m2 -Dtest='OwnerControllerTests#processCreationFormSuccess,ClinicServiceTests#shouldInsertOwner,PetClinicIntegrationTests#findAll' -Dsurefire.runOrder=alphabetical test
ruby stp-petclinic-spike/task6_experiment.rb
ruby stp-petclinic-spike/validate_task6_results.rb
```

The runner supplies both `-javaagent` entries through Maven's test JVM
`argLine`, exercises reverse ordering, and records expanded commands.

## Expected versus actual dependencies

Every dependency contains the Platform unique ID, readable class/method,
canonical method key, stable method ID, `ASM_METHOD_ENTRY`, and raw hit count.
IDs were validated against the emitted catalog.

### Controller

`OwnerControllerTests#processCreationFormSuccess` produced 26 unique methods
(36 raw hits combined), including all required methods:

```text
OwnerController#findOwner(Ljava/lang/Integer;)Lorg/springframework/samples/petclinic/owner/Owner;
OwnerController#setAllowedFields(Lorg/springframework/web/bind/WebDataBinder;)V
OwnerController#processCreationForm(Lorg/springframework/samples/petclinic/owner/Owner;Lorg/springframework/validation/BindingResult;Lorg/springframework/web/servlet/mvc/support/RedirectAttributes;)Ljava/lang/String;
```

The rest are executed `Owner`, `Pet`, `Visit`, `BaseEntity`, `NamedEntity`, and
`Person` accessors. No repository implementation, framework, repository-only,
or Vet method was attributed.

### Repository

`ClinicServiceTests#shouldInsertOwner` produced six unique/raw hits:
`BaseEntity#getId` plus the `Owner`/`Person` setters used to build the entity.
It reported no Spring Data method body. PetClinic declares an interface while
the implementation is a generated proxy, deliberately excluded by this task.

### Integration

`PetClinicIntegrationTests#findAll` produced no attributed PetClinic method.
`VetRepository.findAll()` runs in a Spring Data proxy, and Hibernate can fill
fields without PetClinic accessors. ASM-only instrumentation correctly did not
invent a repository body. A later semantic adapter is required to observe that
dependency. No MVC or Owner-controller method appeared.

## Transformation and contamination

The combined alphabetical run transformed 19 application classes and 91
ordinary methods: `PetClinicApplication`, `PetClinicRuntimeHints`, `BaseEntity`,
`NamedEntity`, `Person`, `Owner`, `OwnerController`, `Pet`, `PetController`,
`PetTypeFormatter`, `Visit`, `VisitController`, `CacheConfiguration`,
`CrashController`, `WebConfiguration`, `WelcomeController`, `Vet`,
`VetController`, and `Vets`. Catalog presence means loaded/transformed, not hit.

Automated validation confirmed required controller methods; no invented
repository implementation; no controller/Vet sentinel crossing; equal
dependency sets for alphabetical, reverse-alphabetical, and STP-first runs;
catalogued IDs and known tests for every attributed hit; no test/proxy class
transformation; no completed-test reactivation; and deterministic normalized
JSON.

Alphabetical had four global unattributed startup hits from cache/web
configuration and three explicit late web-configuration hits. Reverse had zero
global and seven late; STP-first had four global and three late. These lifecycle
differences do not change leaf-test sets. Startup was not assigned to test one,
and late hits did not enter dependency arrays.

## JaCoCo coexistence

JaCoCo-before-STP and STP-before-JaCoCo both passed. JaCoCo exec sizes were
657,096 bytes (alphabetical), 656,979 (reverse), and 655,138 (STP-first). STP
output was generated, dependency sets matched, and
`alreadyInstrumentedClasses` was zero. There was no duplicate hook,
`VerifyError`, Mockito/Byte Buddy or linkage failure, transformation error,
collision, or agent error. Large raw exec files were removed after recording
their sizes and coexistence result.

## Performance

Each mode had one warm-up and five measured fresh JVMs. These are total Maven
durations in seconds:

| Configuration | Samples | Median | p95 | Median delta |
| --- | --- | ---: | ---: | ---: |
| Baseline, JaCoCo only | 10.425403, 10.422874, 10.447697, 11.429677, 10.782339 | 10.447697 | 11.429677 | — |
| STP, instrumentation off | 11.039296, 10.442296, 11.123630, 10.491671, 10.628790 | 10.628790 | 11.123630 | +1.733% |
| STP, instrumentation on | 10.288546, 10.498172, 10.400975, 10.096025, 11.140775 | 10.400975 | 11.140775 | -0.447% |

The negative delta is noise, not a speedup claim. There is no measurable
positive overhead here, so the exploratory 10% gate passes; this is not a
production performance conclusion.

The five on-runs spent 72.3–76.2 ms transforming; each transformed 19
classes/91 methods, collected 49 raw/30 unique hits, wrote 34,857 bytes, and
reported four global unattributed/three late events. The diagnostic alphabetical
run used 75,584,662 transformer ns and wrote 34,893 bytes.

## Limitations and recommendation

Reviewed results are in `task-6-outputs/`, including `task-6-summary.json` and
`task-6-normalized.json`; build directories and large JaCoCo binaries are not
retained. Attribution is same-thread only and does not cover executors, server
threads, reactive work, HTTP propagation, entities, or tables.

**Recommendation: proceed, narrowly.** Concrete PetClinic method attribution
has correct leaf identity, stable order, no contamination, and passes the
exploratory overhead gate. The model-only repository set and empty integration
set justify a narrowly scoped Spring Data or persistence adapter next, not
broader ASM tracing.
