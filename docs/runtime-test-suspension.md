# Runtime test suspension

The lifecycle APIs in `RuntimeContextService` support an
external listener whose events revisit one logical test, such as parameter rows
ordered `m1[row0], m2[row0], m1[row1], m2[row1]`. External listeners must call these
APIs explicitly; the JUnit adapter does not fold such events automatically.

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
Consumers must use a snapshot that contains these APIs, not an older JAR bearing
the same version.

Historical run results are retained in [the history archive](history/runtime-test-suspension.md).
