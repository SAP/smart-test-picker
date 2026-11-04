# STP-CAPABILITIES-01

> Historical verification at `bda91d3`. Its results and raw artifacts remain unchanged.
> [STP-GRADLE-SETUP-01](../stp-gradle-setup-01/REPORT.md) subsequently fixes the Gradle
> JaCoCo setup-scope loss and updates that capability to true.

Initial source: `research/asm-codex`, `0aec5c79b36a257bece3880d314979fd66c607f0`.

## Step 0 — consumers (before changes)

Repository-wide searches for `capabilities()` and all five component accessors found no
production reader. The four implementations only return metadata; existing tests compare
it to literal tuples. Coverage fragment/map `setupScopes()` readers are not capability
readers. A correction is metadata drift, not a routing or execution behavior change.

The production search finds only the interface declaration in
`smart-test-picker-common/.../CoverageCollectorBackend.java`, the two Gradle implementations
(`AsmCoverageCollectorBackend`, `JacocoCoverageCollectorBackend`), and the Maven enum's
accessor (`MavenCoverageCollectorBackend`, holding two declarations). There are **zero
production call sites**. Maven preparation/finalization selects the backend type, not a
capability flag. All actual invocations of `capabilities()` in this repository are tests.

## Observation rules (recorded before the runs)

Use the existing overload, implicit-exception and setup-only fixture shapes on both build
tools. A successful leaf test proves entry to `ImplicitException.length` by its exception
stack frame; the method edge decides entry fidelity. A called String overload and uncalled
int overload decide descriptor preservation. `SetupOnly` is called only in `BeforeAll`;
the final fragment's owned scope decides setup support, not the existence of raw sessions.
Inspect outputs immediately after collection, before conversion, to decide direct schema 2.
The tests must validate completed collection and the executed probe identity before comparing
the observed flags to the declaration. These bounded probes are not a claim of universal
attribution soundness or coverage of every legacy output format.

## Definitions

The record components now have individual Javadoc definitions:

| Flag | Deciding observation |
| --- | --- |
| `classCoverage` | A passing leaf's fragment contains the exercised production class. |
| `exactMethodCoverage` | The leaf contains the entered production method even when an implicit exception prevents its exit probe. This is method-entry fidelity, **not** exact temporal attribution. |
| `setupScopes` | The fragment retains setup-only production coverage with explicit container ownership. A raw `.setup`/`.exec` session alone is insufficient. |
| `descriptorAwareMethods` | Emitted method identities preserve JVM parameter **and return** descriptors and distinguish called and uncalled overloads. |
| `directSchemaV2Fragment` | A supported collection-only invocation writes a schema-v2 fragment **before** report conversion or fragment assembly. An eventual schema-v2 result does not qualify. |

These describe capabilities available in supported backend paths, not every legacy format.
For example, Gradle JaCoCo's executable schema-v3 converter preserves descriptors; its older
unversioned map is not being advertised as descriptor-aware. Maven's prepared ASM path emits
schema **3**, not schema 2. None of these flags certifies attribution soundness, parallel safety,
shared-context support, or a collector's equivalence to another collector.

## Evidence and 20-cell matrix

The probes reuse the existing overload shape from `GradleCollectorBackendFunctionalTest`,
the implicit-exception shape from `asm-jacoco-coexistence`, and the setup-only shape from
the executable-routing fixtures. Their shared source is under
[`capability-probes`](../../smart-test-picker-maven/src/test/fixtures/capability-probes/).
Maven copies `schema-v3-reactor` and adds these probes only to the disposable module-b copy;
Gradle extends its existing temporary Java fixture. No external subject campaign was run.

Artifact keys (all paths are relative to this report):

* **GA**: [Gradle ASM fragment](raw/gradle/asm/fragment.json), schema 2.
* **GJ**: [Gradle JaCoCo fragment](raw/gradle/jacoco/fragment.json), schema 3.
* **MA**: [Maven ASM fragment](raw/maven/asm/finalized/coverage-fragment-v3.json), schema 3.
* **MJ**: [Maven JaCoCo fragment](raw/maven/jacoco/finalized/coverage-fragment-v3.json), schema 3.
* **GA-raw**: [collector-written schema-2 fragment](raw/gradle/asm/collected/_generateSmartTestCoverage/fixture-capabilities/fragment.json), from collection-only `generateSmartTestCoverage`; [task log](raw/gradle/asm/gradle.log).
* **GJ-raw**: [collection snapshot](raw/gradle/jacoco/collected/) taken by `test.doLast`, before the dependent converter; [task log](raw/gradle/jacoco/gradle.log). It contains exec/identity/setup files, no fragment.
* **MA-raw**: [collector-written schema-3 fragment](raw/maven/asm/collected/stp/coverage-fragment-v3.json) and [diagnostic](raw/maven/asm/collected/stp/asm-agent-diagnostic.json), captured after `test`, before any finalization; [collection log](raw/maven/asm/collect.log).
* **MJ-raw**: [collection snapshot](raw/maven/jacoco/collected/) with exec/identity/setup files and no fragment, before the separate [report/finalization invocation](raw/maven/jacoco/finalize.log).

