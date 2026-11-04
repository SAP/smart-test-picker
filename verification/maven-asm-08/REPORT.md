# MAVEN-ASM-08 Reactor-derived Maven ASM scope

## Decision recorded before implementation

Starting revision: `61be81b462220b7324a21f125d8e71d37391c6ae`, branch
`research/asm-codex`. Existing untracked examples and earlier spike evidence are
outside this task.

Choose **B: production code sources**, in addition to package prefixes. The agent
already suppresses conventional test output directories, but that heuristic does
not cover custom test directories or test-JARs. Maven will supply an optional
file containing the exact allowed production directories/main JARs. Missing code
source information cannot pass this allowlist. Gradle will not set this option;
its existing filtering remains unchanged. Test JARs are never production roots,
even when their packages coincide with production packages.

Derive package prefixes from compiled production class names, not groupId or
test classes. Use Maven's collected project model (`session.allProjects`, before
`-pl` restriction), starting from active projects and following dependencies only
when their exact GAV identifies another collected reactor project. Active module
outputs contribute production directories. A dependency omitted by `-pl` must
contribute its installed main artifact, not an arbitrary dependency or test JAR.
No third-party dependency graph or repository-wide scan is permitted.

Derivation requires compiled production outputs (the existing preparatory build
is the prerequisite). Do not silently derive a partial scope when a required
production output is absent. POM-only projects contribute no class roots.
Default-package production classes cannot be represented by the existing package
prefix contract and must produce a focused preparation failure, not be ignored.

Nonblank explicit `smartTestPicker.asm.includes` remains a prefix override, not
an augmentation; excludes still narrow it. Explicit blank/malformed input is
rejected. Both derived and explicit scopes remain bounded by the same production
code-source allowlist. Explicit prefixes do not opt third-party/test artifacts in.

The Maven attachment will opt into a zero-instrumentation guard. At shutdown,
`AgentMetrics.classesTransformed == 0` will prevent fragment publication and name
the effective scope/count. This is not a minimum edge-count rule. Maven finalization
will also validate the diagnostic before accepting evidence. Gradle/raw existing
callers do not enable this new opt-in guard, preserving their current behavior.

A genuine empty test remains valid if the mapping JVM instruments production
classes but that test executes no production methods. A JVM that loads no eligible
production class at all is indistinguishable from a zero-match scope and is rejected
by the requested guard. The empty fixture will load, without initializing or calling,
a production class and verify empty per-test edges; this distinction is explicit.

Measure test-class inflation using the same fixture/code with production-only
sources and an unsupported raw-agent control that lacks the new allowlist and
loads test code from a custom location/test JAR. Do not add a supported option to
include tests in prepared Maven mapping. Preserve JaCoCo, identity, schema,
selection, listener, projector and fork policies.

## Results

PASS. The six Maven runtime cases, all four required suites and the supplementary
agent suite pass. The implementation uses option B; it does not accept test-code
inflation. Validation date: 2026-11-04.

| Case | Effective includes | Instrumented classes | Result |
| --- | --- | ---: | --- |
| Full reactor, no supplied includes | `a.,b.` | 2 | Cross-module coverage and positive evidence accepted |
| `-pl .,module-b`, module A installed | `a.,b.` | 2 | Same edges; module A's classes directory removed before the run |
| Explicit wrong prefix | `wrong.prefix.` | 0 | Maven fails; no fragment/evidence pair |
| Explicit scope entirely excluded | `a.,b.` with matching excludes | 0 | Maven fails; no fragment/evidence pair |
| One empty test, production class loaded without initialization | `a.,b.` | 1 | PASS / COLLECTED_EMPTY, complete fragment |
| Custom test output plus installed test JAR | `a.,b.` | 2 | Only production edges retained |

### Cross-module and empty evidence

Both [full-reactor](raw/reactor/coverage-fragment-v3.json) and
[installed-JAR](raw/installed/coverage-fragment-v3.json) fragments preserve exactly:

