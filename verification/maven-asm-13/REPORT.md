# MAVEN-ASM-13 — JaCoCo attribution across HTTP and parallel test boundaries

## Result

**JaCoCo silently misattributes delayed HTTP work in the sequential case and silently loses it in the controlled parallel case. Both runs produce complete, accepted maps.** The distinctive production method has no other caller and neither test calls production code directly.

| Measurement | Distinctive method or class ownership | Tests | Collection / local join |
| --- | --- | --- | --- |
| JaCoCo sequential | Method assigned to the following test, not the requesting test | Both PASS | Complete / accepted |
| JaCoCo parallel | Method absent from every saved test and setup session; class absent from the map | Both PASS | Complete / accepted |
| JaCoCo parallel fresh repeat | Same destructive loss | Both PASS | Complete / accepted |
| ASM sequential | Class retained in the still-open JUnit container's setup scope, not either leaf | Both PASS | Complete / accepted |
| ASM parallel | HTTP hits have no captured owner; an unsupported ForkJoin setup diagnostic makes collection incomplete | Both PASS | Incomplete / rejected |

A JaCoCo in-JVM parallel guard is warranted. It would prevent the demonstrated overlapping-reset failure, but **would not solve sequential asynchronous work escaping its requesting test's window**. No guard or production change was made.

## Source and environment

- Repository: `research/asm-codex`, initial source HEAD `7c4e50fe5ff634cfac934a7912228434e1799bfb`.
- Local measurements on 2026-11-04, no Jenkins.
- New [http-reset-boundary fixture](../../smart-test-picker-maven/src/test/fixtures/http-reset-boundary/), not the ASM-12 fixture. All five fresh workspaces use frozen fixture revision `98405df1227b03d7c61d6e37de1c9d4a2f329be7`; this identifies a disposable fixture Git repository, not the STP source. All retained source diffs are empty.
- Maven 3.9.15, SapMachine JDK `21.0.13.0.3`, Java release 17; Spring Boot 4.1.0, embedded Tomcat 11.0.22, Surefire 3.5.6, Jupiter/Platform 6.0.3. Actual toolchain output and resolved test classpaths are retained per run.
- **JaCoCo agent 0.8.15, the same version as MAVEN-ASM-10.** The fixture's own prepare-agent supplies it. STP's per-test XML generator still uses core/report 0.8.13, as pinned in the common component, also unchanged from ASM-10. The independent raw-session oracle uses core 0.8.15. The STP artifacts tested are `0.3.0-SNAPSHOT`; this task did not rebuild or rerun historical `0.1.0` evaluation binaries.
- STP artifacts resolve through the existing functional-test Maven repository and the task-local dependency repository retained from ASM-10. This is not a cold-cache resolution test.

## 1. Fixture and ordering controls

`BoundaryTests` contains exactly two test methods:

```text
maven:.::example.boundary.BoundaryTests#aRequest
maven:.::example.boundary.BoundaryTests#bFollowing
```

`aRequest` makes the only HTTP request, reads the full two-byte `OK` response, checks status 200 and closes the client connection. It never invokes an application method directly. `bFollowing` only waits/signals external support latches and invokes no application code. There is no MockMvc or direct-call controller control.

The real Spring controller writes a fixed `Content-Length: 2`, flushes the response, and then blocks on `RELEASE_LATE` before calling:

```text
example.boundary.PostResponseOnly#distinctiveAfterResponse()I
```

That method returns the distinctive constant `41327`; nothing else calls or touches this class. The external support verifies the returned value and records it on the actual Tomcat worker. Its release signal is causally after the client has consumed the complete response. Synchronization uses latches with bounded 30-second waits, not sleeps or timing guesses.

The [Gates](Gates.java) and [BoundaryObserver](BoundaryObserver.java) classes live in a separate disposable measurement JAR outside the production class-analysis universe. They supply synchronization and observations, not application coverage. The agent instruments only STP callback methods in memory: it never invokes JaCoCo reset/dump itself, changes STP routing, or transforms application/framework methods. Positive observation controls are the paired actual listener/data-source callbacks and the externally verified method return. They do not add a direct production call to either measured identity.

There is one Spring test class/container, deliberately separating this experiment from ASM-12's closed-container reuse problem. Sequential method order is alphabetical. In parallel mode, `@Execution(CONCURRENT)` allows the ordered class's methods to run concurrently; the trace, not the annotation alone, proves two executing JUnit workers.

### Sequential schedule

The request test reads the response and returns. The following test releases the server action and waits for it to return. The retained JaCoCo trace proves:

| Trace row | Event |
| ---: | --- |
| 16 | Client has read the complete response |
| 19–21 | Request test's snapshot/reset and finished callback complete |
| 23–24 | Following test's startSession/reset completes |
| 29 | Following test releases the server latch |
| 32 | Distinctive method returns 41327 on `http-nio-auto-1-exec-1` |
| 37–39 | Following test's snapshot/reset and finished callback complete |

