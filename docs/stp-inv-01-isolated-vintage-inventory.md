# STP-INV-01: isolated ClassSource-only Vintage inventory

## Scope and fix

Verified on 2026-11-04 against `research/asm-codex`, starting at
`fd5e57cee76e2e4ff0b851a0582274430ec15a51`, with the fix described here.
This is a shared inventory defect, independent of Maven ASM integration.

`TargetJUnitClassLoader` loads `JUnitInventoryDiscoveryWorker` child-first but
delegates `JUnitHeadTestInventoryGenerator` to its parent. Their identical Java
package names do not make them members of the same runtime package. The worker's
call to package-private `vintageMethodName` therefore raised `IllegalAccessError`
for a Vintage test with only a ClassSource and no MethodSource ancestor.

**Option A implemented:** make `vintageMethodName` `public static`. Its pure string
normalization body is unchanged. Option B (move/duplicate the helper) adds churn
or risks divergent normalization between discovery paths. Option C (child-load
the generator) unnecessarily expands the child-loaded class graph and changes
the isolation contract. Neither alternative is needed for this access violation.

The only production change is the helper's visibility and explanatory Javadoc in
[JUnitHeadTestInventoryGenerator](../smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/selector/JUnitHeadTestInventoryGenerator.java).
No loader rules, non-isolated discovery logic, schemas, identities, selection,
collectors, ASM listener or projector were changed.

## Regression evidence

[JUnitHeadTestInventoryGeneratorTest](../smart-test-picker-common/src/test/java/com/sap/oss/smarttestpicker/selector/JUnitHeadTestInventoryGeneratorTest.java)
compiles a minimal field-backed Vintage runner into a temporary directory. It
asserts that the leaf is ClassSource-only, with no MethodSource ancestor; that
the worker and generator have different defining loaders; and that actual
`generate` calls use target-owned and STP-owned JUnit respectively. The runner
throws if discovery attempts execution.

Before the visibility change, the new isolated regression failed with
`IllegalStateException -> InvocationTargetException -> IllegalAccessError` at
the helper call. Afterward, all five generator tests passed. Both paths return
exactly `vintagefixture.ClassSourceOnlyTest#architecture_rule`.

[ReactorHeadTestInventoryMojoIntegrationTest](../smart-test-picker-maven/src/test/java/com/sap/oss/smarttestpicker/maven/ReactorHeadTestInventoryMojoIntegrationTest.java)
extends the existing `schema-v3-reactor` fixture in disposable copies. It adds
a complete project-owned Jupiter/Vintage runtime and a field-backed runner,
then invokes the real inventory mojo, mapping preparation, provider, JaCoCo
report generation and fragment generation. Maven debug origins prove that
module-a's inventory uses `TargetJUnitClassLoader`. Discovery produces no test
reports. Collection compares exact identities, not counts or reconstructed XML
method names.

| Invocation | Identity in inventory, JaCoCo fragment and execution evidence | Result |
| --- | --- | --- |
| Default Surefire, including reactor aggregation | `maven:module-a::a.ClassSourceOnlyTest#architecture_rule` | PASS |
| Qualified Surefire module fragment | `maven:module-a@surefire@default-test::a.ClassSourceOnlyTest#architecture_rule` | PASS |
| Qualified Failsafe module fragment | `maven:module-a@failsafe@fixture-it::a.ClassSourceOnlyTest#architecture_rule` | PASS |

Each fragment has `collection.completed=true`, no unmapped tests, and this
coverage value (the executable identity key differs as shown above):

```json
{
  "classes": ["a.AValue"],
  "methods": ["a.AValue#<init>()V", "a.AValue#value()I"],
  "outcome": "PASS",
  "collectionStatus": "COLLECTED_WITH_COVERAGE"
}
```

The tests retain actual inventory, fragment, evidence and Maven logs under
`smart-test-picker-maven/build/stp-inv-01/{default,surefire,failsafe}/`.
These are generated local evidence, recreated by the test, not committed artifacts.

## Reproduction and full-suite validation

Environment: Maven 3.9.15; Gradle wrapper 8.14; SapMachine JDK
21.0.13.0.3+1-LTS on macOS aarch64; fixture bytecode Java 17; Surefire/Failsafe
3.5.3; JaCoCo 0.8.13; fixture Jupiter/Vintage 5.9.3 and Platform launcher 1.9.3;
JUnit 4.13.2. The common tests and Maven plugin use JUnit 6.1.3. STP component
versions remain `0.3.0-SNAPSHOT`. Put Maven 3.9.15 on `PATH` before these commands.
The existing integration harness publishes the current STP artifacts to the
local functional-test repository and uses a process-isolated Maven repository.

```sh
./gradlew :smart-test-picker-common:test \
  --tests '*JUnitHeadTestInventoryGeneratorTest' --console=plain
./gradlew :smart-test-picker-maven:test \
  --tests '*ReactorHeadTestInventoryMojoIntegrationTest.isolatedClassSourceOnlyVintageInventoryMatchesJacocoMapping' \
  --console=plain
./gradlew :smart-test-picker-common:test :smart-test-picker-maven:test \
  --rerun-tasks --console=plain
```

Full-suite result: **BUILD SUCCESSFUL in 5m 24s**, all 27 Gradle tasks executed
with `--rerun-tasks` (no cached test result):

| Suite | Tests reported | Skipped | Failures / errors |
| --- | ---: | ---: | ---: |
| smart-test-picker-common | 333 | 11 | 0 / 0 |
| smart-test-picker-maven | 59 | 0 | 0 / 0 |

The Maven total includes 23 reactor integration cases, including the three new
provider cases. XML and HTML results are under each module's
`build/test-results/test/` and `build/reports/tests/test/`. The full-run console
log is retained locally at `/tmp/stp-inv-01-full-suites.log`.

## Delivered behavior, limits and follow-ups

- **JaCoCo Maven:** the inventory access defect is fixed. ArchUnit-shaped
  ClassSource-only projects can now generate inventory and mapping. The evidence
  here uses a minimal custom runner, not the ArchUnit library or an AE run.
  Default reactor aggregation and both qualified provider fragments are verified.
- **ASM Maven:** S7 still has no identity from the ASM listener. Its fragment
  remains incomplete and fails closed, as recorded by the earlier spike. This
  task changes neither that behavior nor its historical observations, and does
  not claim a fresh ASM runtime check.
- A separate limitation was observed when additionally invoking
  `aggregate-reactor-coverage-fragment` with either qualified assignment above:
  `Assigned Maven execution target is absent from reactor: maven:module-a@surefire@default-test`
  (likewise `maven:module-a@failsafe@fixture-it`). Its resolver constructs
  unqualified reactor targets and reads unqualified output filenames. Qualified
  module collection succeeds; qualified reactor aggregation is **not** claimed
  as fixed or verified. No aggregation code was changed for this inventory task.

Required follow-ups, not performed here:

1. Rerun **MAVEN-ASM-02 S7 only**, in fresh output directories, to replace its two
   BLOCKED parity observations with an actual inventory/ASM comparison.
2. Separately address `StpRuntimeTestExecutionListener.identity` and the
   unsupported-identity branch in `AsmCoverageFragmentProjector` for ClassSource
   and legacyReportingName fallback.