```text
maven:module-b::b.BTests#b2
  a.AValue#<init>()V
  a.AValue#value()I
  b.BValue#<init>()V
  b.BValue#value()I
```

`maven:module-b::shared.SharedTest#same` acquires none of those edges. Tests assert
the exact identities/classes and production evidence-v2 partition, then run the
real reactor aggregation goal. The installed case moves module A's compiled
classes out of the fixture before mapping; its allowed source is the isolated
local repository's `fixture/module-a/1/module-a-1.jar`, not its old classes output.

The [empty-only fragment](raw/legitimate-empty/coverage-fragment-v3.json) has one
PASS / COLLECTED_EMPTY identity, no methods/classes, empty unmapped, and completed
collection. BeforeAll only calls `Class.forName("a.AValue", false, loader)`; it
does not initialize the class or execute a production method. The diagnostic
records one transformed class. This does not excuse zero instrumentation: an
all-disabled fork or one that never loads eligible production code is rejected.

### Test code inflation measurement

The fixture uses `target/checks` for module B's test output and consumes module A's
attached test JAR. Both test code sources share packages with production classes.
An unsupported raw-agent control omits the new allowlist and repeats the same test.
No production opt-out was added to preparation.

| Measurement | Prepared production-only scope | Raw control including test code |
| --- | ---: | ---: |
| Serialized module fragment bytes | 390 | 598 |
| Test-to-class edges | 2 | 4 |
| Test-to-method edges | 4 | 6 |

The extra classes are `b.BTests` and `a.TestHelper`; the extra methods are the test
method and test helper. See [comparison data](raw/test-inflation/comparison.json),
[production-only fragment](raw/production-only/coverage-fragment-v3.json), and
[control fragment](raw/test-inflation/with-tests-fragment.json). The restriction
removes 208 bytes of inflation, two class edges and two method edges in this case.

### Fail-closed behavior

[Wrong-prefix output](raw/wrong-prefix/maven.log) names
`classesTransformed=0; includes=["wrong.prefix."]` and the effective excludes.
The test body executes successfully, but the agent emits its diagnostic without
publishing a coverage fragment. Maven's fragment goal independently rejects the
zero metric and leaves no execution evidence; aggregation/publication cannot
mistake the test exit status for a valid map. The fully excluded case follows the
same path. No per-test edge-count threshold is used.

## Implementation and compatibility

- `MavenAsmScope` walks Maven's collected model, computes the reactor dependency
  closure by exact GAV and derives packages from production class paths/JAR entries.
  Resource-only modules add no packages. Main JAR lookup uses the current session's
  local repository manager, not an assumed `~/.m2` and not remote dependency scans.
- Preparation applies the existing fork guard first, resolves scope before agent
  attachment, then writes `asm-production-sources<target-suffix>.txt` beside the
  prepared fragment. Explicit prefixes still override derivation within these roots.
- Agent options `productionSourcesFile` and `requireInstrumentation` are additive
  and unset by Gradle. Exact normalized code-source equality rejects unknown,
  third-party and test sources. The explicit model is authoritative even when a
  production directory has an unconventional name; without it the prior test-path
  heuristic remains unchanged.
- `AgentMetrics.classesTransformed` is the observed count, not the number of classes
  that merely matched a prefix. Shutdown refuses zero-instrumentation publication.
  Diagnostics include the source allowlist and guard status. Maven finalization
  requires the diagnostic, verifies revision/shard/output binding and positive count
  before writing evidence. Schema, listener, projector, selector and JaCoCo behavior
  are unchanged.

Permanent tests reuse the existing `schema-v3-reactor` fixture and isolated Maven
helper. `AsmReactorScopeIntegrationTest` adds six real Maven cases; `MavenAsmScopeTest`
adds six focused model cases, including incomplete outputs, blank override,
default-package and overlapping production/test outputs. The agent regression
checks production, custom-test, test-JAR, third-party and unknown code sources.
The ASM-07 JaCoCo report/order regressions remain in the Maven suite.