Each cell is **initial declaration → observed value**, followed by its deciding artifact/field.
`T` = true; `F` = false. All 20 cells are decided for the defined output properties on these
controlled fixtures; broader topology qualification is explicitly not inferred.

| Backend | classCoverage | exactMethodCoverage | setupScopes | descriptorAwareMethods | directSchemaV2Fragment |
| --- | --- | --- | --- | --- | --- |
| Gradle ASM | T → T, GA `classes` | T → T, GA `methods` | T → T, GA `setupScopes` | T → T, GA `methods` | T → T, GA-raw |
| Gradle JaCoCo | T → T, GJ `classes` | F → F, GJ `methods` | F → F, GJ `setupScopes` + setup witness below | **F → T**, GJ `methods` | F → F, GJ-raw |
| Maven ASM | T → T, MA `classes` | T → T, MA `methods` | T → T, MA `setupScopes` | T → T, MA `methods` | F → F, MA-raw (schema 3) |
| Maven JaCoCo | T → T, MJ `classes` | F → F, MJ `methods` | T → T, MJ `setupScopes` | T → T, MJ `methods` | F → F, MJ-raw |

The leaf is `example.CapabilitiesTest#probes`, qualified as
`gradle::test::example.CapabilitiesTest#probes` or
`maven:module-b::example.CapabilitiesTest#probes` for executable fragments.
All four runs produce one mapped PASS, zero unmapped identities, and completed collection.
The test asserts the caught exception's first frame is `example.ImplicitException.length`,
so missing coverage cannot be explained by the call not occurring. JUnit XML is retained in
each backend's `test-results/` directory.

Exact edge observations:

* All four leaf fragments contain class `example.OverloadedService` and
  `example.OverloadedService#value(Ljava/lang/String;)Ljava/lang/String;`.
* None contains the uncalled overload `example.OverloadedService#value(I)I`.
* Both ASM fragments contain `example.ImplicitException#length(Ljava/lang/String;)I`.
  Neither JaCoCo fragment contains that method, although its constructor is covered and
  the passing probe proves entry and the implicit exception.
* `example.SetupOnly` appears in neither leaf. Gradle ASM and Maven ASM retain it in a
  `CONTAINER` scope; Maven JaCoCo retains it in `FRAMEWORK_SETUP`. The affected container
  is `example.CapabilitiesTest`; executable scopes retain `maven:module-b` ownership.
* Gradle JaCoCo's final `setupScopes` is empty **despite positive collection**:
  [`session_setup_5092d58c.setup`](raw/gradle/jacoco/collected/jacoco/_test/fixture-capabilities/exec/session_setup_5092d58c.setup)
  names that container, and the
  [setup XML](raw/gradle/jacoco/converted/_test/fixture-capabilities/reports/session_setup_5092d58c.xml)
  reports `SetupOnly.setupOnly()I` with METHOD covered=1. This is a conversion gap, not
  a fixture with no setup or a different listener.

## The three asymmetries

1. **Setup scopes: real backend difference.** The Gradle executable converter discards
   the positive setup sessions; Maven converts them into owned `FRAMEWORK_SETUP` scopes.
   Keep Gradle JaCoCo false and Maven JaCoCo true. No setup implementation was changed.
2. **Descriptors: stale declaration.** Correct Gradle JaCoCo `descriptorAwareMethods`
   from false to true. JVM descriptors are present in both modern JaCoCo fragments.
   `exactMethodCoverage` remains false because the independently proven entered method
   is absent. No routing/selection/execution decision reads the corrected flag.
3. **Direct schema 2: real backend difference.** Gradle ASM's normal logical path
   actually runs the agent at schema 2; it is not an unexercised declaration. Maven's
   prepared ASM path runs it at schema 3. Both JaCoCo paths first produce raw sessions,
   then require conversion/assembly to obtain their fragments.

The affected README statements were corrected: Gradle now distinguishes its legacy map
from descriptor-aware executable fragments, and Maven no longer incorrectly claims it
emits no setup scopes. The record documents all five properties. No collector, converter,
schema, selection, setup-attribution, or execution behavior was changed.

