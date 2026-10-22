# Name mapping

Pinned R2: `2e0954b5b590fb0b9da979c28b0d053e4ce9e5c9`.
Paths below are relative to the tool repository root.

| Anonymized name | Real class/file or module path |
|---|---|
| `anon.tool` | `com.sap.oss.smarttestpicker` (package root) |
| `JacocoPerTestListener` | `smart-test-picker-core/src/main/java/com/sap/oss/smarttestpicker/JacocoPerTestListener.java` |
| `TestLifecycleExtension` | `smart-test-picker-core/src/main/java/com/sap/oss/smarttestpicker/TestLifecycleExtension.java` |
| `SessionFileNames` | `smart-test-picker-core/src/main/java/com/sap/oss/smarttestpicker/SessionFileNames.java` |
| `CoverageConverter` | `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/mapper/CoverageMapperJaxb.java` |
| `CoverageMapReader` | `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/mapper/CoverageMapReader.java` |
| `studied-tool-core` | `smart-test-picker-core/` |
| `studied-tool-common` | `smart-test-picker-common/` |
| `studied-tool-maven` | `smart-test-picker-maven/` |
| `studied-tool-cli` | `smart-test-picker-cli/` |
| `studied-tool` (Gradle plugin) | `smart-test-picker/` |

---

New piece of work for STP: a method-entry observer experiment for the ICST paper
(revision / follow-up). The full task spec is in
~/Downloads/entry-observer-tasks.md.

1. Create branch `entry-observer` from the pinned R2 revision (the one that
   produced the eight analyzed maps), not from current main. Tell me which
   commit you used.
2. Copy the spec to experiments/entry-observer/TASKS.md on that branch.
3. The spec was written against the anonymized R2 source (package `anon.tool`,
   classes JacocoPerTestListener, TestLifecycleExtension, SessionFileNames,
   CoverageConverter, CoverageMapReader, module names studied-tool-*). Find the
   real equivalents in this repo and add a short "Name mapping" table at the
   top of TASKS.md (anonymized name -> real class/file path). Do not change
   anything else in the spec.
4. Read the CONTEXT section and T0 to T5. Then, before writing any code, reply
   with: (a) the name mapping, (b) the exact lines in the real listener and
   extension where JaCoCo dump(true) is called, (c) anything in the spec that
   contradicts how STP actually works, and (d) your plan for T0.
5. Wait for my OK. After that, do only T0, commit, and stop.

Ground rules for every later task: one task per commit, stop at its
acceptance criteria and report what changed and how it was verified. Never
modify frozen maps, PIT matrices or published results.


# Entry-observer experiment: Claude Code tasks (phase 1: T0 to T5)

How to use this file:

- Put it in the tool repository as `experiments/entry-observer/TASKS.md`.
- Start each Claude Code session with: *"Read experiments/entry-observer/TASKS.md, section CONTEXT and task Tn. Do only Tn. Stop when its acceptance criteria pass and report what changed and how you verified it."*
- One task per session and one commit per task, on branch `entry-observer`. Review each commit before starting the next task.
- T0 to T4 run in the tool repository and need no subject checkouts. T5 needs the spring-core and Hibernate setups on your machine.

Placeholders:

| Placeholder | Meaning |
|---|---|
| `<TOOL_REPO>` | Non-anonymized tool repository at the R2 revision |
| `<PKG>` | A working copy of the replication package (never the frozen v16 archive) |
| `<SHADED>` | Relocation prefix for the agent's bundled libraries, e.g. `<toolprefix>.entry.shaded` |

Class names below are those of the anonymized R2 source (`JacocoPerTestListener`, `TestLifecycleExtension`, `SessionFileNames`, `CoverageConverter`, `CoverageMapReader`). Use the corresponding classes in the real repository.

---

## CONTEXT (read before every task)

**Study.** The paper measures per-test JaCoCo coverage as a dependency signal for regression test selection. The R2 collector and selector are pinned. On 4,070 KILLED PIT records, 19 killing tests are missed:

- 18 by *early-exception probe shadowing*: the changed method is entered, but it exits by an exception before its first JaCoCo probe, so JaCoCo records no instruction of it;
- 1 by a *pre-session attribution gap* in Hibernate: the method runs during engine discovery, before the leaf session.

**Experiment.** Add an *additive method-entry observer* that records which production methods were entered, using exactly the same per-test window as R2. One collection pass per subject yields three maps:

- **(a)** JaCoCo only (unchanged R2 converter);
- **(b)** JaCoCo ∪ entry edges (name-level keys, same schema as R2);
- **(c)** entry and JaCoCo edges with descriptor-level keys (`Class#name(desc)`).

**Invariants (apply to every task):**

- **I1. JaCoCo is unchanged.** JaCoCo 0.8.13 is not modified, and the JaCoCo `-javaagent` comes **before** the entry agent. JaCoCo computes class IDs from the bytes it receives, so it must see pristine bytes.
- **I2. R2 is unchanged without the agent.** With the entry agent absent, R2 behaviour is identical: same files, same contents, no new output, no new log lines. All existing tool tests pass unchanged.
- **I3. Same window.** Entry flags are snapshotted and reset at exactly the point where R2 calls JaCoCo `dump(true)`. That point appears in both `JacocoPerTestListener.executionFinished` and its fallback `TestLifecycleExtension.afterTestExecution`. Use the same session ID and the same file naming (`SessionFileNames.sanitize`). Repeated invocations of one logical test accumulate as a union, like the appended `.exec` files.
- **I4. Same method universe.** Entry edges are restricted to production, source-backed classes and to methods that JaCoCo reports for that class. Names follow the converter: FQN with `$` for nested classes, `method.getName()`, `lambda$...` kept. Edges outside that universe are counted and reported, never silently dropped.
- **I5. Frozen data stays frozen.** Do not modify the frozen maps, PIT matrices or published results. All new outputs go to new directories.
- **I6. Every task finishes with tests.** End each task with automated tests and a short written report.

---

## T0. Freeze the analysis plan (about 2 hours)

**Goal.** Record the hypotheses and the analysis before any collection, so that the outcome cannot shape the plan.

**Do:**

1. Create `experiments/entry-observer/PLAN.md` and a machine-readable `plan.json` with the content below.
   - **Hypotheses:**
     - **H1.** Map (b) makes the 18 early-exception misses inclusive under the base policy.
     - **H2.** The Hibernate `PersistentAttributeTransformer#fieldReader` miss stays a miss in (b), because its entries occur before the leaf.
     - **H3.** Map (a) reproduces the frozen inclusiveness within run-to-run variation.
     - **H4.** In (b), the five Spring Security records rescued by the empty-footprint rule get non-empty footprints.
   - **Metrics, per subject and in aggregate, for (a), (b) and (c):**
     - inclusiveness;
     - exact-identity killer recall;
     - mean selected tests;
     - time fraction, using the existing durations;
     - list of recovered and remaining misses;
     - edges added per test;
     - tests whose footprint changes from empty to non-empty.
   - **Comparisons:** (b) versus the constructor-only rule and versus the class baseline on the frozen maps; (c) on the nine overload-aliasing misses.
   - **Population:** the frozen 4,070 records and the existing PIT matrices. PIT is not re-run.
   - **Subjects:** all eight. Priority order: spring-core, Spring Security, Hibernate, Commons Lang, JGraphT, then Quarkus, PetClinic, Flink.
   - **Rules:**
     - A record is *recovered* when its base-policy selection on (b) contains at least one exact-identity killer.
     - Collection failures are reported per test, not dropped.
     - Every outcome is reported, including outcomes against the hypotheses.
2. Compute the SHA-256 of both files. Write them to `experiments/entry-observer/plan.sha256` and commit before T5.

**Accept when:** the files exist, are committed, and the digest file matches.

---

## T1. Entry agent (1 to 1.5 days)

**Goal.** A light Java agent that sets a boolean flag on entry to every production method and exposes `dumpAndReset`.

**Do:**

