# STP-PERF-01-CLOSE — delivery and measurement clarification

Closed on branch `research/asm-codex`, starting at
`498ada8831db49ea654af136a72329706a3cb0a0`. No new optimization, production
behavior change, benchmark campaign, CI or Jenkins run was performed in this
close-out. The measurements and correctness runs below are the retained
STP-PERF-01 implementation evidence, not freshly rerun suites.

## 1. Complete contention results

Ten million total calls at every worker count, medians of three fresh-JVM
measurements per configuration. Both control series are included rather than
silently choosing one. Sources: [before](raw/before/summary.json),
[after](raw/after/summary.json). Parallel efficiency is calculated from those
measured medians as `T(1) / (workers * T(workers))`, not estimated from core count.

| Workers | No agent, before (s) | Agent, before (s) | No agent, after (s) | Agent, after (s) | Efficiency before | Efficiency after |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 0.023154708 | 1.389219708 | 0.022681625 | 0.198082250 | 100.00% | 100.00% |
| 2 | 0.012640792 | 2.699448125 | 0.012597416 | 0.137461083 | 25.73% | 72.05% |
| 4 | 0.007077125 | 2.291540209 | 0.007118250 | 0.086010250 | 15.16% | 57.58% |
| 8 | 0.005754834 | 2.269794750 | 0.004696125 | 0.056131833 | 7.65% | 44.11% |
| 16 | 0.013060125 | 2.259855625 | 0.006464292 | 0.078963875 | 3.84% | 15.68% |

The missing single-worker figure is **0.198082250 s**. Sixteen-worker efficiency
is now **15.68%**, versus **3.84%** before. This is an improvement, not linear
scaling. Sixteen workers oversubscribe this ten-logical-CPU machine; the no-agent
controls also lose throughput at sixteen workers. The residual scaling loss
cannot all be assigned to the agent. These are local proxy loops, not Hybris
measurements or proof that any of its 17 failing shards now passes.

Recalculate without running a benchmark:

```bash
python3 verification/stp-perf-01/close.py
```

[Arithmetic JSON](raw/close/arithmetic.json) retains unrounded efficiencies and
predictions; [generated tables](raw/close/tables.md) are the presentation output.

## 2. Where the raw counters live

The valid owned repeat path increments **two** counters:

1. Global per-method occurrences: `MethodCatalog.probes` is a volatile
   `MethodProbe[]`, indexed directly by the injected dense ordinal.
   `MethodProbe.occurrences` is a `LongAdder`. `probe.hit()` increments it on every
   invocation, including hits without valid ownership.
2. Owner-local occurrences: the resolved `TestExecutionContext` or container owns
   a `MethodHitOwner`. Its `pages` field is an
   `AtomicReference<AtomicReferenceArray<Counter>[]>`. Page index is
   `ordinal >>> 8`; the slot is `ordinal & 255`. Each `Counter` contains a
   `LongAdder occurrences` and `AtomicBoolean registered`.

Thus the owner-local structure is a **two-level, lazily allocated ordinal array**,
not one flat eagerly allocated array and not a map keyed by methods. The shared
probe table is flat. The registration `HashMap<MethodIdentity,Integer>` is used
when transforming/registering methods, not on repeated hits. Different method
identities receive different ordinals even when their diagnostic FNV hashes
collide; collision diagnostics and fail-closed semantics are retained.

`RuntimeContextService.recordMethod` resolves leaf/container ownership from
thread-local state before checking owner closure and the ordinal guard. Owner
state is shared by propagated contexts, not stored separately per worker. A new
test/container gets new state; closing it invalidates its cached fast path.

On the steady-state, valid repeat path there is no STP method-map lookup,
`MethodHitEvent` allocation, boxed `Long`/`Integer`, or aggregator monitor entry.
Owner resolution does use Java `ThreadLocal.get()`, whose implementation accesses
the JDK's `ThreadLocalMap`; this is not a method-keyed coverage lookup. First hits
allocate the event and register the counter with the aggregator's map once.
Unsupported/closed-owner hits intentionally still take the diagnostic slow path.

`LongAdder` uses atomic updates and striped cells, not the aggregator's global
monitor. Concurrent increments are not discarded; sums after writers quiesce
include all increments, subject to numeric overflow. `sum()` is not an atomic
snapshot while writers are still incrementing. Existing serialized leaf counts
remain integer-sized; this change does not expand their range.

Initialization is a qualification to “allocation-free”: owner pages/counters are
allocated on first use, and a `LongAdder` can initialize/expand its stripes when
contention first appears, even after that method has previously been seen.
Internal CAS retries and stripe-initialization coordination remain. We claim no
Java monitor or event allocation on the warmed repeat path, not a wait-free
operation or a guarantee of zero allocations on every conceivable repeat.

### What the approximately 18 ns measures

At 100 million calls, subtracting the matched no-agent median gives
**138.13118042 ns/call before** and **18.27108833 ns/call after**. The defensible
coarse decomposition is:

| Measured bucket after optimization | Approximate ns/call |
|---|---:|
| Uninstrumented loop/application work | 2.25448 |
| Added hook dispatch, ordinal lookup, owner resolution, guard and both counters together | 18.27109 |
| Total instrumented loop | 20.52557 |

There are **no separately measured nanosecond figures** for owner resolution,
guard checking or the two counter increments in the retained evidence. Assigning
fractions of 18 ns to them would invent a profile. `runtimeRecordingNanos` now
times first-hit/diagnostic slow paths only and cannot supply that split. A finer
breakdown would require a separately authorized profiling/ablation measurement;
none is represented as having happened here. The counters' cost is included in
the measured residual, not excluded from the performance claim.

## 3. Permanent diagnostic regression and correctness gates