## Permanent regressions

* `GradleCollectorBackendFunctionalTest.capabilitiesMatchCollectedOutput`: two real
  Gradle TestKit invocations, ASM and JaCoCo; actual fragments decoded by the production
  codecs, positive setup XML witness, collection-before-conversion snapshot.
* `CollectorCapabilitiesIntegrationTest.capabilitiesMatchCollectedOutput`: two real
  Maven prepared mappings, ASM and JaCoCo; separate collection and finalization commands,
  production fragment decoding and retained runtime artifacts.

Each test computes a `CollectorCapabilities` from the observed classes, methods, setup
ownership and collection-stage artifacts, then compares it with the backend declaration.
They do not compare to a copied literal tuple. The former tuple-only assertions were
replaced by collector-type checks; the four new functional cases own the capability checks.

The [initial test XML](raw/initial/gradle-tests.xml) retains the failing descriptor
assertion against the original declaration. That run also caught a **test observer bug**:
the artifact scanner tried to parse diagnostic `schemaVersion="agent-shell-1"` as an integer.
The observer now recognizes fragment documents separately and compares the schema string;
this was not a product defect. [Initial console](focused-initial.log) and
[successful focused rerun](focused.log) are preserved. The focused rerun passed all four
new functional cases in 1m 18s.
The first full-suite attempt was deliberately interrupted (exit 130), not reported as a
pass: review found the Maven observer scanned retained evidence instead of its fresh
temporary project's output. It now scans live `target/stp`; earlier retained files cannot
supply the direct-fragment observation. The [interrupted console](full-suites-interrupted.log)
is preserved separately, and all seven suites were restarted with `--rerun-tasks`.

## Reproduction and full validation

Run from the repository root, with Maven 3.9.15 on PATH and SapMachine 21.0.13.0.3. The existing test harness
publishes STP artifacts to the repository's isolated functional-test Maven repository;
no developer-installed STP JAR is required. The wrapper uses Gradle 8.14; fixture Maven
Surefire is 3.5.3 and Jupiter is 5.9.3. STP remains `0.3.0-SNAPSHOT`.

```sh
./gradlew :smart-test-picker:test \
  --tests '*GradleCollectorBackendFunctionalTest.capabilitiesMatchCollectedOutput' \
  :smart-test-picker-maven:test --tests '*CollectorCapabilitiesIntegrationTest' \
  --continue --console=plain

./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-agent:test \
  :stp-runtime:test :stp-junit-adapter:test \
  --rerun-tasks --continue --console=plain

./gradlew :smart-test-picker-cli:test --rerun-tasks --console=plain
```

**PASS**: seven-module full rerun in **13m 55s** ([console](full-suites.log)); the remaining
CLI module passed its full fresh rerun in **13s** ([console](cli-suite.log)). All eight modules
in `settings.gradle` are covered. Counts are extracted from the current Gradle JUnit XML,
not copied from an earlier task; [per-suite counts](raw/suite-counts.json) retain every input
filename and its counters.

| Module | Tests | Skipped | Failures / errors |
| --- | ---: | ---: | ---: |
| smart-test-picker-common | 334 | 11 | 0 / 0 |
| smart-test-picker-core | 29 | 5 | 0 / 0 |
| smart-test-picker-maven | 151 | 0 | 0 / 0 |
| smart-test-picker (Gradle) | 48 | 0 | 0 / 0 |
| stp-agent | 45 | 1 | 0 / 0 |
| stp-runtime | 47 | 0 | 0 / 0 |
| stp-junit-adapter | 21 | 0 | 0 / 0 |
| smart-test-picker-cli | 36 | 0 | 0 / 0 |
| **Total** | **711** | **17** | **0 / 0** |

That is **694 executed successfully and 17 skipped**. Relative to ASM-16, Maven increases
149 → 151 and Gradle increases 46 → 48; the other seven-module counts are unchanged.
[Gradle functional-test XML](raw/gradle-functional-tests.xml) and
[Maven capability-test XML](raw/maven-capability-tests.xml) retain the new probes' stdout
and results from the full rerun. The backend artifacts above were refreshed from that run.

Relative links in this report, changed-source whitespace checks, and the repository's
tracked-file 5 MiB check passed. Each new evidence file is also below the limit.

## Scope and remaining limitations

The Gradle JaCoCo setup-conversion gap is explicitly recorded, not fixed. The existing
collector attribution, parallel/shared-context and legacy-format limitations remain.
The controlled probes decide these capability cells; they do not qualify all projects.
No Jenkins, distributed, AE, Spring subject, S7, fork-merge, or attribution work was done.
