# STP-GRADLE-SETUP-01

Initial revision: `bda91d35f4d957ab9d1f1c619752b26c8dce23b5`, branch `research/asm-codex`.

The retained capability fragments are decoded and joined
through production codecs and `ExecutableCoverageFragmentJoiner`, then passed through
`ExplicitPrSelectorFlow` (including revision preflight, Git change analysis and
`SchemaV2TestSelector`). Only revision binding is adapted to the disposable Git history;
coverage edges and setup ownership are unchanged. The sole diff is `SetupOnly.setupOnly`
returning 8 rather than 7. Historical evidence is not edited.

## Pre-fix result and decision

The real explicit selector returned `NONE`, selected `[]`, for Gradle JaCoCo, and
`SELECTED`, selected `[example.CapabilitiesTest#probes]`, for Maven JaCoCo. This is a
Gradle false negative: positive BeforeAll coverage fails to select its container's test.

Implement option (a) for executable schema 3: convert validated setup sessions into
target-owned `FRAMEWORK_SETUP` scopes, using the recorded container, as Maven does.
Missing, corrupt or unbound setup evidence must reject generation, not produce a thin map.

The older Gradle JaCoCo route configured with runtime schema 2 actually produces an
**unversioned legacy map**, not a logical schema-v2 fragment. Its XML mapper treats the
setup filename as a pseudo-test and never consumes container sidecars. That format has
no owned setup-scope representation. Apply option (b) at the Gradle legacy writer for
positive setup coverage, with a diagnostic directing callers to executable schema 3;
do not redesign the legacy format or alter the shared Maven/CLI XML engine.

## Drop site and selection mechanism

At the initial revision, `GenerateGradleJacocoExecutableFragmentTask.generate()` lines
61–62 read only `.identity` and `.non-executed` sidecars; line 96 supplies `List.of()`
as the fragment's setup scopes. Setup execution data is converted to XML, but `.setup`
ownership is **never read**. It is not filtered out by the collector.

`SchemaV2TestSelector` lines 50–59 match changed classes against setup scopes and add
the head tests in each affected container. The retained Maven fragment has that scope;
the retained Gradle fragment does not. No fallback rescues this Gradle case: it is a
valid-looking complete map and the selector returns `NONE`.

The legacy Gradle route goes through `GenerateTestCoverageJsonTask.generate()`,
`CoverageMapEngine.generate()` (line 75), and
`CoverageMapperJaxb.generateTestCoverageMapping()` (XML loop, lines 111–123).
It derives test names from XML filenames without reading setup ownership. Thus a setup
report becomes a pseudo-test such as `setup_5092d58c`, not an owned container scope.
This is not evidence that the logical schema-v2 contract lacks setup support: this
specific JaCoCo route writes the older, unversioned `metadata`/`testMappings` format.

## Implementation

- `GenerateGradleJacocoExecutableFragmentTask.setupScopes()` reads setup sidecars,
  execution files, conversion status and XML. Positive coverage becomes an owned
  `FRAMEWORK_SETUP` scope, using the recorded container and current Gradle task target.
  The existing Maven implementation at `GenerateCoverageFragmentMojo` lines 418–439
  is the precedent; it was not changed.
- Setup ownership must be format 1, unambiguous, and bound to the target assignment.
  The union of raw and converted setup artifacts detects orphan files rather than
  silently dropping them. Missing, corrupt, duplicate or unrelated-container evidence
  rejects generation. The previous fragment is deleted before conversion.
- `GenerateTestCoverageJsonTask` rejects positive setup coverage that its legacy map
  cannot represent. Empty setup coverage remains allowed; the old output is removed
  before validation. This is an intentional compatibility change for unsafe legacy
  mapping, not a new map schema.
- `JacocoCoverageCollectorBackend.capabilities()` now declares setup-scope support.
  The existing functional probe derives that value from the produced scope containing
  `example.SetupOnly`, not from a hardcoded expected capability tuple.

The repaired fragment contains this class-level coverage, not a fabricated leaf edge:

```text
type: FRAMEWORK_SETUP
owner: gradle::test
affected container: example.CapabilitiesTest
covered class: example.SetupOnly
```

For the identical setup-only source change, the newly generated Gradle map returns
`SELECTED`, `[example.CapabilitiesTest#probes]`. The test also asserts that the leaf
coverage itself does not contain `example.SetupOnly`; selection is saved by the scope.

## Permanent regressions and retained evidence

- `JacocoSetupSelectionTest`: two historical before-fix comparisons through the real
  codecs, joiner and explicit selector. The fixtures' revision fields alone are rebound
  to a disposable Git history to exercise real revision and diff handling.
- `GradleCollectorBackendFunctionalTest.capabilitiesMatchCollectedOutput`: current
  ASM and JaCoCo functional probes; JaCoCo additionally asserts scope ownership/type
  and runs the same selector against the freshly generated fragment.
