// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSuspensionTest {
	private final RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
	private final RuntimeContextService service = new RuntimeContextService(aggregator);
	private final TestIdentity m1 = identity("m1");
	private final TestIdentity m2 = identity("m2");
	private final MethodProbe a = probe(0, "A");
	private final MethodProbe b = probe(1, "B");
	private final MethodProbe c = probe(2, "C");
	private final MethodProbe d = probe(3, "D");
	private static final TestResult PASS = TestResult.successful();
	private static final TestResult FAIL = new TestResult(TestExecutionStatus.FAILED, "AssertionError", "failed row");

	@Test void suspensionDetachesWithoutFinishingOrSettingLastFinished() {
		service.beginTest(m1);
		service.recordMethod(a);
		service.suspendTest(m1);
		service.recordMethod(b);
		assertTrue(service.currentContext().isEmpty());
		assertFalse(observed(m1).finished());
		assertEquals(Set.of(method("A")), observed(m1).methods());
		assertTrue(observed(m1).unattributedEvents().isEmpty());
		assertEquals(Set.of(UnattributedReason.NO_ACTIVE_TEST), aggregator.unattributedSnapshot().keySet()
				.stream().map(event -> event.reason()).collect(java.util.stream.Collectors.toSet()));
	}

	@Test void interleavedRowsKeepIndependentMethodSets() {
		service.beginTest(m1);
		var first = service.currentContext().orElseThrow();
		service.recordMethod(a);
		service.suspendTest(m1);
		service.beginTest(m2);
		var second = service.currentContext().orElseThrow();
		service.recordMethod(b);
		service.suspendTest(m2);
		service.resumeTest(m1);
		assertSame(first, service.currentContext().orElseThrow());
		service.recordMethod(c);
		service.endTest(m1, PASS);
		service.resumeTest(m2);
		assertSame(second, service.currentContext().orElseThrow());
		service.recordMethod(d);
		service.endTest(m2, PASS);
		assertEquals(Set.of(method("A"), method("C")), observed(m1).methods());
		assertEquals(Set.of(method("B"), method("D")), observed(m2).methods());
		assertTrue(observed(m1).finished());
		assertTrue(observed(m2).finished());
	}

	@Test void resumingFinishedTestThrows() {
		service.beginTest(m1);
		service.endTest(m1, PASS);
		assertMessage("test already finished: m1", () -> service.resumeTest(m1));
	}

	@Test void resumingUnknownTestNeverCreatesABucket() {
		assertMessage("test was not begun: m1", () -> service.resumeTest(m1));
		assertTrue(aggregator.snapshot().tests().isEmpty());
	}

	@Test void beginningSuspendedIdentityStillThrows() {
		service.beginTest(m1);
		service.suspendTest(m1);
		assertMessage("test already registered: m1", () -> service.beginTest(m1));
		service.resumeTest(m1);
		assertEquals(m1, service.currentTest().orElseThrow());
	}

	@Test void failureAfterResumeIsTheBucketOutcome() {
		service.beginTest(m1);
		service.recordMethod(a);
		service.suspendTest(m1);
		service.resumeTest(m1);
		service.endTest(m1, FAIL);
		assertEquals(FAIL, observed(m1).result());
		assertTrue(observed(m1).finished());
		assertEquals(UnmappedReason.FAILED, project().unmapped().get(0).reason());
	}

	@Test void unfinishedSuspendedTestPublishesAsUnmappedWithoutLosingSibling(@TempDir Path directory) throws Exception {
		service.beginTest(m1);
		service.recordMethod(a);
		service.suspendTest(m1);
		service.beginTest(m2);
		service.recordMethod(b);
		service.endTest(m2, PASS);
		var codec = new CoverageFragmentCodec();
		Path output = directory.resolve("coverage-fragment-v2.json");
		Files.write(output, codec.serialize(project()));
		var fragment = codec.deserialize(Files.readAllBytes(output));
		assertTrue(fragment.collectionCompleted());
		assertEquals(Set.of(logical("m2")), fragment.tests().keySet());
		assertEquals(Set.of(new com.sap.oss.smarttestpicker.coverage.model.MethodIdentity("example.Target", "B", "()V")),
				fragment.tests().get(logical("m2")).coveredMethods());
		assertEquals(java.util.List.of(new com.sap.oss.smarttestpicker.coverage.model.UnmappedTest(
				logical("m1"), UnmappedReason.COLLECTION_FAILED)), fragment.unmapped());
	}

	@Test void finishingSuspendedTestClosesOwnerAndRejectsCurrentOrFinishedBuckets() {
		service.beginTest(m1);
		var owner = service.currentContext().orElseThrow().methodHits();
		assertMessage("test is not suspended on this thread: m1", () -> service.finishSuspended(m1, PASS));
		assertFalse(owner.closed());
		service.suspendTest(m1);
		service.finishSuspended(m1, FAIL);
		assertTrue(owner.closed());
		assertEquals(FAIL, observed(m1).result());
		assertTrue(observed(m1).finished());
		assertTrue(service.currentContext().isEmpty());
		assertMessage("test already finished: m1", () -> service.finishSuspended(m1, PASS));
		assertMessage("test already finished: m1", () -> service.resumeTest(m1));
		assertMessage("test was not begun: m2", () -> service.finishSuspended(m2, PASS));
	}

	@Test void resumingWithConflictingActiveTestDoesNotLoseEitherContext() {
		service.beginTest(m1);
		service.suspendTest(m1);
		service.beginTest(m2);
		assertMessage("conflicting active test: m2", () -> service.resumeTest(m1));
		assertEquals(m2, service.currentTest().orElseThrow());
		service.endTest(m2, PASS);
		service.resumeTest(m1);
		service.suspendTest(m1);
		service.recordMethod(a);
		assertTrue(observed(m2).unattributedEvents().isEmpty(), "resume must clear the preceding finished marker");
	}

	@Test void suspensionRequiresThisThreadsCurrentIdentity() {
		assertMessage("test is not active on this thread: m1", () -> service.suspendTest(m1));
		service.beginTest(m1);
		assertMessage("test is not active on this thread: m2", () -> service.suspendTest(m2));
		assertEquals(m1, service.currentTest().orElseThrow());
	}

	@Test void suspendedContextCannotBeResumedOrFinishedOnAnotherThread() throws Exception {
		service.beginTest(m1);
		var context = service.currentContext().orElseThrow();
		service.suspendTest(m1);
		onWorker(() -> {
			assertMessage("test was not begun: m1", () -> service.resumeTest(m1));
			assertMessage("test was not begun: m1", () -> service.finishSuspended(m1, PASS));
		});
		service.resumeTest(m1);
		assertSame(context, service.currentContext().orElseThrow());
	}

	@Test void externallyFinishedSuspendedBucketCannotResume() {
		service.beginTest(m1);
		service.suspendTest(m1);
		aggregator.finishTest(m1);
		assertMessage("test already finished: m1", () -> service.resumeTest(m1));
		assertTrue(service.currentContext().isEmpty());
	}

	@Test void finishingSuspendedTestDoesNotDisturbAnotherCurrentTest() {
		service.beginTest(m1);
		service.suspendTest(m1);
		service.beginTest(m2);
		var current = service.currentContext().orElseThrow();
		service.finishSuspended(m1, PASS);
		assertSame(current, service.currentContext().orElseThrow());
		service.recordMethod(b);
		assertEquals(Set.of(method("B")), observed(m2).methods());
	}

	@Test void resumePreservesGuardAndOccurrenceCounterWithoutAggregatorLock() throws Exception {
		service.beginTest(m1);
		var context = service.currentContext().orElseThrow();
		service.recordMethod(a);
		service.suspendTest(m1);
		service.resumeTest(m1);
		assertSame(context, service.currentContext().orElseThrow());
		assertSame(context.methodHits(), service.currentContext().orElseThrow().methodHits());
		service.recordMethod(a);
		var done = new CountDownLatch(1);
		var worker = new Thread(service.wrap(() -> { service.recordMethod(a); done.countDown(); }));
		try {
			synchronized (aggregator) {
				worker.start();
				assertTrue(done.await(2, TimeUnit.SECONDS), "repeat hit acquired the aggregator monitor");
			}
		} finally {
			worker.join(5000);
		}
		assertEquals(3, a.occurrences());
		assertEquals(3, aggregator.testSnapshots().get(0).methods.values().iterator().next());
	}

	@Test void wrappedWorkCapturedBeforeSuspendRetainsOwnerButNewWorkDoesNot() throws Exception {
		try (var registration = RuntimeContextRegistry.install(service)) {
			assertSame(service, RuntimeContextRegistry.current().orElseThrow());
			service.beginTest(m1);
			Runnable before = RuntimeHooks.wrap(() -> service.recordMethod(a));
			service.suspendTest(m1);
			Runnable after = RuntimeHooks.wrap(() -> {
				assertTrue(service.currentTest().isEmpty());
				service.recordMethod(b);
			});
			onWorker(() -> { before.run(); after.run(); });
			assertEquals(Set.of(method("A")), observed(m1).methods());
			assertFalse(observed(m1).finished());
			service.finishSuspended(m1, PASS);
			onWorker(before);
			assertTrue(observed(m1).unattributedEvents().stream().anyMatch(e -> e.reason() == UnattributedReason.LATE_EVENT));
		}
	}

	@Test void forkJoinCaptureBeforeSuspendRetainsOwnerButNewCaptureDoesNot() throws Exception {
		service.beginTest(m1);
		var before = forkJoinHit(a);
		service.captureForkJoin(before);
		service.suspendTest(m1);
		var after = forkJoinHit(b);
		service.captureForkJoin(after);
		onWorker(() -> { before.invoke(); after.invoke(); });
		assertEquals(Set.of(method("A")), observed(m1).methods());
		assertFalse(observed(m1).finished());
	}

	private RecursiveAction forkJoinHit(MethodProbe probe) {
		return new RecursiveAction() {
			// Models the marker and execution advice installed by the agent, as in ForkJoinContextTest.
			private static final boolean $stp$forkJoinExecution = true;
			@Override protected void compute() {
				service.enterForkJoin(this);
				try { service.recordMethod(probe); } finally { service.exitForkJoin(); }
			}
		};
	}

	private static void onWorker(Runnable task) throws Exception {
		var result = new FutureTask<Void>(task, null);
		Thread thread = new Thread(result);
		thread.start();
		try { result.get(5, TimeUnit.SECONDS); } finally { thread.join(5000); }
	}

	private RuntimeObservation.PhysicalTest observed(TestIdentity identity) {
		return aggregator.snapshot().tests().stream().filter(test -> test.identity().equals(identity)).findFirst().orElseThrow();
	}

	private com.sap.oss.smarttestpicker.coverage.model.CoverageFragment project() {
		return new AsmCoverageFragmentProjector().project(aggregator.snapshot(),
				FragmentProjectionConfig.of("revision", "shard"), CollectorIntegrity.healthy()).fragment();
	}

	private static com.sap.oss.smarttestpicker.coverage.model.TestIdentity logical(String method) {
		return new com.sap.oss.smarttestpicker.coverage.model.TestIdentity("example.Tests", method);
	}

	private static TestIdentity identity(String method) {
		return new TestIdentity(method, method, "example.Tests", method, "", "example-engine", "run", "jvm");
	}

	private static MethodIdentity method(String name) { return new MethodIdentity("example.Target", name, "()V"); }
	private static MethodProbe probe(int ordinal, String name) { return new MethodProbe(ordinal, ordinal, method(name)); }
	private static void assertMessage(String expected, Runnable action) {
		assertEquals(expected, assertThrows(IllegalStateException.class, action::run).getMessage());
	}
}
