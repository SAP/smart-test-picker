// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForkJoinContextTest {
	private final RuntimeContextService service = new RuntimeContextService(new RuntimeEventAggregator("run", "jvm"));
	private static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);
	private TestIdentity test(String id) { return new TestIdentity(id, id, "Tests", id, "junit", "run", "jvm"); }
	private void hit(String name) { service.record(new MethodHitEvent(1L, new MethodIdentity("App", name, "()V"),
			new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED))); }
	private RuntimeObservation.PhysicalTest observed(String id) { return service.aggregator().snapshot().tests().stream()
			.filter(t -> t.identity().platformUniqueId().equals(id)).findFirst().orElseThrow(); }
	private Task task(Runnable work) { return new Task(work); }
	private final class Task extends RecursiveAction {
		// Simulates the marker and advice installed by ASM; real transformation is separately tested in a child JVM.
		private static final boolean $stp$forkJoinExecution = true;
		private final Runnable work;
		Task(Runnable work) { this.work = work; }
		@Override public boolean equals(Object other) { return other instanceof Task; }
		@Override public int hashCode() { return 1; }
		@Override protected void compute() {
			service.enterForkJoin(this);
			try { work.run(); } finally { service.exitForkJoin(); }
		}
	}

	@Test void captureAtSubmissionAndReuseAfterReinitializeRestoresAmbientOwner() {
		Task task = task(() -> hit("body"));
		service.beginTest(test("A")); service.captureForkJoin(task); service.endTest(test("A"), SUCCESS);
		service.beginTest(test("B")); task.invoke();
		assertEquals(test("B"), service.currentTest().orElseThrow());
		assertTrue(observed("A").methods().isEmpty());
		assertEquals(UnattributedReason.LATE_EVENT, observed("A").unattributedEvents().iterator().next().reason());
		service.reinitializeForkJoin(task); task.reinitialize(); service.captureForkJoin(task); task.invoke();
		service.endTest(test("B"), SUCCESS);
		assertEquals(1, observed("B").methods().size());
		assertTrue(service.aggregator().snapshot().setupDiagnostics().isEmpty());
	}

	@Test void noOwnerSuppressesAmbientTestAndRestoresAfterError() {
		Task task = task(() -> { assertTrue(service.currentTest().isEmpty()); hit("unowned"); throw new AssertionError("failure"); });
		service.captureForkJoin(task);
		service.beginTest(test("B"));
		assertThrows(AssertionError.class, task::invoke);
		assertEquals(test("B"), service.currentTest().orElseThrow());
		service.endTest(test("B"), SUCCESS);
		assertTrue(observed("B").methods().isEmpty());
		assertEquals(UnattributedReason.NO_ACTIVE_TEST, service.aggregator().snapshot().unattributedEvents().iterator().next().reason());
	}

	@Test void setupDescendantsInheritContainerAndClosedContainerIsLate() {
		service.beginContainer("container", "Tests", false);
		Task parent = task(() -> {
			Task child = task(() -> hit("setup")); service.captureForkJoin(child); child.invoke();
		});
		service.captureForkJoin(parent); parent.invoke();
		Task late = task(() -> hit("lateSetup")); service.captureForkJoin(late);
		service.endContainer("container"); late.invoke();
		assertEquals(1, service.aggregator().snapshot().setup().get(0).methods().size());
		assertTrue(service.aggregator().snapshot().setupDiagnostics().stream().anyMatch(d -> d.detail().contains("closed JUnit")));
		assertEquals(UnattributedReason.LATE_EVENT, service.aggregator().snapshot().unattributedEvents().iterator().next().reason());
	}

	@Test void duplicateSubmissionQuarantinesFollowingHitsWithoutOverwritingOwner() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		Task task = task(() -> { entered.countDown(); await(release); hit("ambiguous"); });
		ForkJoinPool pool = new ForkJoinPool(1);
		try {
			service.beginTest(test("A")); service.captureForkJoin(task); pool.execute(task);
			assertTrue(entered.await(5, TimeUnit.SECONDS)); service.endTest(test("A"), SUCCESS);
			service.beginTest(test("B")); service.captureForkJoin(task); release.countDown(); task.get(5, TimeUnit.SECONDS);
			service.endTest(test("B"), SUCCESS);
			assertTrue(observed("A").methods().isEmpty()); assertTrue(observed("B").methods().isEmpty());
			assertFalse(service.aggregator().snapshot().setupDiagnostics().isEmpty());
			assertEquals(UnattributedReason.UNKNOWN_CONTEXT, service.aggregator().snapshot().unattributedEvents().iterator().next().reason());
		} finally { release.countDown(); pool.shutdownNow(); }
	}

	@Test void cancelledTaskDoesNotExecuteAndCanBeReinitialized() {
		Task task = task(() -> hit("afterCancellation"));
		service.beginTest(test("A")); service.captureForkJoin(task); assertTrue(task.cancel(false));
		assertThrows(CancellationException.class, task::invoke); service.endTest(test("A"), SUCCESS);
		service.reinitializeForkJoin(task); task.reinitialize();
		service.beginTest(test("B")); service.captureForkJoin(task); task.invoke(); service.endTest(test("B"), SUCCESS);
		assertTrue(observed("A").methods().isEmpty()); assertEquals(1, observed("B").methods().size());
	}

	@Test void weakKeysUseObjectIdentityNotUserEquals() {
		service.beginTest(test("A"));
		Task one = task(() -> hit("one")), two = task(() -> hit("two"));
		service.captureForkJoin(one); service.captureForkJoin(two); one.invoke(); two.invoke();
		service.endTest(test("A"), SUCCESS);
		assertEquals(2, observed("A").methods().size());
	}
	@Test void restoresCapturedContainerSharedSetupAndFinishedMarker() {
		Task unowned = task(() -> hit("unowned")); service.captureForkJoin(unowned);
		service.beginContainer("ambient", "AmbientTests", false);
		unowned.invoke();
		hit("ambientSetup");
		service.endContainer("ambient");
		assertEquals(1, service.aggregator().snapshot().setup().get(0).methods().size());
		assertTrue(service.aggregator().snapshot().setup().get(0).methods().stream()
				.anyMatch(m -> m.methodName().equals("ambientSetup")));

		service.beginUnboundedSharedContextSetup("shared");
		Task shared = task(() -> hit("sharedSetup")); service.captureForkJoin(shared);
		service.endUnboundedSharedContextSetup("shared");
		shared.invoke();
		assertTrue(service.aggregator().snapshot().setupDiagnostics().stream()
				.anyMatch(d -> d.kind() == SetupDiagnostic.Kind.SHARED_CONTEXT_SETUP_UNSUPPORTED));
		service.beginTest(test("finished")); service.endTest(test("finished"), SUCCESS);
		Task later = task(() -> hit("notFinishedOwner")); service.captureForkJoin(later); later.invoke();
		hit("synchronousLate");
		assertEquals(1, observed("finished").unattributedEvents().size());
		assertTrue(observed("finished").unattributedEvents().iterator().next().eventIdentity().contains("synchronousLate"));
	}

	@Test void ambiguousParentDoesNotGiveChildrenAFalseOwner() {
		Task parent = task(() -> {
			Task child = task(() -> hit("child")); service.captureForkJoin(child); child.invoke();
		});
		service.beginTest(test("A")); service.captureForkJoin(parent); service.endTest(test("A"), SUCCESS);
		service.beginTest(test("B")); service.captureForkJoin(parent); parent.invoke(); service.endTest(test("B"), SUCCESS);
		assertTrue(observed("A").methods().isEmpty()); assertTrue(observed("A").unattributedEvents().isEmpty());
		assertTrue(observed("B").methods().isEmpty());
		assertFalse(service.aggregator().snapshot().setupDiagnostics().isEmpty());
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
	}
}
