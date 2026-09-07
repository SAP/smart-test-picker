# TASK 28 — PetClinic Gradle ASM end-to-end validation

## Baseline and fixture

The validation started on `research/asm-codex` at
`58f84a7bb1f93161282240d268a1080beccb6bda`. The STP worktree already contained the
uncommitted TASK 27 implementation; it was preserved as the task baseline. Spring PetClinic was cloned
from `https://github.com/spring-projects/spring-petclinic.git` and pinned, without revision substitution,
to `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`. Its working tree was clean before the integration patch.

PetClinic supplied Gradle wrapper 9.5.1. Gradle launched on OpenJDK 21.0.11 and its Java 17 toolchain ran
tests on OpenJDK 17.0.19. The independent inventory task was `test`; production mapping used only
`generateSmartTestCoverage`.

The temporary fixture patch added the STP plugin through `pluginManagement.includeBuild`, enabled normal
project JaCoCo, and configured:

```groovy
smartTestPicker {
  coverageCollector = 'ASM'
  revision = '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272'
  shardId = providers.systemProperty('stp.shardId').orElse('petclinic-single')
  coverageIncludes = ['org.springframework.samples.petclinic.']
  coverageExcludes = []
}
```

TASK 27 supports package prefixes, and PetClinic colocates production and test classes in the same
packages. A narrower class-name exclusion cannot be expressed without changing selector semantics.
The include plus mandatory framework exclusions resulted in 29 transformed application-namespace
classes. No manual `-javaagent` or projector invocation was used.

The first attempt exposed an external-consumer packaging limitation: the plugin's default
`com.sap.oss.smart-test-picker:stp-agent:0.1.0` coordinate is not published and composite substitution
did not resolve it. The fixture therefore supplied the plugin's `stpAgent` configuration with the
locally built production shaded agent JAR. This is production Gradle wiring, but it means automatic
external agent resolution is not yet validated.

## Inventory and single-shard result

Gradle's ordinary JUnit XML, collected before examining the fragment, reported 73 logical/physical
executions: 69 runnable and four environment-disabled database tests. The four skipped identities were
the `findAll` and `ownerDetails` methods in `MySqlIntegrationTests` and `PostgresIntegrationTests`.
There were no failures or aborted tests. Exact identity comparison, after removing the JUnit XML display
suffix `()`, found 69 mapped, zero unmapped, zero missing, and zero unexpected identities.

The full `petclinic-single` run passed. The production plugin selected ASM and emitted schema-v2 with
the exact revision and `collection.completed=true`. The actual `CoverageFragmentCodec` successfully
decoded it. The fragment contains 18 covered classes, 418 test-to-class edges, 89 covered method
identities, 1,343 test-to-method edges, and 10 setup scopes. Sixty-three tests have
`COLLECTED_WITH_COVERAGE`; six have `COLLECTED_EMPTY`; every outcome is `PASS`.

The diagnostic contains zero agent errors, transformation errors, method-ID collisions, runtime-init
failures, fragment failures, unsupported identities, setup errors, and `LATE_EVENT`s. It contains 46
quarantined `NO_ACTIVE_TEST` events; none entered a leaf or setup edge. Metrics were 20,482 classes seen,
29 transformed, 146 methods instrumented, 3,816 raw method hits, and 102 unique method hits.

## Identity audit

All method identities contain JVM descriptors. Constructors are present; no static initializer was
observed. `Owner#getPet` was observed with the distinct `Integer`, `String`, and `String,boolean`
descriptors. `PetTypeFormatter#parse` and `print` each contain both typed and object-return bridge forms,
providing real synthetic/bridge evidence. No covered nested production class was observed.

Ordinary and nested tests are present and normalized to declared methods, including nested
`PetControllerTests` and `PetValidatorTests` containers. This revision contains no parameterized,
repeated, template, or dynamic tests, so this run makes no claim for those categories.

## Setup and lifecycle safety

All 10 emitted scopes are `CONTAINER` scopes and every scope has exactly one affected owning container.
There are no run-wide affected-container sets and no Cartesian attribution. The historical false
relations from `CacheConfiguration`, `CrashController`, and `WelcomeController` to `ValidatorTests` are
absent. PetClinic has no `@AfterAll` method at this revision; no last-leaf leakage was observed, but this
application run does not add positive `AfterAll` coverage beyond TASK 25 fixtures.

## Repeatability and shards

Two equivalent `petclinic-single` runs produced byte-identical fragments with SHA-256
`f9e548156745a5143340017533fb0f22374ab39c9b202e14292b73fa5132e40a`. Every semantic diff count is zero.

The independent inventory was split by deterministic class filters into three non-overlapping shards:

- `petclinic-1`: `OwnerControllerTests` and `ClinicServiceTests` — 27 tests.
- `petclinic-2`: `PetControllerTests*`, `PetValidatorTests*`, and `VisitControllerTests` — 24 tests.
- `petclinic-3`: the remaining application, model, formatter, system, vet, and skipped database suites —
  18 runnable tests plus four skipped executions.

Each shard independently emitted a completed schema-v2 fragment with the exact revision, correct shard
ID, no unmapped tests, and zero critical integrity failures. Their normalized union has 69 tests, 418
class edges, 1,343 method edges, 68 setup edges, 69 outcomes, and zero unmapped entries. Every exact
single-versus-union diff is zero. No scheduled logical test was duplicated or omitted. Artifacts were
isolated under distinct `build/stp/coverage/_generateSmartTestCoverage/<shard>/` directories.

## Coexistence, defaults, cache, and fallback

Normal PetClinic `test` plus `jacocoTestReport` produced `build/jacoco/test.exec` and XML/HTML reports.
The STP ASM fragment remained valid, the JVM started successfully, and no verification errors occurred.
This validates project JaCoCo plus STP ASM, not a combined STP collector.

With `coverageCollector` omitted, a bounded real-project run printed
`Smart Test Picker coverage collector: ASM` and emitted a valid fragment. Two identical bounded runs with
`--configuration-cache` reported `Configuration cache entry stored` and then `reused`; the fragment
remained valid.

The optional JACOCO fallback smoke selected JACOCO and did not attach the ASM agent, but the real project
reported no matching per-test exec files for the custom mapping task. Thus backend selection passed and
the legacy mapping path failed this smoke. This does not alter the ASM semantic results, but it is an
integration limitation.

## Decision

The production Gradle ASM execution path itself is validated for full PetClinic collection,
schema-v2 correctness, population accounting, descriptor-aware identities, repeatability, setup safety,
three-shard semantic invariance, ordinary project-JaCoCo coexistence, default selection, and
configuration-cache reuse. It is not yet reasonable to call the overall external Gradle integration
production-ready because automatic agent resolution failed for the composite consumer and the advertised
JACOCO fallback did not produce its legacy mapping artifacts. Maven integration has not started. Task 5c
remains in progress; 5b and 5d are unchanged.

Compact machine-readable results are in `stp-petclinic-validation/task28/`.
