# STP-PERF-01 — amended gate and owner-local first-hit implementation

Status: **COMPLETE — all three correctness gates passed.** The local benchmark
demonstrates a substantial reduction in recording overhead. No CI run, no Hybris
failure-resolution claim.

## Decisions

The amended gate accepts constant per-call cost and poor contention scaling. The
retained control column is present for every worker count: 0.023154708,
0.012640792, 0.007077125, 0.005754834 and 0.013060125 seconds for 1/2/4/8/16
workers respectively. These are medians at ten million total calls, not missing
controls. See the unchanged [baseline report](REPORT.md).

Choose dense ordinals (option B): each injected long constant now denotes one
JVM-local MethodIdentity, never an FNV hash bucket. Registration keeps the FNV hash
for diagnostics, preserves collision detection and its incomplete-fragment
semantics, and assigns different ordinals even when hashes collide. Diagnostic
method IDs remain fnv1a64-v1; `injectedProbeId` explicitly documents the new internal
constant. No agent-attachment, scope, build integration or selector change.

Resolve thread-local leaf/container ownership and lifecycle first; only a valid,
open owner can use its ordinal-indexed first-hit state. Captured contexts share
that state across threads. Each new test/container owns new state. Closing an owner
invalidates the fast path, including captured work on another thread; diagnostic
events for closed, unknown or unsupported ownership are never deduplicated.

Owner state uses lazily published pages, avoiding an array proportional to the
entire method universe for every test. Repeat hits perform array/atomic reads and
counter updates without entering the aggregator monitor, hashing an event, looking
up a map or allocating an event. Page/counter publication and LongAdder stripe
initialization allocate only on initialization/initial contention, not on the
steady-state repeat path. The first valid hit constructs an event and registers
its occurrence counter with the aggregator.

Choose occurrence-counter preservation (option A): rawMethodHits continues to
count every invocation, including repeated and unattributed hits. LongAdders keep
global per-method and per-leaf counts outside the global monitor. Unique coverage
sets and diagnostics' hash keys retain their meanings. Historical occurrence
evidence is not rewritten. `runtimeRecordingNanos` now measures first-hit and
diagnostic slow paths only, explicitly labelled by `runtimeRecordingTiming`; it
must not be compared with the historical all-calls timing as total runtime cost.

The occurrence-contract audit is retained in
[counter-consumers.txt](raw/implementation/counter-consumers.txt). In particular,
RuntimeEventAggregatorTest asserts two raw hits; MAVEN-ASM-11 Audit reconciles
observed HIT callbacks with global and leaf counters; its report records
3,816 = 3,079 leaf + 737 setup writes. The parity report also records 3,816.
Those historical measurements remain intact. A fresh observer must hook
`recordMethod`, not `record`, since the latter is now the diagnostic/legacy slow
path. First-hit setup writes are intentionally fewer than occurrences; the global
raw counter still counts every invocation. MethodEntryTransformationTest normalizes
the volatile timing field rather than asserting a historical numeric value.

## Correctness gates

### PetClinic — PASS

Upstream commit `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, unchanged sources,
the existing H2 69-test measurement overlay, Maven 3.9.15 and SapMachine 21.0.13.
The existing parity harness was reused in isolated task workspaces/repositories;
the baseline uses the retained original shaded agent, not a reconstructed version.
Normal dependency downloads occurred before the paired timing runs.

Both agents, in both their initial and paired reruns, produced exactly:

```text
0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9
```

All 69 tests passed. Production codecs and join/accounting verification accepted
assignment=69, mapped=69, executable-unmapped=0, positive-non-execution=0.
The diagnostic still reports 23 transformed classes, 116 instrumented methods,
3,816 global raw hits and 3,079 leaf raw hits, with no agent errors. No baseline
adjustment, subject edit, scope change or selector change was made.

See [before](raw/implementation/petclinic-before/accounting.log),
[after](raw/implementation/petclinic-after/accounting.log),
[fresh gate assertions](raw/implementation/correctness-gates.json), and the retained
fragments/diagnostics under each run's `stp/` and `repeat/` directories.

### ASM-12 and ASM-13 — PASS (expected collection rejection)

The existing fixtures and production codecs were reused. Disposable observer
copies hook `recordMethod` rather than the former `record` entry; they do not
change the fixture, synchronization gates or ownership mechanism.

* Shared cached web context: both classes observed the same application-context
  identity, startup timestamp and server port. The second HTTP worker still
  captured the first, now closed, JUnit container. The main-thread positive
  controls observed their active leaf contexts. Result: one `LATE_EVENT` and
  `ASYNC_SETUP_UNSUPPORTED:ERROR` naming `PingController#ping()Ljava/lang/String;`
  and the closed `example.shared.AFirstTests` container.
