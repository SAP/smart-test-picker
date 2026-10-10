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
		// Simulates doExec advice; the agent tests exercise actual JDK retransformation.
		private final Runnable work;
		Task(Runnable work) { this.work = work; }
		@Override public boolean equals(Object other) { return other instanceof Task; }
		@Override public int hashCode() { return 1; }
		@Override protected void compute() {
			Runnable scope = service.openForkJoinExecution(this);
			try { work.run(); } finally { scope.run(); }
		}
	}

	@Test void captureAtSubmissionAndReuseAfterReinitializeRestoresAmbientOwner() {
		Task task = task(() -> hit("body"));
		service.beginTest(test("A")); capture(task); service.endTest(test("A"), SUCCESS);
		service.beginTest(test("B")); task.invoke();
		assertEquals(test("B"), service.currentTest().orElseThrow());
		assertTrue(observed("A").methods().isEmpty());
		assertEquals(UnattributedReason.LATE_EVENT, observed("A").unattributedEvents().iterator().next().reason());
		reset(task); capture(task); task.invoke();
		service.endTest(test("B"), SUCCESS);
		assertEquals(1, observed("B").methods().size());
		assertTrue(service.aggregator().snapshot().setupDiagnostics().isEmpty());
	}

	@Test void noOwnerSuppressesAmbientTestAndRestoresAfterError() {
		Task task = task(() -> { assertTrue(service.currentTest().isEmpty()); hit("unowned"); throw new AssertionError("failure"); });
		capture(task);
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
			Task child = task(() -> hit("setup")); capture(child); child.invoke();
		});
		capture(parent); parent.invoke();
		Task late = task(() -> hit("lateSetup")); capture(late);
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
			service.beginTest(test("A")); capture(task); pool.execute(task);
			assertTrue(entered.await(5, TimeUnit.SECONDS)); service.endTest(test("A"), SUCCESS);
			service.beginTest(test("B")); capture(task); release.countDown(); task.get(5, TimeUnit.SECONDS);
			service.endTest(test("B"), SUCCESS);
			assertTrue(observed("A").methods().isEmpty()); assertTrue(observed("B").methods().isEmpty());
			assertFalse(service.aggregator().snapshot().setupDiagnostics().isEmpty());
			assertEquals(UnattributedReason.UNKNOWN_CONTEXT, service.aggregator().snapshot().unattributedEvents().iterator().next().reason());
		} finally { release.countDown(); pool.shutdownNow(); }
	}

	@Test void cancelledTaskDoesNotExecuteAndCanBeReinitialized() {
		Task task = task(() -> hit("afterCancellation"));
		service.beginTest(test("A")); capture(task); assertTrue(task.cancel(false));
		assertThrows(CancellationException.class, task::invoke); service.endTest(test("A"), SUCCESS);
		reset(task);
		service.beginTest(test("B")); capture(task); task.invoke(); service.endTest(test("B"), SUCCESS);
		assertTrue(observed("A").methods().isEmpty()); assertEquals(1, observed("B").methods().size());
	}

	@Test void weakKeysUseObjectIdentityNotUserEquals() {
		service.beginTest(test("A"));
		Task one = task(() -> hit("one")), two = task(() -> hit("two"));
		capture(one); capture(two); one.invoke(); two.invoke();
		service.endTest(test("A"), SUCCESS);
		assertEquals(2, observed("A").methods().size());
	}
	@Test void restoresCapturedContainerSharedSetupAndFinishedMarker() {
		Task unowned = task(() -> hit("unowned")); capture(unowned);
		service.beginContainer("ambient", "AmbientTests", false);
		unowned.invoke();
		hit("ambientSetup");
		service.endContainer("ambient");
		assertEquals(1, service.aggregator().snapshot().setup().get(0).methods().size());
		assertTrue(service.aggregator().snapshot().setup().get(0).methods().stream()
				.anyMatch(m -> m.methodName().equals("ambientSetup")));

		service.beginUnboundedSharedContextSetup("shared");
		Task shared = task(() -> hit("sharedSetup")); capture(shared);
		service.endUnboundedSharedContextSetup("shared");
		shared.invoke();
		assertTrue(service.aggregator().snapshot().setupDiagnostics().stream()
				.anyMatch(d -> d.kind() == SetupDiagnostic.Kind.SHARED_CONTEXT_SETUP_UNSUPPORTED));
		service.beginTest(test("finished")); service.endTest(test("finished"), SUCCESS);
		Task later = task(() -> hit("notFinishedOwner")); capture(later); later.invoke();
		hit("synchronousLate");
		assertEquals(1, observed("finished").unattributedEvents().size());
		assertTrue(observed("finished").unattributedEvents().iterator().next().eventIdentity().contains("synchronousLate"));
	}

	@Test void ambiguousParentDoesNotGiveChildrenAFalseOwner() {
		Task parent = task(() -> {
			Task child = task(() -> hit("child")); capture(child); child.invoke();
		});
		service.beginTest(test("A")); capture(parent); service.endTest(test("A"), SUCCESS);
		service.beginTest(test("B")); capture(parent); parent.invoke(); service.endTest(test("B"), SUCCESS);
		assertTrue(observed("A").methods().isEmpty()); assertTrue(observed("A").unattributedEvents().isEmpty());
		assertTrue(observed("B").methods().isEmpty());
		assertFalse(service.aggregator().snapshot().setupDiagnostics().isEmpty());
	}

    private void capture(ForkJoinTask<?> task) {
        Object ticket = service.beginForkJoinSubmission(task);
        service.endForkJoinSubmission(ticket, null);
    }
    private void reset(ForkJoinTask<?> task) {
        Object ticket = service.beginForkJoinReset(task);
        task.reinitialize();
        service.endForkJoinReset(ticket, null);
    }

    @Test void rejectedSubmissionRollsBackOnlyItsOwnUnstartedBinding() {
        Task task = task(() -> hit("body"));
        service.beginTest(test("A"));
        Object ticket = service.beginForkJoinSubmission(task);
        service.endForkJoinSubmission(ticket, new RejectedExecutionException());
        service.endTest(test("A"), SUCCESS);
        service.beginTest(test("B")); capture(task); task.invoke(); service.endTest(test("B"), SUCCESS);
        assertTrue(observed("A").methods().isEmpty());
        assertEquals(1, observed("B").methods().size());
        assertTrue(service.aggregator().snapshot().setupDiagnostics().isEmpty());
    }

    @Test void apiDelegationIsOneSubmissionButReentrantExecutionIsNot() {
        Task task = task(() -> hit("delegated"));
        service.beginTest(test("A"));
        Object outer = service.beginForkJoinSubmission(task);
        Object inner = service.beginForkJoinSubmission(task);
        service.endForkJoinSubmission(inner, null);
        task.invoke();
        service.endForkJoinSubmission(outer, null);
        service.endTest(test("A"), SUCCESS);
        assertEquals(1, observed("A").methods().size());
        assertTrue(service.aggregator().snapshot().setupDiagnostics().isEmpty());
    }

    @Test void rejectionCannotEraseConcurrentSubmission() throws Exception {
        Task task = task(() -> hit("ambiguous"));
        service.beginTest(test("A"));
        Object rejected = service.beginForkJoinSubmission(task);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            try { service.beginTest(test("B")); capture(task); service.endTest(test("B"), SUCCESS); }
            catch (Throwable problem) { failure.set(problem); }
        });
        other.start(); other.join(5000); assertFalse(other.isAlive()); assertNull(failure.get());
        service.endForkJoinSubmission(rejected, new RejectedExecutionException());
        task.invoke(); service.endTest(test("A"), SUCCESS);
        assertTrue(observed("A").methods().isEmpty()); assertTrue(observed("B").methods().isEmpty());
        assertFalse(service.aggregator().snapshot().setupDiagnostics().isEmpty());
    }

    @Test void failedRegistrationCannotEraseAcceptedSameOwnerSubmission() {
        Task task = task(() -> hit("accepted"));
        service.beginTest(test("A"));
        Object rejected = service.beginForkJoinSubmission(task);
        Object accepted = service.beginForkJoinSubmission(task);
        service.endForkJoinSubmission(accepted, null);
        service.endForkJoinSubmission(rejected, new RejectedExecutionException());
        task.invoke(); service.endTest(test("A"), SUCCESS);
        assertEquals(1, observed("A").methods().size());
        assertTrue(service.aggregator().snapshot().setupDiagnostics().isEmpty());
    }

    @Test void allRejectedDelegationsReleaseBindingForNextOwner() {
        Task task = task(() -> hit("B"));
        service.beginTest(test("A"));
        Object outer = service.beginForkJoinSubmission(task);
        Object inner = service.beginForkJoinSubmission(task);
        service.endForkJoinSubmission(inner, new RejectedExecutionException());
        service.endForkJoinSubmission(outer, new RejectedExecutionException());
        service.endTest(test("A"), SUCCESS);
        service.beginTest(test("B")); capture(task); task.invoke(); service.endTest(test("B"), SUCCESS);
        assertTrue(observed("A").methods().isEmpty()); assertEquals(1, observed("B").methods().size());
        assertTrue(service.aggregator().snapshot().setupDiagnostics().isEmpty());
    }

    @Test void submissionRacingResetStaysQuarantinedAcrossFurtherReset() {
        Task task = task(() -> hit("body"));
        service.beginTest(test("A")); capture(task); task.invoke();
        Object resetting = service.beginForkJoinReset(task);
        task.reinitialize();
        capture(task); // Interleaving: submitted after original reinitialize, before advice exit.
        service.endForkJoinReset(resetting, null);
        task.invoke(); service.endTest(test("A"), SUCCESS);
        assertEquals(1, observed("A").methods().size());
        assertTrue(service.aggregator().snapshot().unattributedEvents().stream()
                .anyMatch(e -> e.reason() == UnattributedReason.UNKNOWN_CONTEXT));
        reset(task);
        service.beginTest(test("B")); capture(task); task.invoke(); service.endTest(test("B"), SUCCESS);
        assertTrue(observed("B").methods().isEmpty());
    }

    @Test void unobservedSubmissionNeverBorrowsAmbientOwner() {
        Task task = task(() -> hit("missing"));
        service.beginTest(test("A")); task.invoke(); service.endTest(test("A"), SUCCESS);
        assertTrue(observed("A").methods().isEmpty());
        assertTrue(service.aggregator().snapshot().setupDiagnostics().stream()
                .anyMatch(d -> d.detail().contains("without observed submission")));
    }

    @Test void completedTaskWithPossibleStaleEnqueueCannotChangeOwnerOnReset() {
        Task task = task(() -> hit("body"));
        service.beginTest(test("A")); capture(task); task.invoke();
        capture(task); // A completed task can still be enqueued by fork/pool APIs.
        service.endTest(test("A"), SUCCESS);
        reset(task); // Cannot prove that the stale enqueue has been consumed.
        service.beginTest(test("B")); capture(task); task.invoke(); service.endTest(test("B"), SUCCESS);
        assertTrue(observed("B").methods().isEmpty());
        assertFalse(service.aggregator().snapshot().setupDiagnostics().isEmpty());
    }

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
	}
}