1. New Gradle module `entry-observer-agent` in `<TOOL_REPO>`. It produces a shaded `entry-agent.jar` with:
   - `Premain-Class`;
   - Byte Buddy (and anything else it bundles) relocated under `<SHADED>`, to avoid clashes with Hibernate enhancement and Mockito-inline;
   - no dependency on the tool's own modules.
2. **Configuration**, via system properties:
   - `entry.productionRoots`: path-separated list of production class directories or jars. This is the primary filter, by `ProtectionDomain.getCodeSource()`.
   - `entry.includes` and `entry.excludes`: same pattern syntax as the JaCoCo agent. This is the secondary filter.
   - `entry.diagDir`: where diagnostics go.
3. **Instrumentation** (Byte Buddy `AgentBuilder`, `disableClassFormatChanges()`, Advice only):
   - instrument all non-abstract, non-native methods, all constructors and type initializers;
   - skip bridge methods, and skip synthetic methods except those named `lambda$*`;
   - include interface default and static methods;
   - never instrument JDK, test or agent classes.
4. **Recording:**
   - At transform time, assign each `(class FQN, method name, descriptor)` a stable `int` ID in a registry.
   - Pass the ID into the advice as a constant (custom Advice mapping). The advice only does `EntryRecorder.hit(id)`, which sets a flag in paged `boolean[]` storage. No logging, no allocation, no locking on the hot path.
   - Put `EntryRecorder` in a separate small jar that the agent appends to the bootstrap class-loader search, as the existing audit `TraceAgent` does. Advice inlined into classes from custom class loaders (Hibernate enhancement, Quarkus) must be able to reach it.
5. **API:** `public static void dumpAndReset(String sessionId, java.nio.file.Path dir)`.
   - Append the entered methods since the last reset to `dir/session_<sanitize(sessionId)>.entries`, one line per method: `classFqn<TAB>methodName<TAB>descriptor`.
   - Then clear all flags.
   - Implement `sanitize` exactly like `SessionFileNames.sanitize`, as a copy, because the agent must not depend on the tool.
   - The race with threads that set flags during the dump is acceptable. Document it as the same race JaCoCo has.
6. **Diagnostics in `entry.diagDir`:**
   - `registry.tsv` (ID, class, name, descriptor);
   - instrumented-class count;
   - transform errors.

   Write them at shutdown and on every 1,000th dump.

**Tests:**
- registry IDs are stable and unique;
- `sanitize` output equals `SessionFileNames.sanitize` on a table of cases, including upper-case letters and `$`;
- `dumpAndReset` writes, appends and resets correctly;
- `jar tf entry-agent.jar` contains no unrelocated `net/bytebuddy`.

**Accept when:** the jar builds, all tests pass, and a hello-world JVM started with `-javaagent:jacoco ... -javaagent:entry-agent.jar` runs and records the expected entries.

---

## T2. Hook in the R2 listener (half a day)

**Goal.** Snapshot entry flags at exactly the JaCoCo dump point, with zero effect when the agent is absent.

**Do:**

1. Add a small helper `EntryHook.dump(String sessionId)` in the tool:
   - resolve the recorder class reflectively, with the bootstrap loader first, then the system loader;
   - cache the lookup;
   - if the class is absent, return silently (no log);
   - otherwise call `dumpAndReset(sessionId, Path.of(resolveExecDir()))`.
2. Call it **immediately after** `dumpJaCoCoData()` in both `JacocoPerTestListener.executionFinished` and `TestLifecycleExtension.afterTestExecution`, with the same `sessionId` used for the `.exec` file.
3. Change nothing else. Keep the diff minimal.

**Tests:**
- All existing tool tests pass unchanged.
- **Without the agent:** run one existing listener test. The set of produced files and their bytes match the run before the change.
- **With a fake recorder class on the classpath:** `dumpAndReset` is called exactly once per leaf, through both paths, with the same session ID as the `.exec` file.

**Accept when:** the above holds and the diff touches only the two call sites and the new helper.

---

## T3. Map builder for (b) and (c) (1 day)

**Goal.** Turn `.entries` files plus the unchanged R2 outputs of the same run into maps (b) and (c), plus diagnostics.