* Parallel fixture: `ASYNC_SETUP_UNSUPPORTED:ERROR` still names
  `ForkJoinTask: execution without observed submission` for JUnit's
  `ForkJoinPoolHierarchicalTestExecutorService$ExclusiveTask`. Two `NO_ACTIVE_TEST`
  observations remain. This is the existing incidental rejection, not a new
  parallel guard.

Both runs exited 1 in mapping and aggregation. Both fragments have completed=false;
the assignment validator and joiner reject them with
`IllegalArgumentException: Fragment collection is not completed`. Maven finalization
reports `Cannot finalize ASM mapping evidence: Fragment collection is not completed`.
No map publication was attempted (local validation only).

Evidence: [ASM-12 validation](raw/implementation/asm12/validation.json),
[context reuse](raw/implementation/asm12/context-reuse.tsv),
[owner observations](raw/implementation/asm12/context.tsv),
[ASM-13 validation](raw/implementation/asm13/validation.json),
[parallel diagnostics](raw/implementation/asm13/stp/asm-agent-diagnostic.json).

### Full eight-module suites — PASS

The full `--rerun-tasks --continue` run finished **BUILD SUCCESSFUL in 14m 30s**, with
68/68 tasks executed. The complete console is retained in
[full-suites.log](raw/implementation/full-suites.log). Counts below are from this
run's retained JUnit XML, not historical counts.

| Module | Tests | Passed | Skipped | Failures/errors |
|---|---:|---:|---:|---:|
| smart-test-picker-common | 334 | 323 | 11 | 0 |
| smart-test-picker-core | 29 | 24 | 5 | 0 |
| smart-test-picker-maven | 151 | 151 | 0 | 0 |
| smart-test-picker | 58 | 58 | 0 | 0 |
| stp-runtime | 55 | 55 | 0 | 0 |
| stp-junit-adapter | 21 | 21 | 0 | 0 |
| stp-agent | 47 | 46 | 1 | 0 |
| smart-test-picker-cli | 36 | 36 | 0 | 0 |
| **Total** | **731** | **714** | **17** | **0** |

Compared with 721/704/17, eight runtime tests and two agent tests were added; no
module lost tests or passing cases, and skips are unchanged. See
[counts](raw/implementation/suite-counts.json) and
[full-suite assertions](raw/implementation/final-suite-check.log).

Focused runtime/agent runs also passed; new permanent tests
cover forced collisions both at registration and through injected bytecode,
exact occurrence counts, owner sharing, concurrent page growth, repeat hits while
the aggregator monitor is held, and closure/reopening across threads.

Earlier focused-test attempts retained in this directory failed because the new
test used a null TestResult and omitted an assertion import. Those test-code
errors were corrected; the passing rerun is `focused-final-tests.log`.

## Performance after the change

The exact Step 0 matrix was repeated: three fresh JVMs per configuration/mode,
same 256 MiB heap, warmup, checksums, JUnit scope and fixed total contention work.
No other build ran concurrently with the after matrix. All 48 processes succeeded;
all 24 agent fragments passed the unchanged production codec and exact edge-set
checks. Global and per-leaf raw counts equal N + 1,000,000 in every sample.

| Case | Calls | Workers | Before control s | Before agent s | Before ratio | After control s | After agent s | After ratio | Agent speedup |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hot | 1,000,000 | 1 | 0.002498 | 0.138864 | 55.58x | 0.002549 | 0.021079 | 8.27x | 6.59x |
| hot | 10,000,000 | 1 | 0.023071 | 1.557101 | 67.49x | 0.022737 | 0.208711 | 9.18x | 7.46x |
| hot | 100,000,000 | 1 | 0.226378 | 14.039496 | 62.02x | 0.225448 | 2.052557 | 9.10x | 6.84x |
| contention | 10,000,000 | 1 | 0.023155 | 1.389220 | 60.00x | 0.022682 | 0.198082 | 8.73x | 7.01x |
| contention | 10,000,000 | 2 | 0.012641 | 2.699448 | 213.55x | 0.012597 | 0.137461 | 10.91x | 19.64x |
| contention | 10,000,000 | 4 | 0.007077 | 2.291540 | 323.80x | 0.007118 | 0.086010 | 12.08x | 26.64x |
| contention | 10,000,000 | 8 | 0.005755 | 2.269795 | 394.42x | 0.004696 | 0.056132 | 11.95x | 40.44x |
| contention | 10,000,000 | 16 | 0.013060 | 2.259856 | 173.03x | 0.006464 | 0.078964 | 12.22x | 28.62x |

These are medians of three, and speedups compare agent elapsed times. The original
control values remain visible rather than silently reusing today's controls for
yesterday's ratios. Sixteen workers oversubscribe the 10-CPU machine and have high
control variance. The optimized path scales through eight workers but is not
perfectly parallel. The approximately 20–21 ns/call single-thread cost is not zero;
it includes owner checks, ordinal lookup and preserved occurrence counters.

