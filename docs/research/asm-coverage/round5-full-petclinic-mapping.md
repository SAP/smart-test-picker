# Round 5: full PetClinic JUnit mapping validation

## Decision

**ASM NOT READY FOR BROADER EXPERIMENT.**

The per-test method sets are deterministic for the runnable subset, and no ASM false negative was found in
that subset. However, the current Round-4 executor call-site transformer emits invalid bytecode for Spring's
`AsyncTaskExecutor`. That prevents 42 of the 69 otherwise executable PetClinic tests from running. Four more
Docker-backed tests cannot execute in this environment. A 27-test comparison is not representative enough to
authorize a Spring Core experiment, and instrumentation safety is not clean.

No STP selection code, JaCoCo mapping semantics, or production JaCoCo map format was changed.

## Revision, environment, and inventory

The experiment used PetClinic commit `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`. The no-agent discovery run
used Maven Surefire's alphabetical order in a fresh forked JVM. It reported 71 tests: 69 executed successfully
and two MySQL tests were skipped because Docker was unavailable. The PostgreSQL class's `@BeforeAll`
assumption aborts before testcases are created, so its two declared tests appear as an empty suite rather than
Surefire skipped testcases. Those four database-container tests are the only environment exclusions; they are
unrelated to ASM. The other 69 tests cover validators, formatting, domain objects, repositories, MVC slices,
full application contexts, embedded HTTP, error handling, and concurrency and would be a representative
PetClinic inventory.

The current collector cannot run that inventory. A complete ASM attempt fails, and isolated class probes
reproduce the same failure in ten test classes. The affected 42 test identities are listed individually in
`test-inventory.json`; the affected classes are:

* `PetClinicConcurrencyTests`
* `PetClinicIntegrationTests`
* `OwnerControllerTests`
* `PetControllerTests` and both nested error-case classes
* `VisitControllerTests`
* `CrashControllerIntegrationTests`
* `WelcomeControllerTests`
* `VetControllerTests`

The remaining mapping inventory contains 27 tests in `ValidatorTests`, `PetTypeFormatterTests`,
`PetValidatorTests` (including its nested class), `ClinicServiceTests`, `CrashControllerTests`,
`I18nPropertiesSyncTest`, and `VetTests`. It exercises real application and JPA/domain behavior, but omits the
web and full-context surface that dominates the excluded set. It is therefore useful diagnostic evidence, not
a representative full-inventory validation.

## Instrumentation-safety failure

The failing class is `org.springframework.core.task.AsyncTaskExecutor`. Its default method calls the overload
`execute(Runnable,long)`. `ExecutorCallSiteTransformer.executorShape` and `wrapping` use descriptor
`startsWith("(Ljava/lang/Runnable;")` rather than accepting only the declared exact Round-4 descriptors. The
transformer consequently inserts `RuntimeHooks.wrap(Runnable)` immediately before a call whose top stack value
is the primitive `long`, not the `Runnable`. Verification fails with:

```text
java.lang.VerifyError: Bad type on operand stack
Location: org/springframework/core/task/AsyncTaskExecutor.submit(Callable)Future @14: invokestatic
stack: AsyncTaskExecutor, FutureTask, long, long_2nd
Reason: long_2nd is not assignable to java/lang/Runnable
```

This is an ASM/executor-transformer defect, not a PetClinic, Java, Docker, JaCoCo, or test-order failure.
Round 5 deliberately does not repair it because further concurrency work is out of scope. The compact raw shutdown
output remains as `asm-full-inventory-failure-raw.json`. The redundant full inventory log is intentionally excluded;
its SHA-256 and reproduction instructions are in `docs/removed-research-artifacts.md`.

## ASM runs and determinism

The 27-test subset ran three times in alphabetical order and once with Surefire
`reversealphabetical`, each in a fresh JVM. Every run produced the requested sorted
`binaryClassName#methodName(JVM descriptor)` sets.

| Comparison with fixed run 1 | Total tests | Identical sets | Changed sets | Missing edges | Extra edges | Global-union difference |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| fixed run 2 | 27 | 27 | 0 | 0 | 0 | 0 |
| fixed run 3 | 27 | 27 | 0 | 0 | 0 | 0 |
| reverse run | 27 | 27 | 0 | 0 | 0 | 0 |

There are no changed method edges to classify. Global `methodHits` also contains the same 47 unique method
facts and 584 raw hits in every run.

`NO_ACTIVE_TEST` is zero in all four runs. Each run has 19 `LATE_EVENT` hits: 18 constructors associated with
Spring/JPA context setup and one `CrashController` constructor. Fixed runs attach the 18 setup events to
`PetValidatorTests$ValidateHasErrors#validateWithInvalidBirthDate` and the crash constructor to
`ClinicServiceTests#shouldAllowSamePetNameForDifferentOwners`. The reverse run attaches the 18 setup events to
`CrashControllerTests#triggerException` and the crash constructor to
`I18nPropertiesSyncTest#checkI18nPropertyFilesAreInSync`. This is an explicitly retained **test-order effect**
in late-event ownership; none enters a test's method set.

