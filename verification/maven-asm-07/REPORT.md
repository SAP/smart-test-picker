# MAVEN-ASM-07 Maven ASM and project JaCoCo report preservation

## Result

PASS: **the tested prepared Maven ASM path preserves
the project's JaCoCo counters exactly**, for both a literal JaCoCo agent argLine
and JaCoCo `prepare-agent` with `@{argLine}`. This includes generated-code filters
and an implicit-exception path, not just ordinary methods. The full four-suite
rerun also passes.

A project can adopt this tested configuration without changing its existing
coverage reporting. Keep JaCoCo before STP. This is bounded to the fixture,
versions and single reused Surefire fork below, not a guarantee for arbitrary
agents, JVMs or build configurations. **Gradle report preservation remains
unproven**; its earlier technical coexistence evidence is not this measurement.

No production source changed. The additions are a focused Maven fixture, permanent
tests, test-only JaCoCo analysis dependencies, and documentation. No agent-order
option, mitigation, Sonar integration or quality-gate change was introduced.

## Provenance and reproduction

- Branch: `research/asm-codex`.
- Starting STP revision: `b13cdba2597ad391a1d0cb1183a34188ec90373c` (MAVEN-ASM-06).
- STP components: `0.3.0-SNAPSHOT`.
- Maven 3.9.15, Surefire 3.5.3, JaCoCo 0.8.13, Jupiter 5.9.3.
- SapMachine JDK 21.0.13.0.3, compiling Java 17 class files; Gradle 8.14.
- macOS arm64. No Jenkins or external quality service was used.

The new [fixture](../../smart-test-picker-maven/src/test/fixtures/asm-jacoco-coexistence/pom.xml)
uses the existing Maven functional-test repository and process-isolated local
repository helper. STP binaries are published by the existing Gradle test setup,
not taken from a developer's global `.m2`. Baselines activate neither the STP
profile nor an STP goal and assert that core is absent from the fork classpath.
Prepared runs add core, enable Jupiter extension autodetection, and use the real
preparation and fragment-finalization goals. Every run uses a fresh fixture copy;
no previous report or execution file can satisfy the comparison.

```sh
./gradlew :smart-test-picker-maven:test \
  --tests '*AsmJacocoCoexistenceIntegrationTest' --console=plain

./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test \
  --rerun-tasks --console=plain > verification/maven-asm-07/full-suites.log 2>&1
```

Each scenario's `command.txt` records its actual Maven arguments; `fork-command.txt`
records Surefire's executed JVM command, not a helper's proposed argLine.
`fork-arguments.txt` is independently recorded inside the fork using RuntimeMXBean.
Maven debug environment entries are redacted from retained logs. Temporary paths
in those records identify the measured run; the permanent test recreates them.

## P1 through P4 Exact counter comparison and sensitive shapes

Both baseline and prepared runs execute exactly:

```text
fixture.ShapesTest#enumShape
fixture.ShapesTest#recordShape
fixture.ShapesTest#bridgeShape
fixture.ShapesTest#implicitExceptionShape
```

All four pass with no errors or skips. Prepared fragments contain the exact four
`maven:.::fixture.ShapesTest#...` identities, and evidence v2 contains the same
EXECUTED set with empty NON_EXECUTED. The compiled production class bytes are
asserted identical between baseline and coexistence.

| Project argLine shape | Baseline | ASM coexistence | Counter differences |
| --- | --- | --- | ---: |
| Literal options and literal JaCoCo `-javaagent`; prepare-agent disabled | Project JaCoCo only | Prepared ASM appended after JaCoCo | 0 |
| Project `prepare-agent`, late `@{argLine}`, literal options | Project JaCoCo only | Prepared ASM appended after late expansion/options | 0 |

Every reported class and descriptor-exact method is compared for all its XML
counter types: missed and covered INSTRUCTION, LINE, COMPLEXITY, METHOD and CLASS
where present. Counter presence and absent filtered methods are significant.
Session IDs/timestamps and report labels are not coverage counters. No percentages,
aggregate-only comparison, normalization of counters or tolerance is used.

Complete comparison data, including both maps and the empty differences map:

- [Literal P1/P2 counter data](raw/literal/counter-comparison.json).
- [Prepare-agent P1/P2 counter data](raw/prepare-agent/counter-comparison.json).
- Original XML: [literal baseline](raw/literal/baseline/jacoco.xml),
  [literal coexistence](raw/literal/coexist/jacoco.xml),
  [prepare-agent baseline](raw/prepare-agent/baseline/jacoco.xml),
  [prepare-agent coexistence](raw/prepare-agent/coexist/jacoco.xml).

Each comparison checks 44 counter pairs across five class entries (including a
code-free interface) and six reported methods. Important exact observations:

| Required shape | Fixture and proof | Baseline and supported coexistence |
| --- | --- | --- |
| Empty enum constructor | `EmptyEnum`; bytecode asserted as ALOAD, ALOAD, ILOAD, INVOKESPECIAL, RETURN | Constructor absent from JaCoCo methods; ASM nevertheless records its entry |
| Record generated accessors | `ValueRecord(String text, int number)`; ACC_RECORD asserted, both accessors called | `text()` and `number()` absent; constructor has 0 missed / 9 covered instructions |
| Synthetic bridge | `BridgeValue implements GenericValue<String>`; ACC_BRIDGE and ACC_SYNTHETIC asserted, invoked through the interface | Object-returning bridge absent; String-returning `get()` has 0 missed / 2 covered instructions |
| Implicit exception before exit probe | `ImplicitException#length(String)` calls `value.length()` with null; test catches the expected NPE | 3 missed / 0 covered instructions and 1 missed / 0 covered methods |

These are deliberately small instances of the filter-sensitive shapes identified
by [TASK 20](../../docs/task20-jacoco-fn-confirmation.md), not a new Spring campaign.
No JaCoCo report-preservation claim is inferred from merely retaining an `.exec`.

## P5 Measured absence of STP reset and dump calls

[ProbeObserver](../../smart-test-picker-maven/src/test/fixtures/asm-jacoco-coexistence/observer/ProbeObserver.java)
is a test-only Java agent registered before both coverage agents. It observes
JaCoCo's actual runtime methods with stack traces:

- `Agent.reset()`, `Agent.dump(boolean)`, `Agent.getExecutionData(boolean)`.
- `RuntimeData.reset()` and `RuntimeData.collect(..., boolean)`.

It never transforms project classes, changes probe arrays, or invokes a JaCoCo
control API. The unrelated RuntimeData `getExecutionData(Long,String,int)` overload
is ordinary probe lookup, not a snapshot/reset, and is intentionally excluded.
Observation installation and method matches are asserted. A run without the
observer also produces exactly the baseline counters, so the observation mechanism
itself does not change the measured project report.

For each of the four P1/P2 runs:

```text
Agent.reset calls:             0
Agent.dump calls:              0
Agent.getExecutionData calls:  0
RuntimeData.reset calls:       0
RuntimeData.collect calls:     1, reset=false, JaCoCo shutdown stack
STP callers in these stacks:   0
```

Evidence: [literal coexistence trace](raw/literal/coexist/probe-calls.txt),
[prepare-agent coexistence trace](raw/prepare-agent/coexist/probe-calls.txt),
and [observer-absent XML](raw/observer-absent/jacoco.xml). Baseline traces are
alongside their XML files.

The [positive control](raw/observer-control/probe-calls.txt) explicitly invokes
reset, resetting snapshot and non-resetting dump from `ShapesTest` after the tests.
It records Agent.reset once, Agent.getExecutionData(true) once, Agent.dump(false)
once, RuntimeData.reset twice, and three collections (one true, two false including
shutdown). This proves sensitivity, not just an empty log. For no-argument reset(),
the call occurrence is the reset; the trace's placeholder boolean is not an outcome.
The deliberately reset control report is not a P1/P2 comparison input.

## P6 Permanent effective-fork ordering regression

`preparedAsmPreservesEveryProjectJacocoCounterAndAgentOrder` is parameterized over
both project shapes. It requires the actual Surefire `Forking command line` to
contain the JaCoCo agent before the STP agent. Missing either agent or reversing
them fails the regression. The fork also records its input arguments. The observer
comes first but does not transform project code; relative JaCoCo/STP order is the
protected property.

See the actual [literal fork](raw/literal/coexist/fork-command.txt) and
[prepare-agent fork](raw/prepare-agent/coexist/fork-command.txt).
MAVEN-ASM-05 already asserted ordering for some active-JaCoCo shapes, but did not
measure XML correctness. This regression supplies both proofs together, including
the literal JaCoCo attachment path.

## P7 Unsupported inverse order and every counter difference

After the supported comparison passes, one disposable prepare-agent fixture is
edited to attach STP manually before JaCoCo. It bypasses preparation intentionally;
there is no new production option to select this order. The same four tests pass.
No reset/dump occurs. Two distinct effects must not be conflated.

### Ordinary project report against original classes

The project still reports against `target/classes`. JaCoCo's execution IDs now
describe ASM-transformed bytes instead. It warns that `BridgeValue`, `EmptyEnum`,
`ImplicitException` and `ValueRecord` do not match execution data. All previously
covered instructions, lines, methods, complexity and classes in those four classes
become missed. Nine class/method entries change: the four classes plus the five
previously covered methods. The already-uncovered `length(String)` and code-free
interface remain unchanged. Generated methods stay filtered when analyzing the
original bytes.

See [ordinary inverted XML](raw/unsupported-inverse/jacoco.xml) and the complete
`projectReportDifferences` map in [the comparison data](raw/unsupported-inverse/counter-comparison.json).
This is the direct breakage an ordinary project report sees; Maven's report goal
can still return success despite the mismatch warnings.

