# STP-PETCLINIC-PARITY-01

> Follow-up correction: [STP-GENERATED-CLASSES-01](../stp-generated-classes-01/REPORT.md)
> established that the six extra Gradle catalog classes are Spring Data AOT build
> outputs, not evidence that Maven rejected the same six runtime definitions.
> The diagnostics interpretation below is corrected; retained counters, fragments,
> attribution comparisons and historical commands are unchanged.

## Result and the baseline qualification

**Verdict A for the measured coverage contract:** the two ASM fragments have identical
per-test attribution and outcomes, and identical setup scopes after the explicit target
owner normalization below. This is one pinned H2 subject, not universal build-tool
equivalence, identical JVM behavior, or collector equivalence with JaCoCo.

**The requested MAVEN-ASM-10 scalar baseline is not reproduced literally.** Report this
before the parity result:

- `classesSeen`: historical 19,261; fresh Maven 19,260. `classesIgnored` likewise drops
  from 3,976 to 3,975. The diagnostic does not identify that individual class, so its
  cause is **unresolved**, not attributed to a refactor without evidence.
- The task says five `PASS/COLLECTED_EMPTY` tests, but the retained ASM-10 fragment
  contains **six**, and this run has the same six. This is a baseline-description
  discrepancy, not a new empty test. The complete identities are retained in
  [maven-regression.json](raw/maven-regression.json).
- 23 transformed classes, 116 instrumented methods, 1,343 leaf method edges and zero
  transformation errors are reproduced exactly.
- Stronger than those counts: the **entire fresh Maven fragment is byte-identical**
  to ASM-10, SHA-256
  `0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9`.
  Thus no coverage/ownership/outcome regression is observed. A change in total JVM
  class-loading count alone does not establish that ASM-16 or the Gradle JaCoCo fix
  changed attribution. No production correction was made.

## Provenance and Step 0

- STP: `research/asm-codex`, `6413679624792f263e196174fb6089f8bc9f891f`.
- Subject: `spring-projects/spring-petclinic`,
  `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, tree
  `3129e707de6f32acf23549d9e0706d3d1c270405`.
- Separate disposable local clones, with empty tracked `git diff` checks after execution.
  No subject source or shipped build file was edited.
- The pinned tree **does ship a usable Gradle build**: [build.gradle](raw/subject/build.gradle),
  [settings.gradle](raw/subject/settings.gradle), wrapper scripts/JAR,
  [wrapper configuration](raw/subject/gradle-wrapper.properties), and a
  [Gradle CI workflow](raw/subject/gradle-build.yml), alongside [pom.xml](raw/subject/pom.xml).
  Unchanged Gradle `help`, compilation, inventory and mapping all succeeded.
- Maven 3.9.15; Maven/test JVM SapMachine 21.0.13.0.3; Gradle wrapper 9.5.1,
  daemon JDK 21, subject compiler/test toolchain SapMachine 17.0.21.0.3 as specified
  by the shipped Gradle build. Maven compiles with `release 17`. These environments
  are **not identical**; the subject toolchain was not silently overridden.
- Both resolve Spring Boot 4.1.0, Spring Data JPA 4.1.0 and JUnit Jupiter 6.0.3.
  Gradle's launcher is 6.0.3. Maven uses Surefire 3.5.6. The original builds do have
  dependency/configuration differences; this task did not rewrite them to match.
  Actual classpaths are in [Gradle fork input](raw/gradle/fork-classpath.txt) and
  [Maven XML properties](raw/maven/surefire-reports/TEST-org.springframework.samples.petclinic.PetClinicIntegrationTests.xml).
- Both mapping forks use the **same shaded STP agent**, SHA-256
  `dfa69a8afe2689b13e8ccecd9ee26817f252aa354485e4ffa37279e7ded981cc`, including the
  same runtime and JUnit adapter. [Artifact/source provenance](raw/provenance.txt).
  All STP components remain `0.3.0-SNAPSHOT`.

The Maven repository was task-local, seeded with ASM-10's third-party cache only
(`com/sap/oss/smart-test-picker/` excluded). Current STP artifacts resolved from the
existing functional-test repository. Gradle used an isolated user home and that same
functional-test repository, plus public repositories. No developer-local STP install
or Jenkins plugin was used.

## Executed scope equality

The existing ASM-10 `Measure.java` creates the unchanged measurement-POM overlay:
single reusable Surefire fork, H2 default, excluding `MySqlIntegrationTests` and
`PostgresIntegrationTests`. JaCoCo is skipped; only ASM collects.

[gradle.init.gradle](gradle.init.gradle) supplies the same exclusions externally,
`runtimeSchemaVersion=3`, `mappingTestTasks=[':test']`, one reusable fork, parallel
execution disabled and 768 MiB test heap. The subject build itself is unchanged.
Its existing AOT preparation tasks remain in the task graph; no AOT workaround was
added. The measured mapping JVM is the ordinary `:test` task, not an AOT test task.

Gradle inventory discovers 73 identities because its discovery does not apply those
Test-task excludes. [Prepare.java](Prepare.java) uses the production inventory and
assignment codecs to exclude exactly the four MySQL/PostgreSQL identities and asserts
that the resulting 69 logical identities equal Maven's H2 inventory. This is not
mistaken for executed-scope proof.

The later proof uses **actual XML executions and produced fragments**: both have
exactly the same 69 passing logical identities, with no duplicate XML identity.
[executed-identity-equality.json](raw/executed-identity-equality.json) lists both full
sets and every XML-name conversion. Gradle XML spells no-argument methods `method()`;
Surefire spells them `method`. The helper accepts that conversion only when the
production fragment confirms the exact logical identity. An unknown spelling fails
the comparison. All coverage comparisons run only after this equality assertion.

## Schema, resolved scope and naming

Both produced fragments use schema 3, the exact pinned revision, and shard `local`.
No schema-2 fragment was compared with schema 3.

Verbatim resolved includes, **both runs**:

```text
org.springframework.samples.petclinic.
org.springframework.samples.petclinic.model.
org.springframework.samples.petclinic.owner.
org.springframework.samples.petclinic.system.
org.springframework.samples.petclinic.vet.
```

Verbatim resolved excludes, **both runs**, including mandatory agent exclusions:

```text
com.sap.oss.smarttestpicker.
jakarta.
java.
javax.
jdk.
net.bytebuddy.
org.hibernate.
org.jacoco.
org.junit.
org.mockito.
org.springframework.
sun.
```

Maven derives the includes; Gradle's extension receives those same five prefixes.
The agent honors these specific includes under the broader Spring exclusion.
The complete scope is nevertheless different. Verbatim Maven `productionSources`:

```text
/private/tmp/stp-petclinic-parity-01.fJDgaN/maven/target/classes
/private/tmp/stp-petclinic-parity-01.fJDgaN/maven/target/spring-petclinic-4.0.0-SNAPSHOT.jar
```

Gradle `productionSources=[]`; Maven `requireInstrumentation=true`, Gradle `false`.
This is the existing, documented [ASM-08 decision](../maven-asm-08/REPORT.md): Maven
adds a production-code-source allowlist; Gradle retains its prior prefix and conventional
test-output filtering. This task neither changes that contract nor supplies a new scope API.
Both complete resolved configurations are retained in [diagnostics.json](raw/diagnostics.json).

Use the **intersection of compiled production class identities**, listed exhaustively
in [scope-intersection.json](raw/scope-intersection.json): 25 classes. Maven additionally
emits five `package-info` class files. No covered leaf method, covered class or setup
class falls outside the intersection: every excluded-edge list is empty. Thus the
full unfiltered coverage comparison also agrees; no divergent edge was hidden by
intersection filtering.

Explicit normalization (printed in the equality ledger):

| Field | Maven | Gradle | Comparison value |
|---|---|---|---|
| Test identity | `maven:.::X#y` | `gradle::test::X#y` | `X#y` |
| Setup owner | `maven:.` | `gradle::test` | `ROOT_TEST` |
| Shard | `local` | `local` | `local` (unchanged) |