See [sequential events](raw/jacoco-sequential/events.tsv). This is deterministic cross-window execution, not merely a response-flush timestamp.

### Parallel schedule

The observer holds the following test at entry to **STP's existing** `JacocoExecutionDataSource.startSession`, after any setup snapshot, until the distinctive server method has returned. The requesting test releases the server after reading the response and stays active until the following test finishes. The resulting reset is STP's own reset, not an injected operation.

ASM receives the equivalent gate at `RuntimeContextService.beginTest` entry. This selects a reproducible possible interleaving; it is not a claim about the frequency or default ordering of an unconstrained suite. The production listener's callbacks remain on their real JUnit worker threads.

## 2. The reset-boundary oracle

[SessionOracle.java](SessionOracle.java) reads **all original `session_*.exec` files**, including setup sessions, using `ExecFileLoader` and `Analyzer` against the retained original compiled production classes. It records method counters, covered methods, authoritative identity/setup sidecars and embedded session labels. This is independent confirmation from raw execution bytes, not just a check of the final map.

In the sequential run:

| Session file | Distinctive method covered instructions | Covered method count |
| --- | ---: | ---: |
| `session_BoundaryTests#a~Request_55ff023.exec` | 0 of 2 | 0 |
| `session_BoundaryTests#b~Following_7535c05.exec` | 2 of 2 | 1 |
| `session_setup_2a954237.exec` | 0 of 2 | 0 |

**Answer: the following test — cross-boundary bleed.** Its session and fragment contain `PostResponseOnly#distinctiveAfterResponse()I` despite that test executing no production code. The requesting test contains the earlier part of `BoundaryController#late`; the following session contains its continuation and the distinctive method.

Evidence: [raw-session oracle](raw/jacoco-sequential/session-oracle.json), [original sessions](raw/jacoco-sequential/jacoco/), [generated XML](raw/jacoco-sequential/jacoco-xml/), [fragment](raw/jacoco-sequential/stp/coverage-fragment-v3.json), [production-codec accounting](raw/jacoco-sequential/accounting.json).

The oracle also demonstrates observability: it finds the distinctive method in exactly one real session in this positive sequential case, so its empty parallel result is not an untested decoder or wrong class-directory assumption.

### ASM contrast on the same sequential fixture

The HTTP worker has no active leaf test, but retains captured container `example.boundary.BoundaryTests`, which remains open across both methods. The observer records the distinctive method hit with `active=Optional.empty` and `open=true`. ASM puts `PostResponseOnly` in the `CONTAINER` setup scope affecting that class; both leaves are `PASS / COLLECTED_EMPTY`.

The setup schema stores covered classes, not a per-test method assignment. The runtime hit proves the method was observed, while the fragment proves the retained class-level setup ownership. It is not precise attribution back to `aRequest`, but neither is it attribution solely to `bFollowing` or global loss. Collection and production join succeed. All unattributed counters are zero and no ERROR setup diagnostic is emitted.

Evidence: [ASM sequential events](raw/asm-sequential/events.tsv), [agent diagnostic](raw/asm-sequential/stp/asm-agent-diagnostic.json), [fragment](raw/asm-sequential/stp/coverage-fragment-v3.json).

## 3. In-JVM parallel execution

### Pre-run production audit

The [pre-run decision record](PRE-RUN.md) was written before execution. The Maven production tree does not handle `junit.jupiter.execution.parallel.*`. `StpMavenLifecycleParticipant` forces listener and extension autodetection, not sequential Jupiter execution. The prepared mapping path invokes the ASM **fork** guard, but that guard does not prohibit concurrent test methods inside one fork. JaCoCo has no corresponding parallel collection guard in this path.

These settings reached the actual forks, as retained in Surefire XML:

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.config.strategy=fixed
junit.jupiter.execution.parallel.config.fixed.parallelism=2
```

Both collectors use `forkCount=1`, `reuseForks=true`. The tests execute on `ForkJoinPool-1-worker-2` and `ForkJoinPool-1-worker-1`. This is not the unsupported multiple-JVM output collision from ASM-04.

### JaCoCo loses executed coverage and accepts the map

The actual [parallel trace](raw/jacoco-parallel/events.tsv) records:

| Row | Thread | Event |
| ---: | --- | --- |
| 12 | ForkJoinPool-1-worker-2 | Requesting test's start/reset has completed |
| 14 | ForkJoinPool-1-worker-1 | Following test enters startSession and waits at the measurement gate |
| 19 | ForkJoinPool-1-worker-2 | Full HTTP response read |
| 23 | http-nio-auto-1-exec-1 | Distinctive production method returns 41327 |
| 26 | ForkJoinPool-1-worker-1 | Following test's own startSession/reset completes |
| 35–37 | ForkJoinPool-1-worker-1 | Following test snapshots and finishes |
| 42–44 | ForkJoinPool-1-worker-2 | Requesting test snapshots and finishes |

No snapshot occurs between the distinctive return and the following test's reset. `startSession` first sets a process-global label and then resets the process-global probes. The already executed distinctive method is cleared before either leaf's finishing snapshot.

**`PostResponseOnly#distinctiveAfterResponse()I` is absent from every saved per-test and setup session.** Both its instruction count and method count are zero in every session. Both leaves become `PASS / COLLECTED_EMPTY`; the final complete map has no `PostResponseOnly` class anywhere. The remaining setup scope contains only `BoundaryApplication` and `BoundaryController`.