**Do:**

1. New command `build-entry-maps`, in the CLI module or a separate tool. Inputs:
   - the map (a) produced by the unchanged converter from the same run;
   - the directory with the `session_*.entries` files;
   - the per-session JaCoCo XML reports of the same run, which provide the method universe, including descriptors (`<method name=... desc=...>`).
2. **Session mapping.** Map `.entries` file names to map keys with exactly the converter's logic (`extractTestName` and `unsanitizeSessionFileName`). Reuse it, do not reimplement it.
3. **Method universe.** For each class, the universe is the set of `(name, desc)` that JaCoCo reports for that class, covered or not, in any session. Entries outside it are written to diagnostics and not added.
4. **Map (b).** Same JSON schema as R2:
   - `methods = methods(a) ∪ {Class#name of in-universe entries}`;
   - `classes = classes(a) ∪ {classes of those entries}`;
   - metadata and execution identities copied from (a), plus a `derivedFrom` note.

   Map (b) must load through the unchanged production `CoverageMapReader`.
5. **Map (c).** Same structure, but method keys are `Class#name(desc)` for both JaCoCo-covered methods (from the XML) and in-universe entries. The descriptor format must equal PIT's `methodDescription`.
6. **Diagnostics JSON:**
   - per test: added edges, out-of-universe entries;
   - tests whose footprint went from empty to non-empty;
   - totals.

**Tests (on tiny fixtures):**
- (b) is a superset of (a) for every test;
- (b) round-trips through `CoverageMapReader`;
- (c) keys for an overloaded method match a PIT-style descriptor;
- a session with a capital-letter method name maps to the right key;
- out-of-universe entries are reported.

**Accept when:** all tests pass and the command runs on the T4 fixture output.

---

## T4. Synthetic end-to-end fixture (1 day)

**Goal.** Prove, on a project you control, that the observer catches early-exception entries, keeps R2's window, and leaves JaCoCo untouched.

**Do:**

1. Create `experiments/entry-observer/fixture` with one Gradle build. A Maven twin is optional but useful, because Maven uses the `TestLifecycleExtension` fallback path. Use JUnit Platform, JDK 21, the R2 collector and JaCoCo 0.8.13. Production classes:
   - **`Guard.notNull(Object)`:** throws `IllegalArgumentException` on null.
   - **`Service.canDo(Object a)`:** first statement `Guard.notNull(a)`, then some logic. Add an overload `canDo(Object a, Object b)` with the same guard.
   - **`Warmup.init()`:** called from a test class's `@BeforeAll`.
   - **A class with a lambda executed by a test.**
   - **A class that a test only constructs.**
2. Tests:
   - `canDoRejectsNull`: `assertThrows` on `canDo(null)`;
   - `canDoWorks`: normal call of the two-argument overload;
   - a parameterized test with two invocations touching different methods;
   - one test that calls only the lambda path;
   - one that only constructs.
3. A script `run-fixture.sh` that performs three runs and then runs `build-entry-maps`:
   - **(i) baseline:** R2 collector without the entry agent;
   - **(ii) observed:** JaCoCo first, then the entry agent;
   - **(iii) wrong order:** entry agent first, to document the I1 failure mode.
4. `check-fixture.py` asserts:
   - **In (a):** `canDoRejectsNull` has no `Service#canDo` edge. This confirms the JaCoCo gap.
   - **In (b):** it has `Service#canDo`.
   - **In (c):** it has `Service#canDo(Ljava/lang/Object;)Z`, or whatever descriptor applies, and not the two-argument overload.
   - **Window:** `Warmup.init` is attributed to the same session in (a) and (b), i.e. the first leaf after `@BeforeAll`.
   - **Parameterized test:** its entries are the union of both invocations.
   - **Lambda test:** the lambda key is the same in (a) and (b).
   - **Baseline unaffected:** run (i) and the JaCoCo part of run (ii) produce identical per-session probe vectors and identical map (a).
   - **Wrong order is detected:** run (iii) shows a JaCoCo class-ID mismatch, or a missing coverage report, and the script reports it as expected.

