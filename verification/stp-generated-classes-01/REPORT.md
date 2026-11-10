# STP-GENERATED-CLASSES-01 (revised)

## Verdict

1. **The shipped builds execute different AOT steps.** Gradle generates the six
   accessor classes during test AOT and puts them on the test classpath; the measured
   Maven build does neither. This is not evidence that Maven filtered out those same
   six runtime definitions. The previous parity report's scope-policy explanation was
   not established and is corrected in its **Diagnostics and localization** section.
2. **No Hibernate entity enhancement is active in either measured build.** No
   enhancer ordering claim follows. STP coexistence with an active persistence
   enhancer remains an untested boundary.
3. **YES:** a class defined entirely in memory, inside the agent's include prefix,
   is transformed and its invoked method reaches the real schema-v3 fragment.
   A disk-loaded positive control passes in the same test. This is a raw-agent
   experiment, not a claim that Maven's production-source allowlist accepts a null
   code source.

No production source, subject build, collector policy or selection behavior changed.

## Provenance and method

- Inspected checkout: `/Users/D061177/work/moje/smart-test-picker`;
  branch `research/asm-codex`, HEAD `498ada8831db49ea654af136a72329706a3cb0a0`.
- Subject remains PetClinic `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`.
- Steps 1–2 inspect the original parity task's retained builds, logs, diagnostics,
  classpaths and still-present compiled outputs. **The PetClinic suite was not rerun.**
  The disposable workspace inspected was `/private/tmp/stp-petclinic-parity-01.fJDgaN`.
- Step 3 is a **fresh** one-test Surefire run, offline, Maven 3.9.15, Surefire 3.5.6,
  Jupiter/Platform 6.0.3, SapMachine 21.0.13.0.3, compiler release 17.
- The existing shaded agent is `0.3.0-SNAPSHOT`, the same binary used in the parity
  measurement, SHA-256
  `dfa69a8afe2689b13e8ccecd9ee26817f252aa354485e4ffa37279e7ded981cc`.
  Its original build/source provenance is in the
  [parity record](../stp-petclinic-parity-01/raw/provenance.txt).
- Existing unrelated untracked `examples/`, `verification/maven-asm-shapes/` and
  `verification/maven-asm-spike/` were left alone.

## Step 1 — AOT output, not Hibernate enhancement or an isolated scope test

The retained [Gradle build](../stp-petclinic-parity-01/raw/subject/build.gradle)
applies Spring Boot 4.1.0 and GraalVM native build tools 1.1.2. Its **actual** mapping
task graph includes `processTestAot`, `compileAotTestJava`, `processAotTestResources`
and `aotTestClasses` before `test`. The measurement init script does not introduce
those AOT tasks. The original fork classpath includes `build/generated/aotTestClasses`.

The retained [POM](../stp-petclinic-parity-01/raw/subject/pom.xml) also declares
native/Spring Boot tooling, but the measured Maven invocation does not activate
test AOT. Merely declaring native tooling is not equivalent to running its goals.
No Hibernate enhancement plugin/task is configured in either subject build, and
none appears in the retained executed tasks/goals. Thus these are **not equivalent
persistence-adjacent build pipelines**, even though both resolve Spring Data JPA 4.1.0.

The six files physically present under Gradle's `build/generated/aotTestClasses/` are:

```text
org/springframework/samples/petclinic/owner/Owner__Accessor_cj2ue3.class
org/springframework/samples/petclinic/owner/Pet__Accessor_405njj.class
org/springframework/samples/petclinic/owner/PetType__Accessor_uuas9h.class
org/springframework/samples/petclinic/owner/Visit__Accessor_cfh48z.class
org/springframework/samples/petclinic/vet/Specialty__Accessor_l1edsu.class
org/springframework/samples/petclinic/vet/Vet__Accessor_mvphe1.class
```