Both tests pass, Maven exits zero, aggregation succeeds, and the production validator/joiner accepts the complete candidate. There is no attribution warning, failure or degradation status. Generic Surefire system-property-overwrite and JVM dynamic-agent warnings are present, but none reports lost coverage or rejects parallel mapping.

The requesting test's `.exec` file also contains the embedded session label `BoundaryTests#bFollowing_7535c05`, while its `.identity` sidecar still names `aRequest`. This independently exposes process-global session-label interference; the raw oracle preserves both rather than trusting the embedded label for ownership.

Evidence: [parallel session oracle](raw/jacoco-parallel/session-oracle.json), [parallel fragment](raw/jacoco-parallel/stp/coverage-fragment-v3.json), [accepted accounting](raw/jacoco-parallel/accounting.json). A [fresh parallel repeat](raw/jacoco-parallel-repeat/) reproduces the missing method in all sessions and accepted complete map, with the same exact fixture revision and test identities. No tolerance, filter or collector setting was changed to obtain agreement.

### ASM rejects, with a different diagnostic than the proposed one

Both parallel test methods pass, but collection is incomplete. The sole ERROR setup diagnostic serialized in `agentErrors` is exactly:

```text
setup:ASYNC_SETUP_UNSUPPORTED:ERROR:unknown:ForkJoinTask: execution without observed submission: org.junit.platform.engine.support.hierarchical.ForkJoinPoolHierarchicalTestExecutorService$ExclusiveTask
```

**`PARALLEL_CONTAINER_SETUP_UNSUPPORTED` does not fire.** The actual error names an unobserved JUnit ForkJoin submission. The raw runtime observation shows the HTTP worker has `active=Optional.empty`, `captured=null`, `open=false`. Both controller entry and the distinctive method entry become global `NO_ACTIVE_TEST` events:

| Counter | ASM sequential | ASM parallel |
| --- | ---: | ---: |
| NO_ACTIVE_TEST | 0 | 2 |
| LATE_EVENT | 0 | 0 |
| UNKNOWN_CONTEXT | 0 | 0 |
| TEST_ALREADY_FINISHED | 0 | 0 |
| STARTUP / SHUTDOWN / TRANSFORMATION_ERROR | 0 / 0 / 0 | 0 / 0 / 0 |
| ERROR setup diagnostics | 0 | 1 |

Per-leaf unattributed arrays are empty in both runs; the two parallel events are global. The parallel raw fragment has both PASS/empty leaves, no unmapped entries, and `collection.completed=false`. Its setup scope lacks `PostResponseOnly`.

Finalization fails with:

```text
Cannot finalize ASM mapping evidence: Fragment collection is not completed
```

A separate invocation of the real aggregation goal, without rerunning preparation, fails with:

```text
Maven reactor coverage aggregation failed: Missing module execution evidence for maven:.
```

Production assignment validation and join separately reject the raw fragment with `IllegalArgumentException: Fragment collection is not completed`. No candidate map is produced for this mode. The observed missing captured owner and ForkJoin diagnostic are findings, not a claim that all possible parallel ASM schedules fail identically. No framework/propagation fix was attempted.

Evidence: [ASM parallel diagnostic](raw/asm-parallel/stp/asm-agent-diagnostic.json), [events](raw/asm-parallel/events.tsv), [fragment](raw/asm-parallel/stp/coverage-fragment-v3.json), [validation](raw/asm-parallel/validation.json).

## 4. Assumption for attribution and published results

The following sentence states the temporal-isolation assumption exposed by these measurements:

> For interval-based JaCoCo per-test attribution, all application work caused by a test, including work on other threads, must finish within that test's non-overlapping STP collection window, with no unrelated application work or competing reset during that window and setup work confined to its correctly recorded setup scope.

Disabling parallel execution alone does not establish this assumption: the sequential HTTP case violates it. A successfully read HTTP response is not evidence that all server-side work has completed. A complete map currently proves identity accounting and structural completeness, not temporal isolation or attribution soundness.

