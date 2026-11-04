# MAVEN-ASM-10 — local Maven Spring PetClinic characterization

## Result

**YES: the prepared Maven ASM path produces a complete, correctly accounted schema-v3 map for the pinned PetClinic H2 test scope.** All 69 assigned executable identities pass and are mapped; there are no executable-unmapped or positively non-executed identities. Production codecs, aggregation and the production joiner accept the exact assignment partition. This is a local candidate map, not a Jenkins publication or a selection test.

**The collectors are not equivalent.** ASM has 1,343 per-test method edges; JaCoCo has 1,400. Their symmetric difference is 91 edges: 17 ASM-only generated-bridge edges explained below, and 74 JaCoCo-only attribution differences retained as **unexplained at exact method-edge level**. No filter, scope adjustment or heuristic was added to make these maps agree. The earlier trivial reactor fixture's equality is not a baseline expectation here.

**Project JaCoCo coexistence passes:** the STP-free baseline and ASM coexistence run have exactly equal JaCoCo counters for all 25 classes and 113 descriptor-qualified methods (138 entries), with zero differences. This is evidence for this revision, toolchain and prepared agent configuration, not arbitrary agent ordering or universal project compatibility.

## Provenance and scope

- Measurement date: 2026-11-04.
- STP branch: `research/asm-codex`; source: `d9cb928d5a07f6ea2cf152381b6b55513113cf09` (MAVEN-ASM-09).
- All STP components: `0.3.0-SNAPSHOT`. No production source, dependencies or build configuration changed in this task. [Measured artifact checksums](raw/stp-artifact-checksums.txt).
- Subject: [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic), commit `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, fetched from upstream by that exact SHA. The subject POM reports `4.0.0-SNAPSHOT`; this is distinct from its resolved Spring Boot version.
- Separate disposable clones for ASM, JaCoCo, STP-free baseline and coexistence. Original `pom.xml` and all subject source files remain unchanged; each run retains `upstream-source-diff.txt`. All **57 production/test class file hashes match** the baseline: [comparison](raw/compiled-class-comparison.json), full manifests under each run's `collection-files.json`.
- Maven 3.9.15; SapMachine JDK 21.0.13.0.3; macOS arm64; Gradle wrapper 8.14 for STP builds/regressions. [Tool versions](raw/tool-versions.log).
- Actually resolved: Spring Boot **4.1.0**, Spring Data Commons/JPA **4.1.0**, Jupiter/Platform **6.0.3**, Surefire **3.5.6**. [Dependency tree](raw/asm/dependencies.txt), [effective POM](raw/asm/effective-pom.xml), and [fork reports](raw/asm/surefire/). STP's plugin realm has its own JUnit dependency version; it is not the subject runtime version.
- Project JaCoCo report/agent: **0.8.15**. STP per-test XML analysis: **0.8.13**, as pinned by the existing common component. This difference is recorded, not hidden or changed to improve agreement.

The measured scope is the 69 H2/default tests, including nested tests. Only `MySqlIntegrationTests` and `PostgresIntegrationTests` are excluded (four methods requiring external database containers). Those exclusions apply identically to inventory and all four executions. The subject does not exercise parameterized/repeated/dynamic tests or an ArchUnit-style custom Vintage runner in this scope. There is no S7 workaround or new claim about S7.

An untracked, generated `measurement-pom.xml` adds build-only profiles; it does not edit upstream files. `measurement` fixes one reused Surefire fork, alphabetical order, `@{argLine} -Xmx768m`, and the two exclusions. `stp-measurement` adds the documented core test dependency and binds existing `generate-reports` then `generate-coverage-fragment` goals at verify. It is inactive for the STP-free baseline. The baseline dependency tree and Surefire classpath contain no STP runtime; the baseline command invokes no STP goal. Checkstyle and formatting checks are skipped consistently, not tests.

## Execution and reproducibility

[run.sh](run.sh) is the actual runner, not illustrative Pipeline code. [Measure.java](Measure.java) performs measurement, assignment construction and assertions through production inventory/assignment/fragment/map codecs, `MavenMappingEvidence`, and `ExecutableCoverageFragmentJoiner`. [analyze.sh](analyze.sh) repeats the comparisons. No Jenkins plugin is involved.

The run used a new task-local Maven repository (`ASM10_WORK/m2`), not a developer's Maven cache. STP resolves through the existing `build/functional-test-maven-repository`, exposed by [settings.xml](settings.xml); third-party artifacts resolve through Maven Central. The same task-local repository is reused across otherwise fresh subject clones. The shaded agent is resolved by Maven preparation at the aligned version, not attached manually or copied into the subject. The measurement helper's Gson JAR is an explicitly supplied harness dependency, not a hidden project dependency.

From the STP repository, with Maven/JDK on PATH and network access to upstream/Central:

```bash
./gradlew :smart-test-picker-common:publishAllPublicationsToFunctionalTestRepository \
  :smart-test-picker-core:publishAllPublicationsToFunctionalTestRepository \
  :smart-test-picker-maven:publishAllPublicationsToFunctionalTestRepository \
  :stp-agent:publishAllPublicationsToFunctionalTestRepository

