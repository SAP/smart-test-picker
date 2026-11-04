# MAVEN-ASM-03: selected collector isolation

> Historical implementation snapshot of `bd889ab`. Its absent-selection behavior
> regressed ordinary consumers. The [MAVEN-ASM-03-FIX report](../verification/maven-asm-03-fix/REPORT.md)
> supersedes activation behavior: collection now defaults to inactive and selection
> is evaluated only after explicit activation. Original results below are preserved.

## Decision and scope

Implemented on `research/asm-codex`, starting from `e5c314d` (which includes the
completed STP-INV-01 fix on top of the requested `fd5e57c` baseline).

**Option B is the protection boundary:** both core SPI components self-gate on
the selected collector. Removing an injected dependency alone (option A) cannot
protect a consumer that declares core itself. Maven bootstrap also avoids injecting
the JaCoCo runtime in ASM mode, as a complement, not a substitute for the gate.

`StpCollectorSelection` is a JDK-only contract in core. Both components read the
test-JVM system property `smartTestPicker.coverageCollector` at construction:

| Value | Core's JaCoCo listener and Jupiter extension |
| --- | --- |
| `ASM` | Silent no-op: no runtime access, probe reset, session/identity/setup output or metrics callbacks |
| `JACOCO`, absent or blank | Legacy JaCoCo selection; an active JaCoCo runtime is required |
| Other value | Invalid configuration, rejected rather than interpreted as a disabled collector |

Values are trimmed and case-insensitive. Selection is invocation configuration,
not something to change between callbacks. SPI discovery may still instantiate
both services; discovery does not activate their disabled collection logic.

Gradle's existing backend selection is unchanged: **ASM remains the default**.
Each backend now supplies its selected value to the mapping test JVM. Thus a
consumer-owned core dependency cannot activate JaCoCo during ASM mapping.

Managed Maven bootstrap resolves user properties before project properties, then
JVM properties, and transports the value to Surefire/Failsafe. Its runtime lookup
is collector-aware: only JaCoCo resolves `JacocoPerTestListener.class`; ASM adds
no JaCoCo classpath entry. The ambiguous generic SPI-resource lookup is removed.
Existing consumer classpath entries are preserved.

For manually attached Maven ASM experiments, supply
`-DsmartTestPicker.coverageCollector=ASM` to the test fork as well as the existing
agent configuration. Attaching an agent alone is not this explicit selection
contract. This task does **not** add agent attachment, fork coordination, ASM
fragment/evidence orchestration, or Jenkins integration. In particular, it does
not claim that selecting ASM makes the current managed Maven evidence verifier
consume ASM evidence.

## Selected JaCoCo still fails closed

The listener validates its selected JaCoCo runtime during SPI construction.
An unavailable runtime now stops launcher startup with a service-construction
failure, before test bodies execute. This avoids relying on exceptions from
`TestExecutionListener` callbacks, which JUnit logs and swallows. Surefire can hide
the nested cause, so the selected component prints the unchanged diagnostic
before rethrowing:

```text
Active JaCoCo runtime is unavailable for STP mapping
```

No diagnostic is emitted by either core component in ASM mode. Healthy JaCoCo
collection and the existing extension/listener duplicate-processing guard remain
unchanged. No schemas, identity rules, selection policy, ASM listener/projector,
or agent attachment code were changed.

## Acceptance evidence

The existing `schemaV3FailsClosedWhenTestForkHasNoActiveJacocoRuntime` regression
is unchanged and passes, including its exact diagnostic assertion.

New tests cover:

- Every disabled core callback, including skipped tests and setup/container entry,
  is a no-op. A fresh JVM with core SPI and extension autodetection executes its
  probe successfully in ASM mode without any Java agent; no JaCoCo output appears.
- The same fresh-JVM probe in JaCoCo mode exits unsuccessfully at SPI construction,
  reports the runtime diagnostic and does not execute its body.
- Maven bootstrap resolves selection precedence, transports it, retains the
  JaCoCo runtime in JaCoCo mode, and supplies no JaCoCo runtime in ASM mode.
- A real Surefire fork uses the copied `schema-v3-reactor` fixture, consumer-declared
  core, Jupiter extension autodetection, and the shaded ASM agent. JaCoCo is absent
  (also asserted inside the test JVM). Only the agent attachment and output paths
  are manually configured in this test; no new production attachment logic exists.
- A real Gradle TestKit build uses the default ASM backend with consumer-declared
  core and extension autodetection, without the JaCoCo plugin. Mapping succeeds,
  the fragment is complete with one mapped test and no unmapped entries, and no
  `build/jacoco` directory is written. Existing collector-switching, JaCoCo
  fallback and ordinary project-JaCoCo coexistence tests also pass.

The Maven fragment is inspected directly through the production schema-v3 codec:

| Executable identity | Classes | Result |
| --- | --- | --- |
| `maven:module-b::b.BTests#b2` | `a.AValue`, `b.BValue` | PASS / COLLECTED_WITH_COVERAGE |
| `maven:module-b::shared.SharedTest#same` | none | PASS / COLLECTED_EMPTY |

The first identity has both `a.AValue#value()I` and `b.BValue#value()I` method edges;
the second has no method edges. `unmapped=[]`, `collection.completed=true`.
The unrelated test does not acquire coverage merely because both classes were
instrumented in its JVM. Fresh local evidence is retained under
`smart-test-picker-maven/build/maven-asm-03/{fragment.json,diagnostic.json,maven.log}`.

## Reproduction and results

Verified locally on 2026-11-04 using Maven 3.9.15 on `PATH`, Gradle wrapper 8.14,
SapMachine JDK 21.0.13.0.3+1-LTS, Surefire 3.5.3 and Jupiter 5.9.3 in the Maven
fixture. STP versions remain `0.3.0-SNAPSHOT`. The Maven test task builds the
shaded agent and publishes current STP dependencies to its existing local
functional-test repository before starting isolated Maven fixtures.

```sh
./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test --rerun-tasks --console=plain
```

Full-suite result: **BUILD SUCCESSFUL in 6m 20s**, all 52 Gradle tasks executed
with `--rerun-tasks` (no up-to-date/cache substitution).

| Suite | Tests | Skipped | Failures / errors |
| --- | ---: | ---: | ---: |
| `smart-test-picker-common` | 333 | 11 | 0 / 0 |
| `smart-test-picker-core` | 27 | 5 | 0 / 0 |
| `smart-test-picker-maven` | 62 | 0 | 0 / 0 |
| `smart-test-picker` (Gradle plugin) | 44 | 0 | 0 / 0 |

Counts include skipped tests. The fresh-JVM probe's test is deliberately disabled
in the ordinary core suite and enabled only by its subprocess harness.
Results are generated under each module's `build/test-results/test/` and
`build/reports/tests/test/`.
The console log is `/tmp/maven-asm-03-full-suites.log`.

`git diff --check` passes. The repository-wide large-file check still rejects two
pre-existing tracked artifacts: `EI-10/artifacts/published-r0-map.json`
(30,045,626 bytes) and `EI-11/artifacts/deployed-smart-test-picker.hpi`
(10,450,073 bytes). Both sizes match the initial HEAD; neither file was changed.

Prior MAVEN-ASM spike files were not modified or rerun. ASM's ClassSource-only
Vintage identity gap remains a separate collector task; this change does not
claim to resolve S7 or to complete Maven ASM integration.