No setup id, type, affected container, method descriptor or covered class is renamed.

## Exact differential

[Compare.java](Compare.java) compiles against the **unchanged ASM-14 Differential.java**
and reuses its JSON/set readers and exact `test -> edge` construction. It adds the
required build-tool normalization, production fragment-codec validation, actual-execution
precondition, class/scope comparisons and diagnostic extraction. It is measurement
code only, not another collector or selector.

[differential.json](raw/differential.json) retains both complete edge sets and exact
left-only/right-only sets, not just totals:

| Compared artifact | Maven | Gradle | Difference |
|---|---:|---:|---|
| Executed logical identities | 69 | 69 | none |
| Descriptor-qualified leaf method edges | 1,343 | 1,343 | both directional sets empty |
| Leaf class edges | 418 | 418 | both directional sets empty |
| Setup scopes | 10 | 10 | identical after owner normalization |
| Executable unmapped identities/reasons | 0 | 0 | both empty |
| `collection.completed` | true | true | none |
| PASS/COLLECTED_EMPTY | 6 | 6 | same exact identities |

Every setup scope is `CONTAINER`. The ledger includes each id, type, covered classes,
affected containers and normalized owner. In particular the three HTTP test containers
retain their setup coverage on both sides. The original owners remain visible in the
[Maven fragment](raw/maven/stp/coverage-fragment-v3.json) and
[Gradle fragment](raw/gradle/fragment.json). There are **no differing coverage edges to name**.

## Diagnostics and localization

These are actual counters from the two agent diagnostics:

| Counter | Maven | Gradle |
|---|---:|---:|
| classesSeen | 19,260 | 19,954 |
| classesIncluded | 30 | 31 |
| classesExcluded | 15,255 | 15,496 |
| classesIgnored | 3,975 | 4,427 |
| classesTransformed | 23 | 29 |
| methodsConsidered | 121 | 151 |
| methodsInstrumented | 116 | 146 |
| methodsSkipped | 5 | 5 |
| methodIdCollisions | 0 | 0 |
| transformationErrors | 0 | 0 |
| rawMethodHits | 3,816 | 3,816 |
| uniqueMethodHits | 102 | 102 |