All runs report zero `executor-attribution-incomplete` diagnostics and the same four run-level
`executor-attribution-unsupported` diagnostics, for explicit-executor `CompletableFuture.runAsync` call sites
in `MVStore`, `DefaultListableBeanFactory`, `DefaultLifecycleProcessor`, and `AsyncTaskExecutor`. The current
diagnostic schema identifies transformed caller classes, not the test or enclosing caller method, and does not
prove that a call site executed. It therefore cannot safely localize affected tests or methods. Conservatively,
all 27 tests in the shared JVM are classified attribution-incomplete. This evidence was not normalized away.

## Existing JaCoCo STP baseline and comparison

The unchanged `JacocoPerTestListener`, `ExecToXmlEngine`, `CoverageMapperJaxb`, and Maven goals generated the
original production-format map for the same 27 tests. `jacoco-stp-map-original.json` is retained unchanged.
Descriptors were extracted separately from the per-test XML into `jacoco-descriptor-map.json`; this comparison
view did not alter the production map or collapse overloads.

Across all per-test edges, 239 are common, zero are ASM-only, and six are JaCoCo-only. Twenty-five tests are
exact matches. Mean per-test Jaccard similarity is 0.969907; the minimum is 0.5. The complete 27-row values are
in `comparison-report.json`.

| Test | Common | ASM-only | JaCoCo-only | Jaccard |
| --- | ---: | ---: | ---: | ---: |
| `ClinicServiceTests#shouldFindVets` | 11 | 0 | 5 | 0.6875 |
| `CrashControllerTests#triggerException` | 1 | 0 | 1 | 0.5 |
| each of the other 25 tests | exact | 0 | 0 | 1.0 |

The six changed edges, with no unknowns hidden, are:

* `ClinicServiceTests#shouldFindVets` → `PetClinicApplication#<init>()V`, `Owner#<init>()V`,
  `Pet#<init>()V`, `PetType#<init>()V`, and `Visit#<init>()V`: **JaCoCo attribution artifact**;
  secondary category **constructor**. ASM saw each globally as `LATE_EVENT` after the preceding test. JaCoCo's
  reset-at-finish session behavior carries between-test probes into the next completed test session.
* `CrashControllerTests#triggerException` → `CrashController#<init>()V`: **JaCoCo attribution artifact**;
  secondary category **constructor**, for the same reset boundary.

The changed-edge class set is `PetClinicApplication`, `Owner`, `Pet`, `PetType`, `Visit`, and
`CrashController`; the changed-method set is the six zero-argument constructors. At global-union level, 45
methods are common, zero are ASM-only, and two are JaCoCo-only: `PetClinicApplication#<init>()V` and
`CrashController#<init>()V`. The other four per-test-only differences execute in correctly attributed ASM test
buckets elsewhere in the suite.

No interface body, static initializer, synthetic/bridge/compiler-generated method, overload-collapse case,
worker-attributed edge, genuine execution difference, ASM false negative, or unknown comparison edge occurred
in the runnable subset.

## Safety analysis

All six JaCoCo-only per-test application methods are present in ASM global `methodHits`. Each executed outside
an active test and is retained as `LATE_EVENT`; none disappeared at instrumentation or recording time. Although
constructors can be RTS-relevant, assigning these context-transition executions to the following test would be
incorrect. Their absence from the ASM test bucket is therefore expected, and the JaCoCo edge is the artifact.
There is no unexplained RTS-relevant ASM false negative in the 27-test subset.

There are no ASM-only edges, so this run supplies no candidate instrumentation-noise edge requiring further
validation. Zero transformation errors and zero method-ID collisions were recorded in successful runs. Those
clean counters do not mitigate the independently reproduced verifier failure on the broader inventory.

## Performance sanity check

This is one cold comparison, not a benchmark. The same 27 tests took 3.191008 seconds without an agent. The
three alphabetical ASM runs took 3.966658, 3.751539, and 4.055733 seconds (median 3.966658), approximately
24.3% over the single no-agent observation. The reverse run took 3.597887 seconds. Startup/context variance is
large relative to the instrumentation work.

Fixed run 1 considered 14,073 loaded classes: 22 matched the include decision, 12,007 were excluded, and 2,044
were ignored. Fifteen classes were transformed; 64 of 69 considered methods were instrumented and five were
skipped. It recorded 584 raw hits and 47 unique method facts. Transformer time was 25.366 ms and runtime
recording time was 8.872 ms. Raw agent output was 93,135 bytes; its normalized retained map/diagnostics file is
40,932 bytes. Counts for subsequent fixed runs were equivalent except normal JVM class-loading and timing
variation; exact values are retained in each normalized file and the run manifest.

## Reproduction and retained evidence

Run `stp-petclinic-spike/round5/reproduce.sh` with Java 17+ and the pinned PetClinic checkout at the default
`/private/tmp/stp-round2-petclinic`, or set `PETCLINIC_DIR`. It builds the current artifacts, runs inventory and
compatibility probes, performs the fresh-JVM matrix, invokes the existing JaCoCo STP Maven pipeline, and
normalizes the evidence.

Retained under `stp-petclinic-spike/round5/evidence/`:

* deterministic inventory and ASM compatibility probes;
* the full-inventory verifier failure and raw agent shutdown evidence;
* four raw and four normalized ASM maps with metrics and diagnostics;
* the original JaCoCo STP map and descriptor-only comparison map;
* comparison report, fully classified diff details, run manifest, and reproduction logs.