export ASM10_WORK="$(mktemp -d /tmp/stp-asm10.XXXXXX)"
git clone https://github.com/spring-projects/spring-petclinic.git "$ASM10_WORK/subject"
git -C "$ASM10_WORK/subject" fetch origin 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
git -C "$ASM10_WORK/subject" checkout --detach 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
# Set this explicitly to the Gson 2.10.1 JAR used by the measurement helper.
export ASM10_GSON=/absolute/path/to/gson-2.10.1.jar
for mode in asm jacoco baseline coexist; do
  bash verification/maven-asm-10/run.sh "$mode"
done
```

Use a disposable checkout/evidence destination when reproducing; the runner writes mode-specific raw evidence. It rejects an already existing subject copy rather than reusing its outputs. This measurement was performed under `/private/tmp/stp-asm10.VvlrmI`, and retained commands record those historical paths.

For mapping, the runner executes:

1. `test-compile -DskipTests -Djacoco.skip=true` to produce exact-head classes.
2. `generate-reactor-head-test-inventory`, schema 3, exact subject SHA, explicit output.
3. Assignment of the entire discovered runnable inventory through the production assignment codec.
4. `prepare-reactor-executable-mapping`, collector ASM or JACOCO, schema 3, exact revision, shard `local`, inventory and assignment.
5. `verify`: real Surefire execution, project report where enabled, STP report/fragment finalization.
6. `aggregate-reactor-coverage-fragment`, followed by codec/accounting assertions and a pure local join to a CANDIDATE map.

Exact expanded commands: [ASM](raw/asm/command.txt), [JaCoCo](raw/jacoco/command.txt), [baseline](raw/baseline/command.txt), [coexistence](raw/coexist/command.txt). There is no explicit `asm.includes`, manual STP `-javaagent`, selected-test plan, or publication policy change. The ASM-only run disables the project's JaCoCo; coexistence leaves it enabled.

## 1. Real package scope

Preparation derives `org.springframework.samples.petclinic.` and its production subpackage prefixes. The diagnostic also lists mandatory `org.springframework.` exclusion. **The derived include wins**, as demonstrated by **23 transformed production classes**, 116 instrumented methods, zero transformation errors and zero method-ID collisions. Thirty classes matched the inclusion decision; that is not the transformed-class count.

[ASM diagnostic](raw/asm/module/asm-agent-diagnostic.json) records both prefixes, production code-source restrictions, 19,261 classes seen, and `requireInstrumentation=true`. The zero-instrumentation guard does not fire. Production sources are the subject's compiled classes and its own expected main JAR; test classes and third-party dependencies are not the instrumentation universe.

## 2. Exact accounting

| Run | Assignment | Mapped | Executable-unmapped | Positive non-execution | Actual passing tests |
| --- | ---: | ---: | ---: | ---: | ---: |
| ASM | 69 | 69 | 0 | 0 | 69 |
| JaCoCo | 69 | 69 | 0 | 0 | 69 |
| ASM + project JaCoCo | 69 | 69 | 0 | 0 | 69 |

Evidence v2, revision/shard binding, exact `EXECUTED == coverageOwners`, disjointness, exact assignment union, and module/aggregate agreement are asserted, not inferred from counts. Inventory equals assignment. The production joiner validates completeness. No missing identity is converted into positive non-execution by subtraction. There are no unmapped entries requiring reasons in the successful runs, and no disabled/aborted positive-non-execution case is exercised by this subject run; earlier regressions retain those checks.

Full identities and outcomes: [ASM accounting](raw/asm/accounting.json), [JaCoCo accounting](raw/jacoco/accounting.json), [coexistence accounting](raw/coexist/accounting.json). Raw assignments, fragments and evidence are in each run's `handoff/` and `module/`. Surefire identity/outcome equality against the baseline is separately asserted for [ASM](raw/asm-results-comparison.json), [JaCoCo](raw/jacoco-results-comparison.json), and [coexistence](raw/coexist-results-comparison.json), with no duplicate report identities.

ASM has six `PASS/COLLECTED_EMPTY` tests: `PetClinicIntegrationTests#ownerDetails`, `#ownerList`; `CrashControllerIntegrationTests#triggerExceptionHtml`, `#triggerExceptionJson`; and the two `I18nPropertiesSyncTest` methods. JaCoCo has only the two I18n empties. Empty coverage is not failed execution, but the four differing HTTP cases are not declared semantically explained merely because accounting passes.