Every emitted unattributed reason is zero in **both global and every per-test array**:
`NO_ACTIVE_TEST`, `TEST_ALREADY_FINISHED`, `UNKNOWN_CONTEXT`, `STARTUP`, `SHUTDOWN`,
`LATE_EVENT`, `TRANSFORMATION_ERROR`. Agent errors are empty. These counters are
computed from retained event records, not assumed from fragment completeness.

The difference is in the **instrumentation catalog**, not observed attribution.
Gradle has six additional generated accessor classes and exactly 30 additional methods:

```text
org.springframework.samples.petclinic.owner.Owner__Accessor_cj2ue3
org.springframework.samples.petclinic.owner.Pet__Accessor_405njj
org.springframework.samples.petclinic.owner.PetType__Accessor_uuas9h
org.springframework.samples.petclinic.owner.Visit__Accessor_cfh48z
org.springframework.samples.petclinic.vet.Specialty__Accessor_l1edsu
org.springframework.samples.petclinic.vet.Vet__Accessor_mvphe1
```

All 30 exact descriptor-qualified methods are listed as Gradle-only catalog entries
in `diagnostics.json` and `scope-intersection.json`. **None has a hit record**; even
the complete global `methodHits` arrays are equal. The catalog delta accounts exactly
for the +6 transformed classes / +30 instrumented methods.

**Corrected by STP-GENERATED-CLASSES-01:** Gradle's measured task graph executes
`processTestAot`; all six accessor class files exist in `build/generated/aotTestClasses`,
which is on its test fork classpath. The measured Maven build does not run test AOT
and has no corresponding generated outputs. The generator is Spring Data's AOT
accessor machinery, not Hibernate entity enhancement. Gradle includes 25 main
classes plus six accessors (31); Maven includes 30 main classes, including five
additional `package-info` files. Those five have no instrumentable methods, explaining
why the included delta is only one while the transformed delta is six. See the
[follow-up evidence and exact class sets](../stp-generated-classes-01/REPORT.md).
The scope policies do differ, but this unequal-build comparison does **not** establish
scope rejection as the cause of these six classes being absent from Maven's catalog.
Overall `classesSeen` also includes Gradle workers, different build classpaths and
different test JVM versions; it is not a pure application-scope count.

Consequently, the evidence supports identical **observed attribution**, different AOT
build outputs, and a separately documented scope-policy difference, not identical
instrumentation universes or an isolated scope-policy experiment. No integration defect
requiring production changes was demonstrated.

## Commands, attempts and validation

The actual disposable workspace was `/private/tmp/stp-petclinic-parity-01.fJDgaN`.
The [Maven command](raw/maven/command.txt), [Maven console](raw/maven-console.log),
[inventory equality](raw/inventory-equality.txt), [Gradle inventory log](raw/gradle-inventory.log),
[Gradle mapping log](raw/gradle-mapping.log) and [comparison output](raw/comparison.log)
are retained. Both mapping commands exited 0.

The first plain Gradle `help` attempt inherited machine-global SAP repository init
scripts and was cancelled; [its log](raw/gradle-help.log) is not parity evidence.
The isolated-home [help rerun](raw/gradle-help-isolated.log) succeeded. The initial
comparison helper rejected the XML display spelling `method()` versus `method`;
the explicit, fragment-checked conversion above corrected the measurement reader,
without rerunning tests or changing product behavior.

To reproduce in a disposable STP/evidence checkout, with Maven 3.9.15, JDKs 17/21,
Gson 2.10.1, and current STP functional-test artifacts available:

```sh
export PARITY_WORK="$(mktemp -d /tmp/stp-petclinic-parity-01.XXXXXX)"
export PARITY_SUBJECT=/absolute/path/to/upstream-petclinic-checkout
export PARITY_M2="$PARITY_WORK/m2"
export PARITY_GSON=/absolute/path/to/gson-2.10.1.jar
bash verification/stp-petclinic-parity-01/run-maven.sh
bash verification/stp-petclinic-parity-01/run-gradle.sh
bash verification/stp-petclinic-parity-01/analyze.sh
```

The Gradle shell wrapper records the same commands executed during this measurement;
it was consolidated afterward and syntax-checked, not falsely presented as a second
fresh execution. `Prepare.java`, `Compare.java` and `analyze.sh` were compiled/executed.
The external init file itself was used for both Gradle discovery and mapping.

**Baseline suites were not rerun:** no production code changed. Earlier suite counts
are not claimed as fresh. No JaCoCo measurement, Jenkins run, shared-context or parallel
qualification, mutation campaign, selector change or new subject scenario was performed.
The known shared Spring web-context boundary remains outside this result.

Shell syntax, measurement-helper compilation, report-relative links, changed-source
whitespace checks and `scripts/check-large-files.sh` passed. Only this new verification
directory is changed; pre-existing untracked work is preserved.
