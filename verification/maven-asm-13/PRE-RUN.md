# MAVEN-ASM-13 pre-run decisions

Source: 7c4e50fe5ff634cfac934a7912228434e1799bfb, research/asm-codex.

The Maven production tree contains no handling of `junit.jupiter.execution.parallel.*`.
`StpMavenLifecycleParticipant` forces launcher/extension autodetection, not sequential
Jupiter scheduling. `PrepareReactorExecutableMappingMojo` invokes the ASM fork guard,
but fork count is not JUnit in-JVM parallelism. The fixture explicitly supplies the
parallel enabled/default concurrent settings, one reused fork, and two fixed workers.

`JacocoExecutionDataSource.startSession` sets the process-global session id and calls
`reset`. `snapshotAndReset` calls process-global `getExecutionData(true)`. Neither
filters by executing thread. Listener ThreadLocals hold labels, not isolated probes.

New fixture: one Spring web test class, two methods, a real fixed-length HTTP response.
The controller waits on an external latch after flushing the response, before calling
the otherwise unused `PostResponseOnly.distinctiveAfterResponse()` method. No test
directly calls any production method. Synchronization/observation support lives in an
external measurement JAR, not the project's production class directory.

Sequential schedule: request test reads the complete response and returns; the following
test releases the server action and waits for its completion. The trace must prove the
first finish/snapshot and second start precede the distinctive action.

Parallel schedule: the observer holds the following test at entry to JaCoCo's own
`startSession`, after any setup snapshot, until the server action has returned. The
requesting test releases the action after reading the complete response and remains
active until the following test finishes. Thus a real STP start reset occurs during
another test's execution. No observer calls reset or changes production routing.
ASM receives the equivalent gate at its `beginTest` entry. The actual timeline and
sessions, not the intended schedule, determine the conclusions.

This is a controlled attainable interleaving, not a prevalence estimate or a claim
that every unconstrained parallel run has this ordering. One container deliberately
isolates the scheduling axis from ASM-12's closed-container reuse failure. No claim
about parallel test classes or multiple Spring contexts will follow from this fixture.