Fresh ASM-07 comparisons from this rerun still have zero differences in all 44
class/method counter pairs for both [literal](regressions/jacoco-literal-counters.json)
and [prepare-agent](regressions/jacoco-prepare-agent-counters.json) argLines.

## Reproduction and regression results

```sh
./gradlew :smart-test-picker-maven:test --tests '*AsmReactorScopeIntegrationTest' --console=plain
./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-agent:test \
  --rerun-tasks --console=plain > verification/maven-asm-08/full-suites.log 2>&1
```

Environment: Maven 3.9.15, Surefire 3.5.3, JDK SapMachine 21.0.13.0.3,
Gradle 8.14, macOS arm64; STP components remain `0.3.0-SNAPSHOT`. Project builds
use the existing functional-test repository and process-isolated Maven repository.
No Jenkins, AE, PetClinic, Sonar or external deployment is involved.

| Suite | Reported tests | Passed | Skipped | Failures / errors |
| --- | ---: | ---: | ---: | ---: |
| common | 333 | 322 | 11 | 0 / 0 |
| core | 29 | 24 | 5 | 0 / 0 |
| maven | 142 | 142 | 0 | 0 / 0 |
| smart-test-picker (Gradle) | 46 | 46 | 0 | 0 / 0 |
| stp-agent (additional suite) | 45 | 44 | 1 | 0 / 0 |

All requested baselines are held; Maven increases from 130 to 142. Existing
common/core/agent skips remain. See [machine-readable results](suite-summary.json)
and [new Maven runtime tests](test-results/TEST-com.sap.oss.smarttestpicker.maven.AsmReactorScopeIntegrationTest.xml).
The [Gradle functional regression](test-results/TEST-com.sap.oss.smarttestpicker.GradleCollectorBackendFunctionalTest.xml)
covers default/explicit ASM, consumer-declared core, failure outcomes and backend
switching. Its new agent options remain absent; no Gradle production code changed.

The [full rerun](full-suites.log) executed all four required suites successfully
under `--rerun-tasks`, then failed compiling the additional agent test because
the new test lacked the static import for `Assertions.fail`. The only subsequent
code edit was that test import. The [entire agent rerun](agent-rerun.log), also
with `--rerun-tasks`, passed in 11 seconds. Finally, the
[combined confirmation](combined-confirmation.log) passed in 2 seconds with 16
tasks executed and 41 up-to-date, reusing the unchanged, passing suite results.
This final confirmation is not presented as a fresh rerun of those tests.

The preliminary `full-suites-initial.log` was intentionally stopped (exit 143)
after a timestamp check showed the last source adjustment landed immediately
after agent compilation. It is not acceptance evidence. `full-suites.log` is the
clean rerun from the final source state; raw scenario outputs are refreshed from
that run. The focused logs record earlier checks, not a replacement for the full
rerun. Documentation links and source whitespace checks pass. All task evidence
files are below 5 MiB; existing unrelated oversized EI-10/EI-11 artifacts are
unchanged. Raw console whitespace is preserved.

## Remaining boundaries

- Preparation derives from compiled outputs; it does not run a hidden build.
  A supplied prefix avoids package derivation but never bypasses source restriction.
- The collected model must contain an omitted module to recognize its installed
  main JAR. Separate unrelated builds/dependencies are not inferred to be reactor
  modules by groupId. Matching installed artifacts to the exact head remains the
  preparation caller's prerequisite, not a new provenance service.
- Omitted non-JAR projects and default-package derivation fail explicitly. There
  is no scan of arbitrary dependency JARs, test JARs or the whole Maven repository.
- A deliberately narrower explicit prefix that still instruments some production
  classes remains an intentional override. This task detects zero instrumentation,
  not every possible incomplete user-chosen scope.
- Gradle retains its current scope/zero-instrumentation policy; this task does not
  enable the Maven opt-ins there or claim Gradle project-JaCoCo report preservation.
- Per-fork merging, S7 identity support, Jenkins enablement, publication and
  selection changes remain outside scope.
