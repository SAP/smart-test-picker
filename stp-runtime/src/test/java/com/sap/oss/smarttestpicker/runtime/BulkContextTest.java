// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BulkContextTest {
    final RuntimeContextService service = new RuntimeContextService(new RuntimeEventAggregator("run", "jvm"));
    final TestResult success = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);
    TestIdentity test(String id) { return new TestIdentity(id, id, "Tests", id, "fixture", "run", "jvm"); }
    void hit(String method) { service.record(new MethodHitEvent(1L, new MethodIdentity("App", method, "()V"),
            new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED))); }
    RuntimeObservation.PhysicalTest observed(String id) { return service.aggregator().snapshot().tests().stream()
            .filter(t -> t.identity().platformUniqueId().equals(id)).findFirst().orElseThrow(); }

    @Test void capturesBeforeLazyIterationAndLeavesCallerCollectionUntouched() throws Exception {
        AtomicInteger iterations = new AtomicInteger();
        Callable<Integer> original = () -> { hit("body"); return 7; };
        List<Callable<Integer>> list = new ArrayList<>(Arrays.asList(original, null, original));
        Collection<Callable<Integer>> source = new AbstractCollection<>() {
            public int size() { return list.size(); }
            public Iterator<Callable<Integer>> iterator() { iterations.incrementAndGet(); return list.iterator(); }
        };
        service.beginTest(test("A"));
        var wrapped = service.wrapCallables(source);
        assertEquals(0, iterations.get());
        service.endTest(test("A"), success);
        service.beginTest(test("B"));
        var it = wrapped.iterator();
        assertEquals(7, it.next().call());
        assertNull(it.next());
        assertEquals(7, it.next().call());
        assertFalse(it.hasNext());
        assertEquals(test("B"), service.currentTest().orElseThrow());
        service.endTest(test("B"), success);
        assertEquals(Arrays.asList(original, null, original), list);
        assertTrue(observed("B").methods().isEmpty());
        assertTrue(observed("A").methods().isEmpty());
        assertTrue(observed("A").unattributedEvents().stream().allMatch(e -> e.reason() == UnattributedReason.LATE_EVENT));
        assertFalse(observed("A").unattributedEvents().isEmpty());
    }

    @Test void sameCallableHasIndependentConcurrentSubmissionOwners() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<String> original = () -> { barrier.await(5, TimeUnit.SECONDS); hit("shared");
            return service.currentTest().orElseThrow().platformUniqueId(); };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        service.beginTest(test("A"));
        var a = service.wrapCallables(List.of(original)).iterator().next();
        // Keep A open on its logical lifecycle while another caller creates B.
        var b = workers.submit(() -> {
            service.beginTest(test("B"));
            try { return service.wrapCallables(List.of(original)).iterator().next().call(); }
            finally { service.endTest(test("B"), success); }
        });
        try {
            assertEquals("A", a.call()); assertEquals("B", b.get(5, TimeUnit.SECONDS));
        } finally { service.endTest(test("A"), success); workers.shutdownNow(); }
        assertEquals(1, observed("A").methods().size()); assertEquals(1, observed("B").methods().size());
    }

    @Test void delegatedSubmitAndBulkDoNotRecaptureTheWrapper() throws Exception {
        service.beginTest(test("A"));
        var captured = service.wrapCallables(List.<Callable<Integer>>of(() -> { hit("body"); return 9; })).iterator().next();
        service.endTest(test("A"), success);
        service.beginTest(test("B"));
        assertSame(captured, service.wrap(captured));
        assertSame(captured, service.wrapCallables(List.of(captured)).iterator().next());
        assertEquals(9, captured.call());
        assertEquals(test("B"), service.currentTest().orElseThrow());
        service.endTest(test("B"), success);
        assertTrue(observed("B").methods().isEmpty());
        assertFalse(observed("A").unattributedEvents().isEmpty());
    }

    @Test void emptyOwnerSuppressesWorkerStateAndRestoresItEvenOnError() {
        Callable<Void> original = () -> { assertTrue(service.currentTest().isEmpty()); hit("unowned"); throw new AssertionError("expected"); };
        var captured = service.wrapCallables(List.of(original)).iterator().next();
        service.beginContainer("ambient", "Ambient", false);
        service.beginTest(test("B"));
        assertThrows(AssertionError.class, captured::call);
        assertEquals(test("B"), service.currentTest().orElseThrow());
        service.endTest(test("B"), success); hit("ambient"); service.endContainer("ambient");
        assertTrue(observed("B").methods().isEmpty());
        assertEquals(Set.of(new MethodIdentity("App", "ambient", "()V")), service.aggregator().snapshot().setup().get(0).methods());
        assertEquals(UnattributedReason.NO_ACTIVE_TEST, service.aggregator().snapshot().unattributedEvents().iterator().next().reason());
    }

    @Test void nestedSetupSubmissionUsesCapturedContainerAndClosedContainerIsIncomplete() throws Exception {
        service.beginContainer("setup", "Tests", false);
        var nested = service.wrapCallables(List.<Callable<Callable<Void>>>of(() -> service.wrap((Callable<Void>) () -> { hit("setup"); return null; })));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Callable<Void> child = pool.submit(nested.iterator().next()).get(5, TimeUnit.SECONDS);
            pool.submit(child).get(5, TimeUnit.SECONDS);
            assertEquals(1, service.aggregator().snapshot().setup().get(0).methods().size());
            service.endContainer("setup");
            pool.submit(child).get(5, TimeUnit.SECONDS);
            assertTrue(service.aggregator().snapshot().setupDiagnostics().stream().anyMatch(d -> d.detail().contains("closed JUnit container")));
            assertTrue(service.aggregator().snapshot().unattributedEvents().stream().anyMatch(e -> e.reason() == UnattributedReason.LATE_EVENT));
        } finally { pool.shutdownNow(); }
    }

    @Test void unknownSharedSetupIsNotInventedAndFinishedMarkerIsRestored() throws Exception {
        service.beginUnboundedSharedContextSetup("unknown");
        var work = service.wrapCallables(List.<Callable<Void>>of(() -> { hit("shared"); return null; }));
        service.endUnboundedSharedContextSetup("unknown");
        service.beginTest(test("finished")); service.endTest(test("finished"), success);
        work.iterator().next().call(); hit("synchronousLate");
        assertEquals(SetupDiagnostic.Kind.SHARED_CONTEXT_SETUP_UNSUPPORTED, service.aggregator().snapshot().setupDiagnostics().iterator().next().kind());
        assertEquals(1, observed("finished").unattributedEvents().size());
        assertTrue(observed("finished").unattributedEvents().iterator().next().eventIdentity().contains("synchronousLate"));
    }

    @Test void unregisteredForkJoinAdapterCanDelegateToBulkButDoesNotHideUnownedHits() throws Exception {
        for (boolean outsideHit : List.of(false, true)) {
            service.beginTest(test("adapter" + outsideHit));
            var captured = service.wrapCallables(List.<Callable<Integer>>of(() -> { hit("captured"); return 1; })).iterator().next();
            var adapter = ForkJoinTask.adapt(captured);
            Runnable restore = service.openForkJoinExecution(adapter);
            try {
                assertEquals(1, captured.call());
                if (outsideHit) hit("outsideWrapper");
            } finally { restore.run(); }
            service.endTest(test("adapter" + outsideHit), success);
            assertEquals(1, observed("adapter" + outsideHit).methods().size());
            assertEquals(outsideHit, !service.aggregator().snapshot().setupDiagnostics().isEmpty());
        }
    }

    @Test void nullAndEmptyCollectionsPreserveTheirMeaning() {
        assertNull(service.wrapCallables(null));
        assertTrue(service.wrapCallables(List.of()).isEmpty());
    }

    @Test void bulkCreatedAfterMissingSubmissionCannotLegitimizeTheLostOwner() throws Exception {
        service.beginTest(test("A"));
        Runnable restore = service.openForkJoinExecution(ForkJoinTask.adapt(() -> { }));
        try {
            var capturedTooLate = service.wrapCallables(List.<Callable<Integer>>of(() -> { hit("unowned"); return 1; }));
            capturedTooLate.iterator().next().call();
        } finally { restore.run(); service.endTest(test("A"), success); }
        assertTrue(observed("A").methods().isEmpty());
        assertTrue(service.aggregator().snapshot().setupDiagnostics().stream()
                .anyMatch(d -> d.detail().contains("without observed submission")));
    }
}