The warmed, sequential PetClinic mapping pair took **29.983275 s before** and
**25.184900 s after** (16.0% less wall time, 1.19x speedup). This is one pair, not
a statistical claim. The initial setup runs are also retained but excluded from
that comparison: they include first-time dependency resolution, and the original
baseline run briefly overlapped a focused build. No such overlap occurred in the
paired timing or after benchmark matrix.

Sources: [all before/after cells](raw/implementation/comparison.md),
[after ranges](raw/after/tables.md), [after samples](raw/after/samples.json),
[codec checks](raw/after/codec-verification.log),
[PetClinic paired timing](raw/implementation/petclinic-timing.json).

## Scope and limits

Production changes are confined to `stp-agent` (MethodCatalog, method-entry
injection constant, AgentRuntime, AgentMetrics and diagnostic labels) and
`stp-runtime` (MethodProbe, MethodHitOwner, TestExecutionContext,
RuntimeContextService and RuntimeEventAggregator). Permanent regressions are in
MethodProbeTest and MethodEntryTransformationTest. The agent README documents
the affected diagnostic contract. All other task additions are local verification
harnesses, the report and retained evidence.

These local proxies support a narrower claim: STP's repeated recording work was a
large multiplier, and removing the global monitor from valid repeat hits removes
that source of cross-thread serialization. A hot loop doing expensive application
work will still do that work. Cheap-method control ratios are not predictions of
Hybris shard speedups. Instrumentation remains one-time at class load; the change
optimizes subsequent recording, not repeated class transformation.

The 17 shard failures are **not proven fixed**. Timing/event-count failures and
recording overhead are distinct observations; only the real suite can establish
which failures disappear. No CI, Hybris, quarantine, always-run policy, JaCoCo,
selector, Jenkins, scope policy or build-tool integration was changed. No inline
self-disabling probes or retransformation were added.

## Reproduction and retained state

Initial and final HEAD: `498ada8831db49ea654af136a72329706a3cb0a0`, branch
`research/asm-codex`. Unrelated pre-existing work is preserved. Changes remain
uncommitted at the time of this validation; no commit or push was requested in
STP-PERF-01 or its amendment. The subsequent [close-out](CLOSE.md) records delivery,
the complete contention series, counter qualifications and conditional CI predictions.
No CI or external Jenkins run was performed.

Measured changed-agent SHA-256:
`735d70c6135e1b503c1aebedde9291f4b4eb2de92a214a3e8c041a18f3ab095f`.
Full-rerun archive SHA-256:
`f8f1d65852f40e859fdb0dc4ca9e4208deee3e7822abddf3791bf68849063499`.
Their 2,011 archive entries have byte-identical contents; only archive packaging
differs. [The entry-by-entry comparison](raw/implementation/agent-provenance.json)
establishes that the measured artifact and fully retested artifact contain the
same code/resources. Component versions remain `0.3.0-SNAPSHOT`.

Relative documentation links, `git diff --check`, Python syntax checks and the
repository's tracked large-file check passed. All retained task evidence files,
including the new test-result XML files, are below 5 MiB. Remaining blocker: none.
The baseline report is historical; this document records the amended gate.

```bash
# Existing isolated Gradle home avoids the user's unrelated global init script.
GRADLE_USER_HOME="$PWD/verification/stp-perf-01/.work/gradle-home" \
  ./gradlew publishStpToIsolatedRepository
python3 verification/stp-perf-01/validate.py petclinic-before
python3 verification/stp-perf-01/validate.py petclinic-after
python3 verification/stp-perf-01/repeat-petclinic.py
BENCH_PHASE=after python3 verification/stp-perf-01/run.py
BENCH_PHASE=after python3 verification/stp-perf-01/analyze.py
python3 verification/stp-perf-01/validate.py asm12
python3 verification/stp-perf-01/validate.py asm13
python3 verification/stp-perf-01/evidence.py
PATH="/Users/D061177/Programs/apache-maven-3.9.15/bin:$PATH" \
GRADLE_USER_HOME="$PWD/verification/stp-perf-01/.work/gradle-home" \
  ./gradlew :smart-test-picker-common:test :smart-test-picker-core:test \
  :smart-test-picker-maven:test :smart-test-picker:test :stp-runtime:test \
  :stp-junit-adapter:test :stp-agent:test :smart-test-picker-cli:test \
  --rerun-tasks --continue --console=plain
python3 verification/stp-perf-01/finish.py
```

The scripts intentionally refuse existing output directories. Baseline binary
`.work/agent-before.jar` has SHA-256
`dfa69a8afe2689b13e8ccecd9ee26817f252aa354485e4ffa37279e7ded981cc`.
`.work/petclinic-source` is an upstream clone containing the pinned commit. Paths
to Maven 3.9.15 and cached Gson/ASM dependencies are explicit in `validate.py`.
Every actual Maven command, generated harness, toolchain and source-diff check is
retained with its run; bulk checkouts, repositories and agent binaries stay ignored
under `.work/`, not in the evidence directory.