**Accept when:** `check-fixture.py` passes on a clean checkout, end to end, with one command.

---

## T5. Pilot on real subjects (1 day, your machine)

**Goal.** Check the two decisive cases and the overhead before any full collection.

**Do:**

1. **spring-core, worked example.** Use the subject setup from the original R2 collection, and add the entry agent after JaCoCo with `entry.productionRoots` set to the spring-core main output. Run the two `GenericConversionServiceTests` killers of `canConvert(Class, Class)`, alone and with their test class. Check:
   - in (a), both killer sessions contain only `GenericConversionService#<init>` for that class, as in the paper;
   - in (b), both contain `GenericConversionService#canConvert`;
   - in (c), both contain `canConvert(Ljava/lang/Class;Ljava/lang/Class;)Z`;
   - JaCoCo probe vectors per session are identical with and without the entry agent.
2. **Hibernate, attribution case.** Run `testDynamicUpdateWithInheritance` with its custom engine. Check that the `PersistentAttributeTransformer#fieldReader` entries are **not** in that test's session.

   Because R2 resets at leaf finish, pre-leaf work lands in the next finishing leaf. That is the expected H2 behaviour.
3. **Overhead.** Run the spring-core test task three times with JaCoCo only and three times with JaCoCo plus the entry agent. Report median wall time and the ratio.
4. Write `experiments/entry-observer/pilot/REPORT.md` with the commands, versions, results and raw outputs.

**Accept when:** all three checks match the expectations above.

**Stop rule:** if the `canConvert` edge is missing, or the JaCoCo vectors differ, stop and investigate before any full collection.

---

## Next phase (separate file after T5)

| Task | Content |
|---|---|
| T6 | Wire the agent into all eight subject builds |
| T7 | Full collection: one pass per subject producing (a), (b), (c), with and without the agent for overhead |
| T8 | Integrity checks against the frozen maps |
| T9 | Evaluation of (a), (b), (c) in `<PKG>` and Java replay of (b) and (c) through the unchanged selector |
| T10 | Package integration and re-verification |
| T11 | Paper subsection |

---

## Amendments (A1 to A7)

Approved for T0 on 2026-10-22. These amendments govern the affected provisions
above; the original specification is retained unchanged.

A1 (naming). The .entries file uses exactly the same stem as the .exec file of the
same path: the caller passes the stem it uses for the .exec file (sanitized in the
listener, raw in the extension). The agent does not sanitize. Note in PLAN that
buildSessionId's 7-hex hash suffix already prevents case-only collisions, so the
unsanitized fallback does not affect the frozen maps.

A2 (H2 restated). "In the same run, entries from pre-leaf work land in the same
sessions as the JaCoCo edges for that work; therefore (b) recovers the Hibernate
miss only if (a) from the same run also does." Test it per run; no expected outcome
beyond that.

A3 (map c). Map (c) evaluates a hypothetical descriptor-aware policy. The harness
passes Class#name(desc) built from PIT methodDescription to the unchanged selector.
State explicitly that this is not the current production workflow (GitChangeDetector
emits Class#name).

A4 (helpers). Do not change visibility in R2 production code. Copy the session-name
logic into the map builder and add a parity test, in the same package, against the
package-private unsanitizeSessionFileName over a table of names.

A5 (method universe). Build the universe once with the JaCoCo Analyzer over the
production class files (same filters as JaCoCo, all methods with descriptors),
not from per-session XML. Report entries outside it. Add acceptance: maps (b) and
(c) are loaded through the production CoverageMapReader and every edge it drops is
reported.

A6 (procedure). T3 acceptance is unit tests on tiny fixtures; "runs on fixture
output" moves to T4. Replace "byte-identical" with "identical content after
normalizing timestamps and session-info metadata, plus identical per-session probe
vectors". Document, do not fix, the non-atomic pair of dumps.

A7 (anonymity). Nothing from this experiment enters the anonymized replication
package unless it passes the existing relocation/redaction pipeline and identity
scan, including paths and user IDs in logs, JSON and reports.

Then do T0 as planned, commit only T0, and stop.