The measurements invalidate an unconditional assumption that process-global snapshots provide per-test isolation for these workloads. They do not quantify the effect on previously published evaluation results. No historical evaluation, paper, release binary or dataset was changed or rerun; no number of affected results is estimated. Other coverage limitations, such as JaCoCo's implicit-exception probe behavior, are outside this temporal-isolation measurement.

## 5. Guard proposal only

**A fail-closed parallel check is warranted for JaCoCo mapping.** The preparation path in `PrepareReactorExecutableMappingMojo`, before collection forks start, is the natural Maven integration boundary. It would need the effective configuration actually reaching Jupiter, not a check of `forkCount` or just one POM property. Maven user/system properties, Surefire configuration parameters and `junit-platform.properties` can be distinct configuration channels; a narrowly implemented check must state which it covers and reject unsupported ambiguity rather than claim completeness.

A complementary runtime overlap check belongs at the shared JaCoCo collection/session owner because other callers can bypass Maven preparation. It must make evidence incomplete and fail finalization, or reject before test execution through a reliable configuration hook. Throwing only from a launcher callback is not sufficient fail-closed behavior: listener exceptions may be logged instead of failing the build. This is a proposed design boundary, not an implemented guard.

The cost is lost parallel mapping throughput (or a rejected configuration) plus validation/overlap accounting. It should affect mapping, not ordinary consumer test runs with collection inactive. A guard that rejects any enabled parallel configuration would be conservative and may reject configurations whose effective execution is sequential. It still cannot detect arbitrary post-response background work from global JaCoCo probes alone. This task does not implement a guard, force sequential execution, introduce an adapter, or change fallback/selection policy.

## Reproduction and evidence checks

The [runner](run.sh) executes the real inventory → assignment → prepare → tests → reports/fragment → aggregate chain, using the existing ASM-10 assignment/accounting helper and ASM-12 production validation helper. No execution plans or fragments are repaired. The original sessions and compiled production classes are retained, so the session oracle can be rerun without executing Spring.

Measured environment:

```sh
export PATH=/Users/D061177/Programs/apache-maven-3.9.15/bin:$PATH
export ASM13_WORK=/private/tmp/stp-asm13.mW1XcT
export ASM13_M2=/private/tmp/stp-asm10.VvlrmI/m2
export ASM13_ASM=/private/tmp/stp-asm10.VvlrmI/m2/org/ow2/asm/asm/9.8/asm-9.8.jar
export ASM13_GSON=/Users/D061177/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
bash verification/maven-asm-13/run.sh jacoco-sequential
bash verification/maven-asm-13/run.sh jacoco-parallel
bash verification/maven-asm-13/run.sh asm-sequential
bash verification/maven-asm-13/run.sh asm-parallel
bash verification/maven-asm-13/run.sh jacoco-parallel jacoco-parallel-repeat
```

For a new repetition use a fresh `mktemp -d` work directory and available paths for the explicit harness dependencies. Populate STP's existing functional-test repository with `./gradlew publishStpToIsolatedRepository` if necessary. The runner refuses to reuse an existing subject directory. Preserve the current `raw` evidence before repeating because its output location is fixed. Expanded commands and tool versions are recorded in every run's `command.txt` and `toolchain.txt`.

The runner now invokes `SessionOracle` automatically for JaCoCo. Its classpath uses Gson 2.10.1, JaCoCo core 0.8.15, and ASM/ASM tree/ASM commons 9.8. The initial four runs used the same oracle separately after collection; the fresh repeat exercised the integrated oracle invocation.

Recheck all retained measurement assertions:

```sh
javac -cp "$ASM13_GSON" -d "$ASM13_WORK/classes" verification/maven-asm-13/Audit.java
java -cp "$ASM13_WORK/classes:$ASM13_GSON" Audit verification/maven-asm-13/raw
```

[Audit.java](Audit.java) checks exact executed identities/outcomes, response/release/method/callback order, actual separate worker threads, the absence of a saving snapshot before the destructive reset, raw-session ownership/loss, actual ASM diagnostics, production validation results and source revision consistency. [summary.json](raw/summary.json) and [audit.log](audit.log) retain the result.

The first evidence-audit draft incorrectly expected ASM parallel completion; its failure is preserved in [audit-initial.log](audit-initial.log). It was corrected to assert the actual unsupported ForkJoin diagnostic and incomplete fragment. No fixture, collector or runtime output was modified to turn that result into success.

Five Maven measurements completed, with ten passing test executions in total. Three distinct configurations plus the JaCoCo repeat produced accepted complete candidates; ASM parallel rejected collection. **No publication/storage operation or Jenkins run was performed.** No production code changed, so baseline suites were **not rerun** and prior baseline counts are not presented as fresh. The only added application source is the new measurement fixture; no evaluation harness, paper, ASM-12 fixture or historical evidence was changed.