No `__Accessor_` class file exists anywhere in the measured Maven `target/` tree.
Every Gradle accessor implements Spring Data's `PersistentPropertyAccessor`, not
a Hibernate enhancement interface. The exact resolved **Spring Data Commons 4.1.0**
JAR's bytecode shows `DefaultAotContext.ContextualTypeConfiguration` invoking
`AotMappingContext.contribute`, whose `AotAccessorFactory` extends
`ClassGeneratingPropertyAccessorFactory` and invokes
`potentiallyCreateAndRegisterPersistentPropertyAccessorClass`.
That factory's generator creates the accessor bytecode.

The task's premise that JPA rules out Spring Data as the generator is therefore
too broad. The [object-mapping documentation](https://docs.spring.io/spring-data/jpa/reference/data-commons/object-mapping.html)
does exclude JPA from that ordinary object-mapping discussion; it does not exclude
AOT contributions. The separate [JPA AOT documentation](https://docs.spring.io/spring-data/jpa/reference/jpa/aot.html)
explicitly documents generated accessor bytecode and `spring.aot.data.accessors.enabled`.
Those live pages currently describe 4.1.2; the retained **4.1.0 bytecode and actual
generated files**, not an assumption about a later release, establish the result here.

### Reconciling all four counters

| Class population | Maven | Gradle | Can supply instrumented methods here? |
|---|---:|---:|---|
| Main classes with instrumentable methods | 23 | 23 | Yes |
| Abstract-only repository interfaces | 2 | 2 | No |
| `package-info` class files | 5 | 0 | No |
| AOT-generated accessors | 0 | 6 | Yes |
| **classesIncluded** | **30** | **31** | Admitted before method inspection |
| **classesTransformed** | **23** | **29** | At least one method instrumented |

The two abstract-only interfaces are `owner.PetTypeRepository` and
`vet.VetRepository`. The five package-info files are in the root PetClinic,
`model`, `owner`, `system` and `vet` packages. Each generated accessor contributes
five methods: its constructor, static initializer, `getBean`, `getProperty` and
`setProperty`. Hence the method catalog increases by exactly 30: 116 → 146.
The arithmetic is **six added accessors minus five absent package-info classes**,
not six classes somehow hidden inside a one-class included delta.

These sets and assertions are in [class-accounting.json](raw/class-accounting.json).
The original counters do not record a per-class inclusion decision; the accounting
reconciles the compiled sets and instrumented method catalog exactly, rather than
claiming a nonexistent rejected-class trace.

The real scope-policy difference remains: Maven supplies production code-source roots;
Gradle uses prefixes plus conventional test-output filtering. `__Accessor_` does
not match the transformer's generated-framework-name exclusions, and
`generated/aotTestClasses` is not a conventional test-output path recognized there.
That explains why Gradle can instrument these available files. It does **not** prove
that the same files existed and were rejected in Maven. No counterfactual scope replay
or rewrite of the builds was performed.

Evidence:

- [Executed AOT steps, fork classpath and JPA transformer messages](raw/retained-log-excerpts.txt).
- [Accessor disassembly](raw/gradle-accessors-javap.txt), including all six interfaces
  and method bodies; each original class-file SHA-256 is in the accounting JSON.
- [Exact Spring Data generator disassembly and JAR checksum](raw/spring-data-generator-javap.txt).
- [Original complete catalogs and hit comparison](../stp-petclinic-parity-01/raw/diagnostics.json).
  All 30 extra methods have **no recorded hits**. The earlier attribution equality
  remains intact; this correction changes its explanation, not its measured edges.

## Step 2 — No active entity enhancer; ordering untested

Inspected the compiled `Owner`, `Pet`, `Visit`, `PetType`, `Vet`, `Specialty` and
their `BaseEntity`, `NamedEntity`, `Person` bases in **both** builds. Their disassembly
contains no `$$_hibernate_` members and none of `ManagedEntity`,
`PersistentAttributeInterceptable` or `SelfDirtinessTracker`.

Both mapping logs explicitly say:

```text
No LoadTimeWeaver setup: ignoring JPA class transformer
```

The Gradle AOT process says so too, separately from the actual test worker.
Together with the absent enhancement build step, this closes the question for
these measured builds: the entity classes are not enhanced by Hibernate here.
Generating separate Spring Data accessor classes is not rewriting the entities.
Hibernate proxies/instantiators are likewise not proof of entity enhancement.

There is no active STP/enhancer ordering to validate in this subject. **Compatibility
with an active Hibernate entity enhancement transformer remains untested**, not
proven safe by this task or by the earlier agent-ordering experiment.

Evidence: [Maven entity bytecode](raw/maven-entities-javap.txt),
[Gradle entity bytecode](raw/gradle-entities-javap.txt), and the retained log excerpts.
The inspection script asserts absence of the enhancement markers.

## Step 3 — Runtime-defined class: YES

The [minimal fixture](fixture/pom.xml) has exactly one Jupiter test,
`probe.InMemoryTest#recordsDynamicAndControl`. It:

1. Calls disk-loaded `generatedprobe.ControlValue.value()` and asserts 17.
2. Compiles a source string into a `ByteArrayOutputStream` using `JavaCompiler`
   and an in-memory file manager, with annotation processing disabled.
3. Calls `ClassLoader.defineClass` on those bytes. There is no generated class file
   or classpath resource; the defined class's code-source **location is null**.
4. Invokes `generatedprobe.DynamicValue.value()` reflectively and asserts 42.

The real shaded agent is attached to Surefire with `includes=generatedprobe.`,
runtime schema 3, target `maven:.`, shard `memory`, and no production-source allowlist.
This deliberately answers the independent **agent** question; it does not bypass or
qualify any supported Maven prepared-mapping restriction.

Fresh result: **exit 0; 1 test executed, 0 failures/errors/skips**. Agent metrics:
2 classes included, 2 transformed, 4 methods instrumented (two constructors plus
two value methods), 2 raw/unique method hits, 0 transformation errors, no agent errors.
The production fragment codec accepts the complete fragment, with one mapped
identity and zero unmapped entries:

```text
maven:.::probe.InMemoryTest#recordsDynamicAndControl
  -> generatedprobe.ControlValue#value()I
  -> generatedprobe.DynamicValue#value()I
outcome=PASS, collectionStatus=COLLECTED_WITH_COVERAGE
```

Evidence: [console](raw/memory/maven.log), [JUnit XML](raw/memory/surefire-reports/TEST-probe.InMemoryTest.xml),
[agent diagnostic](raw/memory/agent-diagnostic.json), [fragment](raw/memory/fragment.json),
[production-codec assertion](raw/memory/verification.txt).

## Commands and validation

From the STP root, the executed entry points were:

```bash
python3 verification/stp-generated-classes-01/inspect.py /private/tmp/stp-petclinic-parity-01.fJDgaN

PROBE_AGENT=/private/tmp/stp-petclinic-parity-01.fJDgaN/gradle/build/stp/artifacts/stp-agent.jar \
PROBE_M2=/private/tmp/stp-petclinic-parity-01.fJDgaN/m2 \
PROBE_MVN=/Users/D061177/Programs/apache-maven-3.9.15/bin/mvn \
bash verification/stp-generated-classes-01/run.sh
```

The [expanded Maven command](raw/memory/command.txt) includes the disposable fixture
directory, exact revision, output paths and offline dependency repository.
The run script refuses to overwrite existing evidence. The inspection entry point
requires the retained parity workspace; its results are preserved here so readers
need not rely on that temporary directory continuing to exist.

Full baseline suites were **not rerun**: no production code changed. No earlier
suite counts are presented as fresh results. Only the targeted fixture and evidence
assertions are fresh. No claim about selection, Jenkins, shared contexts, parallel
execution or persistence-enhancer coexistence is made.

Additional checks passed: production-codec verification including the exact test
identity, exact diagnostic hit-set assertions, JUnit executed-count assertions,
JSON parsing, relative file links in both changed reports, shell syntax,
`git diff --check`, and `scripts/check-large-files.sh`. The last check covers tracked
files; a separate check confirmed every new task file is below 5 MiB.

Remaining blocker: **none for this measurement**. Active-enhancer compatibility is
a named boundary outside this subject, not an unresolved explanation for these six
classes.