`MethodProbeTest.cachedContainerHitAfterCloseStillDiagnosesEvenIfIdIsReopened`
first populates the guard, closes its container, reopens the same textual ID and
replays the captured old context. It asserts `LATE_EVENT` and
`ASYNC_SETUP_UNSUPPORTED`: it fails if cached membership bypasses the closed-owner
diagnostic. `containerClosedOnAnotherThreadInvalidatesCapturedAndSynchronousCaches`
also verifies invalidation when another thread closes the owner.

These tests are in the production module's normal test source set:
[MethodProbeTest.java](../../stp-runtime/src/test/java/com/sap/oss/smarttestpicker/runtime/MethodProbeTest.java),
executed by `:stp-runtime:test` in the eight-module `--rerun-tasks` run. Their
[retained JUnit XML](raw/implementation/test-results/stp-runtime/TEST-com.sap.oss.smarttestpicker.runtime.MethodProbeTest.xml)
records their successful execution. They are not only verification fixtures.

The completed [implementation gates](IMPLEMENTATION.md) remain:

- PetClinic, 69-test H2 scope at
  `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`: byte-identical fragment
  `0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9`.
- Eight suites: **731 tests, 714 passed, 17 skipped, zero failures/errors**;
  [per-module counts](raw/implementation/suite-counts.json) and
  [console log](raw/implementation/full-suites.log).
- ASM-12 shared cached web context still rejects collection with the closed-owner
  ERROR setup diagnostic and `LATE_EVENT`; ASM-13 parallel execution still rejects
  with the unobserved ForkJoin setup diagnostic. The guard does not convert these
  failures into successful maps. See [gate assertions](raw/implementation/correctness-gates.json).

Paired local PetClinic mapping wall time was **29.983275 s before / 25.184900 s
after**, a single paired observation, not a CI forecast.

## 4. Separate delivery

STP-GENERATED-CLASSES-01 was still uncommitted at close-out start. Its retained
investigation, in-memory fixture/evidence and correction to the PetClinic parity
report were committed separately as
`d966ddccc0b3b806a44bd39888d742dc84b7ff90`
(`Document generated-class provenance and correct PetClinic parity attribution`).
The correction attributes the six additional accessors to the subject's Gradle
AOT build steps, not to proven Maven code-source filtering. Historical counters
were preserved.

The STP-PERF-01 implementation and its retained evidence are delivered together
with this close-out document in the subsequent performance commit. This document
does not embed its own future commit hash; the delivery response supplies it.
Neither commit is pushed. Pre-existing untracked `examples/`,
`verification/maven-asm-shapes/` and `verification/maven-asm-spike/` are excluded
from both commits and left untouched. Raw console whitespace is preserved rather
than rewriting historical logs to satisfy a text whitespace check.

Close-out checks passed: all 729 performance-commit files are below 5 MiB;
Python syntax and JSON/XML parsing; relative report file links; source/document
whitespace checks excluding immutable raw console output; and
`scripts/check-large-files.sh`. The named test results, suite totals and all six
retained copies of the before/after PetClinic fragment were rechecked against the
recorded hash. This was evidence verification, not a rerun of the product suites.

## 5. Derived CI expectation — not tested

Using the requested rounded local overheads, for an original slowdown `N`:

```text
predicted slowdown = 1 + (N - 1) * 18 / 138
predicted duration = old duration * predicted slowdown / N
                  = baseline + (old duration - baseline) * 18 / 138
```

| Assumed original slowdown N | Predicted slowdown | Previously 7h 13m shard | Previously 6h 19m class |
|---:|---:|---:|---:|
| 2x | 1.130435x | 4h 04m 44s | 3h 34m 13s |
| 3x | 1.260870x | 3h 01m 59s | 2h 39m 17s |
| 4x | 1.391304x | 2h 30m 37s | 2h 11m 50s |
| 20x | 3.478261x | 1h 15m 18s | 1h 05m 55s |

These are conditional predictions, not measurements. We do not know which `N`
applies to either long-running case. The calculation assumes unchanged application
work and call count, and that the excess runtime is attributable to the measured
per-entry cost. I/O, GC, changed test outcomes, contention, retries and timeouts
can violate that assumption. Real application work inside a hot loop is not
removed by this optimization. No claim is made that the 17 failing shards are fixed.

### What uninstrumented baseline actually exists

The downloaded `Recipe cxmc JUnit  3_26.txt` is **instrumented**, with an explicit
STP `-javaagent`. It reports `BUILD FAILED in 7h 13m 31s` and
`PLA_12514_Test` with two tests and `22,731.066` seconds. The prediction table uses
the user's rounded 7h 13m and 6h 19m durations, not falsely precise extrapolations.

There are historical scheduler timing files in Downloads:

- `executiontime.properties`, line 1159:
  `de.hybris.platform.catalog.jalo.PLA_12514_Test=326382`;
  SHA-256 `13ff9e4a330ee90a0771087a0dc1e0166c8de94ebe4c9b6e7795d86a488f1620`.
- `executiontime.properties.txt`, line 4314: the same key has value `749952`;
  SHA-256 `bcf1fe232135737ac8ef8724e87961137cb9a61e5665729e175dc60afc6a140a`.

Their units, build/revision provenance and absence of instrumentation were not
established. Neither is a verified matched uninstrumented control for this run,
and neither is used to assign `N`. The log's reference to an agent-side
`merged-executiontime.properties` does not establish that either download was its
input. No matched uninstrumented wall-time baseline for the complete shard was
established in the retained task evidence or the examined downloads.

Consequently, attributing a future CI improvement needs a matched uninstrumented
control as well as the old/new agent comparison: same subject revision, shard
assignment, test scope, agent resources and build configuration. This close-out
records that requirement; it does not run CI or alter the subject.