## 3. Divergence characterization

The [complete edge ledger](raw/divergence.json) names **every divergent edge individually**, including the full executable test identity, descriptor-qualified method or class, collector side and classification. It contains 91 method edges and 20 class edges; no sampling or truncated unexplained list.

| Production class (under the PetClinic package) | Explained ASM-only bridge edges | Unexplained JaCoCo-only method edges |
| --- | ---: | ---: |
| `model.BaseEntity` | 0 | 6 |
| `model.NamedEntity` | 0 | 8 |
| `model.Person` | 0 | 9 |
| `owner.Owner` | 0 | 14 |
| `owner.OwnerController` | 0 | 11 |
| `owner.Pet` | 0 | 10 |
| `owner.PetController` | 0 | 7 |
| `owner.PetType` | 0 | 2 |
| `owner.PetTypeFormatter` | 17 | 2 |
| `owner.PetValidator` | 0 | 3 |
| `system.CrashController` | 0 | 2 |
| **Total** | **17** | **74** |

Assessment against the requested mechanisms:

| Mechanism | Exact method-edge assignments supported here | Interpretation |
| --- | ---: | --- |
| Spring proxies/generated infrastructure without application method bodies | 0 | `VetRepository.findAll` is abstract, so no body edge is expected. Its absence is not itself a divergent edge. This run's `PetClinicIntegrationTests#findAll` does have entity-constructor edges; it is not empty. |
| Lifecycle/setup attribution boundary | 0 conclusively assigned | Plausible for the HTTP cases; setup class overlap is not proof of causal attribution for a particular method edge. |
| JaCoCo reset-boundary over-attribution | 0 conclusively assigned | No same-run reset-boundary oracle was collected. First-test ordering alone cannot establish over-attribution. |
| ASM late-event quarantine | 0 | This ASM diagnostic has no unattributed events, including no recorded LATE_EVENT. Historical measurements are not substituted for this run. |
| Unexplained | 74 | All retained individually, with no claimed causal resolution. |
| Additional finding: generated synthetic bridge filtering | 17 | ASM records two real compiler-generated method bodies that JaCoCo filters from its report. |