- Seven real Gradle rejection cases: missing owner, missing execution data, malformed
  owner, unrelated container, corrupt execution data, duplicate container, and orphan
  converted report. Each plants an old fragment and asserts it cannot survive rejection.
- A real legacy Gradle run rejects positive setup coverage, removes an old map and
  reports the executable-schema-3 migration requirement.

Evidence is retained under `raw/`: before/after maps, exact source diffs and selector
results; negative-run logs; legacy rejection reports; fresh Gradle and Maven capability
outputs; JUnit XML results and suite counts. The older
[STP-CAPABILITIES-01 evidence](../stp-capabilities-01/REPORT.md) remains unchanged;
only a historical-status banner was added to that report.

## Reproduction

Environment: SapMachine JDK 21.0.13.0.3, Gradle wrapper 8.14, Maven 3.9.15 on `PATH`.
Run from the repository root:

```sh
./gradlew :smart-test-picker:test \
  --tests '*GradleCollectorBackendFunctionalTest' \
  --tests '*JacocoSetupSelectionTest' --console=plain

./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-agent:test \
  :stp-runtime:test :stp-junit-adapter:test :smart-test-picker-cli:test \
  --rerun-tasks --continue --console=plain
```

The full rerun includes the final orphan-report case, added after the initial focused
run. Console output is retained in `focused.log`, `selection-before.log`, and
`full-suites.log` in this directory.

## Boundaries

This repairs conversion of already collected setup coverage, not JaCoCo temporal
attribution. ASM, Maven production code, collector activation, selector policy,
parallel execution, shared Spring contexts, Jenkins, sharding, AE, S7 and fork merging
are unchanged. All runtime evidence here is local Gradle/Maven fixture execution;
there is no new Jenkins or real-application validation claim.

## Capability checks

All four `capabilitiesMatchCollectedOutput` cases still inspect actual collection and
finalization artifacts. They determine class edges, the implicit-exception method-entry
probe, positive setup coverage, exact overload descriptors, and whether the collection
stage itself emitted schema 2. Only the Gradle JaCoCo setup declaration changes:

| Backend | Class coverage | Exact method coverage | Setup scopes | Descriptor-aware methods | Direct schema-2 fragment |
|---|---|---|---|---|---|
| Gradle ASM | true | true | true | true | true |
| Gradle JaCoCo | true | false | **true** | true | false |
| Maven ASM | true | true | true | true | false |
| Maven JaCoCo | true | false | true | true | false |

The fixture-derived `observed.txt`, collected artifacts and finalized fragment for each
case are retained under `raw/capabilities/{gradle,maven}/{asm,jacoco}/`. The declaration
comparison remains against the computed observation, not a literal copy of this table.

## Full-suite result

Fresh `--rerun-tasks` run: **BUILD SUCCESSFUL in 14m 46s**, 68 tasks executed.
Counts are read from this run's JUnit XML, not copied from previous tasks.

| Module | Tests | Passed | Skipped | Failures / errors |
|---|---:|---:|---:|---:|
| smart-test-picker-common | 334 | 323 | 11 | 0 |
| smart-test-picker-core | 29 | 24 | 5 | 0 |
| smart-test-picker-maven | 151 | 151 | 0 | 0 |
| smart-test-picker | 58 | 58 | 0 | 0 |
| stp-agent | 45 | 44 | 1 | 0 |
| stp-runtime | 47 | 47 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| smart-test-picker-cli | 36 | 36 | 0 | 0 |
| **Total** | **721** | **704** | **17** | **0** |

Gradle increased from 48 to 58 tests: two retained selector comparisons, seven unsafe
setup cases, and one legacy rejection. Other module counts and the 17 skips are
unchanged from STP-CAPABILITIES-01. Existing deprecation warnings remain; no build,
dependency or artifact version was changed.

Direct evidence:

- [Before: Gradle false negative](raw/before-gradle/selection.txt)
- [Before: Maven selects the container test](raw/before-maven/selection.txt)
- [After: Gradle selects the container test](raw/after-gradle/selection.txt)
- [Current Gradle JaCoCo fragment](raw/capabilities/gradle/jacoco/fragment.json)
- [Current Gradle functional results](raw/gradle-functional-tests.xml)
- [Current Maven capability results](raw/maven-capability-tests.xml)
- [Selector comparison results](raw/selector-tests.xml)
- [Per-module counts](raw/suite-counts.json)
- [Full rerun console](full-suites.log)

Result: **COMPLETE**. The demonstrated setup-only false negative is repaired for
executable Gradle JaCoCo mapping; the unrepresentable legacy case fails closed.
Relative file-link validation, source/document whitespace checks and
`scripts/check-large-files.sh` passed. Historical raw evidence and pre-existing
untracked directories were preserved.
