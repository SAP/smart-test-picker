# Runtime test suspension

The lifecycle APIs in `RuntimeContextService` support an
external listener whose events revisit one logical test, such as parameter rows
ordered `m1[row0], m2[row0], m1[row1], m2[row1]`. No external listener or JUnit
adapter is changed.

## Contract

- `suspendTest(identity)` requires that identity to be current on this thread.
  It saves the existing context in a thread-local suspended map and removes
  `current`. It does not finish the bucket, close its method owner, or set
  `lastFinished`.
- `resumeTest(identity)` requires no active test, an existing unfinished bucket,
  and a context suspended on this thread. It restores the *same* context and
  method owner without calling `beginTest`. It clears a preceding sibling's
  `lastFinished` marker, as beginning a test does.
- `finishSuspended(identity, result)` closes the saved method owner, finishes
  the bucket through `RuntimeEventAggregator.endTest`, and removes the saved
  context. It neither resumes that identity nor changes a different active
  identity. Finishing the current identity through this API is rejected.

Unknown or foreign-thread suspended contexts fail with `test was not begun`;
finished buckets fail with `test already finished`; resuming while another test
is active fails with `conflicting active test`. A second `beginTest` still fails
with `test already registered`, including while the original test is suspended.
Existing `beginTest`, `endTest`, `finishTest`, and setup collection are unchanged.

The suspended map belongs to the listener thread: another thread cannot resume
or finish its saved context. Wrapped executor work and ForkJoin work submitted
**before suspension** retain the captured context and can still record into its
unfinished bucket. Suspension is not cancellation or a synchronization barrier.
Callers must suspend only when no such work is outstanding. Submissions after
suspension capture no test; resuming does not retroactively give them an owner.

Listeners must eventually resume/end or explicitly finish every suspended test.
A test left suspended at output time is unfinished and projects as
`unmapped/COLLECTION_FAILED`; a passing sibling keeps its coverage and collection
remains complete, subject to the existing independent setup-diagnostic rules.

## Hot-path preservation

The dense-ordinal guard and occurrence counters stay attached to the same
`MethodHitOwner` across suspension. Repeated hits remain coverage-set duplicates
while their existing `LongAdder` occurrence counts increase. There is no new
per-hit map access, allocation, or lock. The new map operations and the
aggregator's synchronized unfinished-bucket check occur only at lifecycle calls.

## Permanent regression coverage

`RuntimeSuspensionTest` in the `stp-runtime` test source set contains 16 tests
covering lifecycle transitions, unfinished-bucket projection, cross-thread rejection,
conflicting-active-test recovery, captured executor/ForkJoin work, owner closure,
and guard/counter preservation. The unfinished-bucket case serializes and reads
the real projector's fragment. The repeat-hit test holds the aggregator monitor
while a worker records an already-seen method, proving that hit does not acquire
that monitor. ForkJoin tests simulate the existing instrumented boundary marker,
as `ForkJoinContextTest` does; they do not change propagation behavior.

## Artifact

The agent version remains `0.3.0-SNAPSHOT` (`stp-agent-0.3.0-SNAPSHOT.jar`);
the Gradle shaded build output is `stp-agent/build/libs/stp-agent.jar`.
No version bump or distribution-specific packaging is part of this change.
Consumers of the new API must use the
newly built snapshot, not an older JAR bearing the same version.

## Local validation (2026-11-12)

The focused command passed all 24 tests (16 new suspension tests and eight
existing `MethodProbeTest` tests):

```sh
./gradlew :stp-runtime:test --tests '*RuntimeSuspensionTest' --tests '*MethodProbeTest' --offline
```

The first focused run exposed a missing simulated instrumentation marker in the
new ForkJoin test fixture. Adding the same marker used by the existing
`ForkJoinContextTest` made the test exercise captured ownership. No production
ForkJoin code was changed to accommodate the test.

The retained Maven research harnesses were run afresh against the artifacts
published by this branch's full rerun into the functional-test Maven repository.
Each gate used a separate local Maven repository, excluding old STP artifacts
from the dependency cache. Artifact provenance is retained with the raw output.

- **Reference Maven application:** revision `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, ASM,
  the same 69-test H2 scope. The production codecs verified assignment=69,
  mapped=69, executable-unmapped=0, positive-non-execution=0. Fragment SHA-256:
  `0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9`.
  Raw occurrence counts remain 3,816 total and 3,079 leaf. Mapping completed
  successfully (21.59 seconds wall time; not a performance comparison).
- **Shared cached web-context fixture:** still emits
  `ASYNC_SETUP_UNSUPPORTED:ERROR` for work captured by the closed first JUnit
  container. `collectionCompleted=false`; mapping and aggregation both exit 1.
- **Parallel execution fixture:** still emits `ASYNC_SETUP_UNSUPPORTED:ERROR`
  for `ForkJoinTask: execution without observed submission`.
  `collectionCompleted=false`; mapping and aggregation both exit 1.
- In both rejection gates, the real assignment validator and joiner report
  `IllegalArgumentException: Fragment collection is not completed`. No external
  map publication was attempted.
- **Hot path:** source comparison against `05d5c3b` found the existing
  `RuntimeContextService` methods from `currentTest` onward byte-identical,
  including recording, propagation, setup and diagnostics. `MethodHitOwner`,
  `MethodProbe`, `RuntimeHooks` and `ForkJoinContexts` are also byte-identical.
  The new repeat-hit regression verifies the retained owner, occurrence count
  and absence of an aggregator-monitor acquisition after resume.

Raw evidence and isolated work directories are retained locally, not added to
the delivery branch. Validation reused the existing reference-application and
rejection-fixture harnesses. No external integration was executed.

The full suite was rerun, not reused:

```sh
./gradlew test --rerun-tasks --offline --continue
```

Result: `BUILD SUCCESSFUL in 8m 42s`, 69 tasks executed. Counts were read from
the fresh JUnit XML files; all modules have zero failures and zero errors.

| Module | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| smart-test-picker-common | 342 | 331 | 11 |
| smart-test-picker-core | 29 | 24 | 5 |
| smart-test-picker | 58 | 58 | 0 |
| smart-test-picker-maven | 161 | 161 | 0 |
| smart-test-picker-cli | 36 | 36 | 0 |
| stp-runtime | 83 | 83 | 0 |
| stp-junit-adapter | 21 | 21 | 0 |
| stp-agent | 52 | 51 | 1 |
| **Total** | **782** | **765** | **17** |

After replacing the test-only engine label with a generic value, the runtime
suite was rerun with `./gradlew :stp-runtime:test --rerun-tasks --offline`:
83 tests passed, zero failures, errors or skips. Production Java sources were
unchanged by that documentation/test-label amendment; the full-suite and
integration results above were not rerun or presented as new measurements.

The 16 additional tests increase the base's 766 tests to 782; no existing test
was removed. Console logs are retained at `build/runtime-suspend-full-suites.log`
and `verification/stp-runtime-suspend-01/full-suites.log`. The repository's
tracked-file size check and `git diff --check` also pass.

Delivery branch: `feature/runtime-suspend-resume`. The commit uses `[skip ci]`
to suppress the pull-request CI test workflow. Automatically triggered analysis
jobs are not part of the local validation results above.
