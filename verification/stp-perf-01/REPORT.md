# STP-PERF-01 — pre-change gate result

> Historical Step 0 decision, superseded by the user's amended gate. The original
> measurements and decision below are preserved. Implementation and fresh gates
> are recorded in [IMPLEMENTATION.md](IMPLEMENTATION.md); the former stop decision
> is not the current task status.

## Decision: stop at Step 0; no production implementation

The requested **superlinear single-thread growth is not demonstrated**. The measured
agent overhead is large but approximately linear in call count. The superlinear
premise is wrong as a characterization of this proxy's measured scaling, so the
task's explicit condition for proceeding is not met.

Contention does degrade fixed-work throughput: every multi-worker agent median is
slower than its one-worker reference. However, time peaks at two workers and then
plateaus; it does not increase monotonically from 1 through 16 workers.

This does **not** refute the narrower hypothesis that per-call recording is expensive.
The measurements support that hypothesis strongly. A high constant cost per call
naturally produces linear growth; superlinearity is not required for a worthwhile
optimization. Nevertheless, this task explicitly says to implement nothing if the
single-thread superlinear premise fails. That stop rule was followed, without
silently relaxing it after seeing the data.

## Repository and environment

- Checkout: `/Users/D061177/work/moje/smart-test-picker`.
- Branch: `research/asm-codex`.
- Initial and final HEAD: `498ada8831db49ea654af136a72329706a3cb0a0`.
- Agent: existing unchanged `stp-agent/build/libs/stp-agent.jar`, version
  `0.3.0-SNAPSHOT`, SHA-256
  `dfa69a8afe2689b13e8ccecd9ee26817f252aa354485e4ffa37279e7ded981cc`.
  This matches the binary in [the retained parity provenance](../stp-petclinic-parity-01/raw/provenance.txt).
  No new agent build or release binary was needed or produced.
- Machine: MacBookPro18,2, ARM64, 10 logical CPUs, 32 GiB memory.
- JVM: SapMachine 21.0.13.0.3; `-Xms256m -Xmx256m -XX:+UseG1GC`.
- JUnit Jupiter and Platform 6.0.3; local cached dependency hashes are retained in
  [environment.txt](raw/before/environment.txt). No network resolution or CI run.
- Benchmark sources compiled with `javac --release 17` into a disposable directory.
- Pre-existing modifications to the parity report and untracked generated-class,
  spike, shape and example directories were preserved. This task adds only
  `verification/stp-perf-01/`. No commit or push was performed.

## Step 0 — measurement design and validity

The [protocol](PROTOCOL.md) was written before running the matrix. These are **local
proxies**, not a reproduction of Hybris or its six-hour test class.

Each of the 48 samples starts a fresh JVM and executes exactly one Jupiter test
through the real Platform launcher. There are three repetitions of eight
configurations under both control and agent modes. Samples run serially, with
agent/control launch order alternated by repetition.

Only `perf.subject.HotMethod` is instrumented. Its `touch(long)` computes a recurrence;
the next call depends on the previous result, and the final result is printed and
compared between agent and control. This prevents dead-code elimination of an empty
or unused loop. There is no explicit no-inline option; normal JVM optimization is
allowed in both modes. Each JVM performs one million warmup calls in the same test
before timing the requested work.

For contention, exactly ten million measured calls are divided equally among
1/2/4/8/16 platform threads. Thread creation and context wrapping are outside the
timed region. Workers wait on a start latch, call the same production method,
and finish before the owning test ends. Agent workers use the existing
`RuntimeHooks.wrap`, so this measures concurrent writes to **one valid owner**,
not unowned work or concurrent JUnit tests. The measured window includes latch
release and joins but no application synchronization inside the called method.

Every agent sample was checked for:

- exactly `N + 1,000,000` raw hits (measured calls plus warmup), all attributed to
  the one test, with one unique method hit;
- zero transformation errors, zero agent errors, and no unattributed events;
- a complete schema-v3 fragment with exactly
  `maven:.::perf.BenchTest#measure -> perf.subject.HotMethod#touch(J)J`;
- successful decoding and exact identity/method-set assertions through the
  unchanged production codec, for all 24 agent samples.

All 48 JVMs exited zero; each executed one successful test. Agent/control result
checksums matched for every paired sample. See [codec-verification.log](raw/before/codec-verification.log).

### Single-thread hot loop

Measured-loop wall times are medians of three independent JVMs. Ratios are ratios
of those medians, not whole-process timings.

| Calls | No agent (s) | Agent (s) | Agent / control | Agent ns/call |
|---:|---:|---:|---:|---:|
| 1,000,000 | 0.0024985 | 0.1388638 | 55.58x | 138.86 |
| 10,000,000 | 0.0230705 | 1.5571012 | 67.49x | 155.71 |
| 100,000,000 | 0.2263784 | 14.0394965 | 62.02x | 140.39 |

Tenfold increases in calls produce **11.2132x then 9.0164x** agent time. Across
100x calls, time grows approximately 101.10x; the endpoint log-log exponent is
**1.002381**, essentially linear. Per-call cost rises at the middle point and then
falls again, rather than showing sustained growth. The first 11.21x interval alone
is not sufficient evidence of superlinear scaling when the next interval and the
full-range result contradict that characterization.

These are three samples per point, not a proof of an asymptotic complexity class.
No confidence claim beyond the recorded ranges is made. The result does not
establish superlinear behavior at unmeasured call counts or on other machines.

### Contention — ten million total measured calls