### Diagnostic analysis of the bytes JaCoCo actually received

Separately, the test analyzes only project classes from JaCoCo's `classdumpdir`
against the same `.exec`. This matches all execution IDs and isolates filter and
instruction effects. It is diagnostic evidence, **not a reporting workaround**.
The report input/output classes are retained under the inverse scenario's `classes/`.

All 16 changed entries are enumerated in `matchedInputDifferences`:

| Changed method entries | Instruction counters, missed/covered | Explanation |
| --- | --- | --- |
| `BridgeValue#<init>()V`, `BridgeValue#get()String`, `ImplicitException#<init>()V`, `ValueRecord#<init>(String,int)V`, `EmptyEnum#<clinit>()V` | 0/3 -> 0/5, 0/2 -> 0/4, 0/3 -> 0/5, 0/9 -> 0/11, 0/9 -> 0/11 respectively | Two injected entry instructions become part of JaCoCo's analyzed method; other method counters unchanged |
| `EmptyEnum#<init>(String,int)V` | absent -> 0/7 | The empty-constructor filter no longer matches; adds one covered method, complexity unit and line |
| `ValueRecord#text()String`, `ValueRecord#number()int` | absent -> 0/5 each | Generated-accessor filter no longer matches; each adds one covered method, complexity unit and line |
| `ValueRecord#equals(Object)boolean`, `hashCode()int`, `toString()String` | absent -> 6/0, 5/0, 5/0 | Generated-method filtering changes; these methods are not called, so each adds a missed method, complexity unit and line |
| `ImplicitException#length(String)int` | 3/0 -> 3/2 | Injected prefix receives coverage while the original instructions remain missed; METHOD and COMPLEXITY change from 1/0 to 0/1, LINE remains 1/0 |

The other four changed entries are the class counters derived from those methods:

- BridgeValue: covered instructions 5 -> 9; other counters unchanged.
- EmptyEnum: covered instructions 9 -> 18, covered methods/complexity 1 -> 2,
  covered lines 2 -> 3; CLASS unchanged.
- ImplicitException: instructions 3/3 -> 3/7, methods/complexity 1/1 -> 0/2;
  LINE and CLASS unchanged.
- ValueRecord: instructions 0/9 -> 16/21, methods/complexity 0/1 -> 3/3;
  LINE and CLASS unchanged (generated members share the same source line).

The synthetic bridge remains filtered in this contrast; `GenericValue` has no
counter changes. All descriptor-exact names, all counter types and absent entries
are preserved in the JSON, and the [matched-input XML](raw/unsupported-inverse/jacoco-on-jacoco-input.xml)
provides the independent JaCoCo result. The enum's seven covered instructions
reproduce TASK 20's specific filter hazard.

## Regression results and boundaries

The [full console log](full-suites.log) records `BUILD SUCCESSFUL in 12m 19s`,
with all 52 tasks executed under `--rerun-tasks`. Results from the generated
JUnit XML are retained in [suite-summary.json](suite-summary.json):

| Suite | Reported tests | Passed | Skipped | Failures / errors |
| --- | ---: | ---: | ---: | ---: |
| common | 333 | 322 | 11 | 0 / 0 |
| core | 29 | 24 | 5 | 0 / 0 |
| maven | 130 | 130 | 0 | 0 / 0 |
| smart-test-picker (Gradle) | 46 | 46 | 0 | 0 / 0 |
| Total | 538 | 522 | 16 | 0 / 0 |

All requested baselines are retained; Maven increases from 127 to 130 because of
the two argLine cases and observer positive control. Existing common/core skips
are unchanged. The [new regression's JUnit XML](test-results/TEST-com.sap.oss.smarttestpicker.maven.AsmJacocoCoexistenceIntegrationTest.xml)
records three passing tests. Raw scenario evidence in this report is from this
full rerun, not from the earlier harness-development runs.

Documentation links and source/document whitespace checks pass. All new evidence
files are below 5 MiB. The repository-wide large-file check still fails on two
pre-existing files, unchanged by this task: `EI-10/artifacts/published-r0-map.json`
(30,045,626 bytes) and `EI-11/artifacts/deployed-smart-test-picker.hpi`
(10,450,073 bytes). Raw Maven console logs retain their original whitespace.

Two measurement-harness corrections are recorded in the retained focused logs:
the first observer watched ordinary probe lookup/session setup as well as control
APIs; the initial manual inverse setup omitted the agent output directory. Both
were corrected in test code. Neither was a supported-order counter difference,
and no comparison tolerance was relaxed. [The complete focused rerun](focused-final.log)
passed all three tests.

This work does not prove Gradle report preservation, arbitrary transformer order,
multiple forks, every filter across JaCoCo/JDK versions, or every project/plugin
combination. It changes no production collector, listener, projector, Maven
attachment code, schema, selection logic or Jenkins component. No mitigation was
designed for the intentionally unsupported order.