The additional finding concerns `PetTypeFormatter#parse(String,Locale):Object` (14 test edges) and `#print(Object,Locale):String` (3). `javap` shows `ACC_BRIDGE, ACC_SYNTHETIC` and delegating bodies; JaCoCo 0.8.13's `SyntheticFilter` excludes these non-lambda synthetic methods. Evidence: [subject bytecode](raw/formatter-and-repository-bytecode.txt), [filter bytecode](raw/jacoco-synthetic-filter-bytecode.txt), [example per-test XML](raw/bridge-example-per-test-jacoco.xml), and both maps. These are not Spring proxies without method bodies; they are recorded as a separate finding, not forced into that category. No production change follows from it.

The 74 unexplained method edges belong to exactly five tests:

- `PetClinicConcurrencyTests#testDuplicatePetNameRaceConditionIsBlocked`: 30.
- `PetClinicIntegrationTests#ownerDetails`: 21.
- `PetClinicIntegrationTests#ownerList`: 21.
- `CrashControllerIntegrationTests#triggerExceptionHtml`: 1.
- `CrashControllerIntegrationTests#triggerExceptionJson`: 1.

These tests issue HTTP requests to the running application. Runtime container/setup context and worker-thread attribution are relevant hypotheses, not demonstrated explanations for each edge. Both maps retain ten setup scopes, with their existing different kinds (`CONTAINER` versus `FRAMEWORK_SETUP`); those class-level scopes are not recast as leaf method evidence. All 20 divergent class edges are JaCoCo-only on these HTTP tests and remain unexplained too.

Globally ASM covers 89 distinct methods and JaCoCo 87; **there is no JaCoCo-covered method globally absent from ASM**. The only global ASM-only methods are the two bridges. Thus this measurement does not demonstrate global loss of those 74 methods; it does demonstrate different per-test attribution. It does not prove every attribution is correct or validate downstream selection.

This framing is consistent with [TASK 21](../../docs/task21-full-spring-core-asm-vs-evaluation.md) (25,283 differing edges, redistribution dominant) and [TASK 20](../../docs/task20-jacoco-fn-confirmation.md) (filter-sensitive bytecode). Those historical results remain separate experiments, not causal proof for this run.

## 4. Observed cost

| Run | Mapping/verify wall seconds | OS maximum RSS bytes | Candidate map bytes | Class / method edges |
| --- | ---: | ---: | ---: | --- |
| ASM only | 37.31 | 677,494,784 | 186,899 | 418 / 1,343 |
| STP JaCoCo | 27.86 | 579,846,144 | 193,525 | 438 / 1,400 |
| STP-free project JaCoCo baseline | 25.25 | 536,952,832 | n/a | n/a |
| ASM + project JaCoCo | 37.11 | 726,646,784 | 186,899 | 418 / 1,343 |

[Machine-readable costs](raw/costs.json); raw macOS `/usr/bin/time -l` outputs are `raw/<mode>/time.txt`. RSS is the operating system's maximum-resident-set statistic for the timed command, **not** measured Java heap or summed concurrent Maven/fork memory. Maven and the test fork each have a 768 MiB heap ceiling. The timed region includes preparation, verify/package/report/finalization and aggregation; initial compilation, inventory and the helper's local join are outside it. Baseline has no STP preparation or aggregation.

These are **single-run observations, not a comparative benchmark or speedup claim**. The first ASM run downloaded additional build/plugin dependencies, later runs reused the task cache, and the STP regression suites ran concurrently. Agent transformer time is diagnostic data, not a substitute for end-to-end cost.