| Threads | No agent (s) | Agent (s) | Agent / control | Agent time / one-worker agent time |
|---:|---:|---:|---:|---:|
| 1 | 0.0231547 | 1.3892197 | 60.00x | 1.0000x |
| 2 | 0.0126408 | 2.6994481 | 213.55x | 1.9431x |
| 4 | 0.0070771 | 2.2915402 | 323.80x | 1.6495x |
| 8 | 0.0057548 | 2.2697948 | 394.42x | 1.6339x |
| 16 | 0.0130601 | 2.2598556 | 173.03x | 1.6267x |

The control benefits from more workers through eight, whereas the agent loses
throughput relative to one. Sixteen workers oversubscribe this 10-CPU machine;
the control also slows. Agent times are not monotonic with thread count, and the
394x ratio at eight workers is relative to a very cheap parallel control, **not a
predicted Hybris slowdown**.

These results are consistent with the already established shared-monitor recording
path. The benchmark does not isolate the monitor's exclusive share of time from
allocations, lookup, metrics, GC, JIT effects, or scheduling; it must not be cited
as a profiler proving that one particular lock alone accounts for the overhead.

[tables.md](raw/before/tables.md) preserves min/median/max, per-call costs and
whole-process medians for every configuration. [samples.json](raw/before/samples.json)
contains all individual timings and checksums; [summary.json](raw/before/summary.json)
contains the aggregates. Startup, warmup and shutdown serialization are excluded
from the loop measurement but included in the separately recorded process times.

## Steps 1–4 — explicitly not performed after the failed gate

| Requirement | Status |
|---|---|
| First-hit guard and collision-safe key design | NOT IMPLEMENTED — Step 0 stop condition |
| Dense ordinal vs hash-set decision | NOT TAKEN — no injected constant or diagnostic ID change |
| Occurrence-counter contract change | NONE — `rawMethodHits` still counts occurrences |
| PetClinic fragment equality | NOT RUN — no changed collector to validate |
| All eight module suites | NOT RUN — no production change; no historical counts claimed as fresh |
| ASM-12 shared-context rejection | NOT RUN — unchanged production code, not fresh runtime proof |
| ASM-13 parallel/ForkJoin rejection | NOT RUN — unchanged production code, not fresh runtime proof |
| Post-change benchmark matrix | NOT RUN — no implementation |
| Fresh before/after PetClinic mapping time | NOT RUN — stopped before that stage |

The requested PetClinic reference SHA-256 remains
`0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9`;
this is **the historical oracle**, not a fresh passing correctness gate.

For completeness, the requested before/after ratio table is explicitly incomplete:

| Case | Calls | Threads | Before agent/control | After |
|---|---:|---:|---:|---|
| Hot loop | 1,000,000 | 1 | 55.58x | NOT RUN |
| Hot loop | 10,000,000 | 1 | 67.49x | NOT RUN |
| Hot loop | 100,000,000 | 1 | 62.02x | NOT RUN |
| Contention | 10,000,000 | 1 | 60.00x | NOT RUN |
| Contention | 10,000,000 | 2 | 213.55x | NOT RUN |
| Contention | 10,000,000 | 4 | 323.80x | NOT RUN |
| Contention | 10,000,000 | 8 | 394.42x | NOT RUN |
| Contention | 10,000,000 | 16 | 173.03x | NOT RUN |

No raw hash was treated as a dense ordinal or assumed to identify only one method.
No guard was inserted before owner resolution, inside the event-receiving `record`
method, or anywhere else. Consequently no claim is made that this task has solved
collision handling, owner-boundary reset, closed-container diagnostics, or the
occurrence-count contract. Those remain mandatory design/validation constraints
if implementation is subsequently authorized under a revised measurement gate.

## Step 5 — limits and consequence

The proxy establishes a large STP per-call multiplier and harmful concurrent
recording overhead. It does not establish the proposed **superlinear** single-thread
mechanism, the cause of the long Hybris shard, or why any cache assertion failed.
Slowness and test failures are separate observed symptoms. Real application work
inside a slow loop would not be accelerated by reducing STP recording cost.

A first-hit optimization might reduce this measured constant factor and contention,
but no such improvement was implemented or measured here. **None of the 17 shard
failures is claimed fixed.** Testing that claim requires the real suite and is
outside this local-only task. No quarantine, scope change, selector change,
JaCoCo change, build-tool integration change or CI action occurred.

## Reproduction, helper correction and delivery

From the repository root:

```bash
python3 verification/stp-perf-01/run.py
python3 verification/stp-perf-01/analyze.py
java -cp stp-agent/build/libs/stp-agent.jar \
  verification/stp-perf-01/VerifyFragments.java \
  verification/stp-perf-01/raw/before
```

`run.py` refuses to overwrite `raw/before`; use a separate disposable copy of the
measurement directory for a rerun. `BENCH_M2` may identify a cache containing the
exact pinned JUnit dependencies. No Maven build, undocumented STP dependency install,
or Jenkins plugin is involved. Every launched JVM's exact command, console log,
exit code, and (for agent runs) fragment and diagnostic are retained below `raw/before/`.

The first post-run verification helper compared production `MethodIdentity` objects
against strings. Its assertion failed despite the displayed expected coverage;
an intermediate set-copy change did not address that type mismatch. The helper
was corrected to compare actual `MethodIdentity` values, and all 24 fragments passed
without rerunning or changing the benchmark outputs. The initial helper error is
retained in [codec-verification-initial-helper-error.log](raw/before/codec-verification-initial-helper-error.log).
This was a measurement-helper defect, not a collector or benchmark failure.

Implementation status: **STOPPED BY THE SPECIFIED STEP 0 GATE**. Measurements and
this report are delivered; implementation and end-to-end correctness acceptance
are not claimed complete.