STP JaCoCo generated 77 nonempty per-test/setup XML files, totaling **1,637,163 bytes**, and 87 mapping `.exec` files totaling **7,245,254 bytes** (excluding the separate project `target/jacoco.exec`). Ten report inputs had no coverage and were skipped; zero report-generation failures. Status/placeholder files are separately listed in [the file manifest](raw/jacoco/collection-files.json). The log reports 319 ms for XML generation. ASM generated no per-test JaCoCo XML or execution files; its diagnostic and fragment are retained as JSON. This small-project result does not establish large-project memory or volume scaling.

## 5. Project-owned JaCoCo report preservation

[Counter comparison](raw/project-counter-comparison.json) retains every class/method's exact missed and covered counters, including method descriptors and all present counter types. Missing/extra methods would fail equality. Baseline and coexistence both use project JaCoCo 0.8.15 and the same 69 test identities/outcomes; all compiled class bytes match. Result: **25 class entries + 113 method entries, zero differences**. The compared raw XML files are [baseline](raw/baseline/jacoco.xml) and [coexistence](raw/coexist/jacoco.xml).

The ASM-only and coexistence STP maps are also byte-identical in these two runs. That is a within-ASM coexistence observation, **not ASM/JaCoCo collector equivalence**. No inverted-order experiment, new reset/dump tracing, Gradle coexistence proof or arbitrary third-party-agent compatibility claim is made here; those are not this task's P1/P2 comparison.

## Attempts and regression validation

The first JaCoCo attempt passed all 69 tests but failed aggregation: the measurement overlay omitted the existing `generate-reports` goal, leaving no per-test XML status. Finalization produced 69 `COLLECTION_FAILED` unmapped entries and an incomplete fragment; aggregation rejected it. [Initial console](jacoco-initial-console.log) and [raw attempt](raw/jacoco-initial/) are preserved. The correction was only to the measurement profile, binding `generate-reports` before fragment generation, followed by a fresh subject clone. No production fix or relaxed validation was needed. The successful initial ASM overlay did not need that JaCoCo-only report step; the final runner includes it (a no-op for ASM), as also exercised by coexistence.

Full rerun, no cached test tasks:

```bash
./gradlew --no-daemon :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-agent:test \
  --rerun-tasks --console=plain
bash verification/maven-asm-10/analyze.sh
```

[Full console](full-suites.log): `BUILD SUCCESSFUL in 13m 47s`, 57 tasks executed. [XML-derived counts](raw/suite-counts.json):

| Suite | Reported tests | Skipped | Failures / errors |
| --- | ---: | ---: | --- |
| common | 333 | 11 | 0 / 0 |
| core | 29 | 5 | 0 / 0 |
| Maven | 143 | 0 | 0 / 0 |
| Gradle plugin (`smart-test-picker`) | 46 | 0 | 0 / 0 |
| agent | 45 | 1 | 0 / 0 |

All requested count baselines are retained. Skipped fixture cases are explicitly reported rather than described as executed passes. The production JaCoCo missing-runtime, ASM fork/scope guards and existing mapping regressions were not edited. No Gradle behavior changed.

Shell syntax, helper compilation and changed-document relative links pass. Every new evidence file is below 5 MiB. The repository-wide `scripts/check-large-files.sh` still fails on two **pre-existing tracked** files: `EI-10/artifacts/published-r0-map.json` (30,045,626 bytes) and `EI-11/artifacts/deployed-smart-test-picker.hpi` (10,450,073 bytes). Neither was changed or exempted by this task. This is a repository hygiene finding, not a PetClinic runtime failure.

## Boundaries and remaining findings

- Complete accounting and project-report coexistence: demonstrated for this exact local Maven/H2 scope.
- 74 method and 20 class attribution differences: individually recorded, causally unresolved; not tuned away, and not claimed to be collector defects or proven over-attribution.
- Generated bridge filtering: an additional characterized collector difference, not a fix request.
- No MySQL/PostgreSQL-container scope, S7 fix, distributed shards, Jenkins, AE, publication, selection or per-fork merging was attempted.
- No external blocker prevented this task. The unexplained edge classification is an explicit measurement result, not an end-to-end equivalence claim.
