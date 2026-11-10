// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MethodProbeTest {
	private static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);
	private RuntimeContextService service() { return new RuntimeContextService(new RuntimeEventAggregator("run", "jvm")); }
	private TestIdentity identity(String name) { return new TestIdentity(name, name, "example.Tests", name, "", "junit-jupiter", "run", "jvm"); }
	private MethodProbe probe(int ordinal, String name) { return new MethodProbe(ordinal, 7L, new MethodIdentity("example.Target", name, "()V")); }

	@Test void distinctOrdinalsWithTheSameHashKeepBothMethodsAndCounts() {
		var service = service(); var test = identity("one"); service.beginTest(test);
		var a = probe(0, "a"); var b = probe(1, "b");
		for (int i = 0; i < 100; i++) { service.recordMethod(a); service.recordMethod(b); }
		var snapshot = service.aggregator().testSnapshots().get(0);
		assertEquals(2, snapshot.methods.size());
		assertTrue(snapshot.methods.values().stream().allMatch(count -> count == 100));
		assertEquals(100, a.occurrences()); assertEquals(100, b.occurrences());
	}

	@Test void nextTestAndContainerGetIndependentFirstHits() {
		var service = service(); var probe = probe(0, "a");
		service.beginContainer("container", "example.Tests", false);
		service.recordMethod(probe); service.recordMethod(probe);
		for (String name : new String[]{"one", "two"}) {
			var test = identity(name); service.beginTest(test);
			service.recordMethod(probe); service.recordMethod(probe);
			service.endTest(test, SUCCESS);
		}
		assertEquals(2, service.aggregator().testSnapshots().size());
		assertTrue(service.aggregator().testSnapshots().stream().allMatch(s -> s.methods.values().iterator().next() == 2));
		assertEquals(1, service.aggregator().snapshot().setup().size());
		assertEquals(6, probe.occurrences());
	}

	@Test void repeatedHitDoesNotAcquireAggregatorMonitor() throws Exception {
		var service = service(); var probe = probe(0, "a"); service.beginTest(identity("one"));
		service.recordMethod(probe);
		var done = new CountDownLatch(1);
		var worker = new Thread(service.wrap(() -> { service.recordMethod(probe); done.countDown(); }));
		synchronized (service.aggregator()) {
			worker.start();
			assertTrue(done.await(2, TimeUnit.SECONDS), "repeat blocked on aggregator monitor");
		}
		worker.join();
		assertEquals(2, service.aggregator().testSnapshots().get(0).methods.values().iterator().next());
	}

	@Test void concurrentFirstHitsAndPageGrowthRetainEveryOccurrence() throws Exception {
		var service = service(); service.beginTest(identity("one"));
		var probes = new MethodProbe[]{probe(0, "a"), probe(1024, "b"), probe(2048, "c")};
		var start = new CountDownLatch(1); var threads = new Thread[8];
		for (int i = 0; i < threads.length; i++) {
			threads[i] = new Thread(service.wrap(() -> {
				try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
				for (int call = 0; call < 1000; call++) for (var probe : probes) service.recordMethod(probe);
			}));
			threads[i].start();
		}
		start.countDown(); for (var thread : threads) thread.join();
		var methods = service.aggregator().testSnapshots().get(0).methods;
		assertEquals(3, methods.size());
		assertTrue(methods.values().stream().allMatch(count -> count == 8000));
		for (var probe : probes) assertEquals(8000, probe.occurrences());
	}

	@Test void cachedContainerHitAfterCloseStillDiagnosesEvenIfIdIsReopened() {
		var service = service(); var probe = probe(0, "a");
		service.beginContainer("container", "example.Tests", false);
		Runnable captured = service.wrap(() -> service.recordMethod(probe));
		captured.run(); service.endContainer("container");
		service.beginContainer("container", "example.Tests", false);
		captured.run(); captured.run();
		String json = new RuntimeJsonSerializer().serialize(service.aggregator());
		assertTrue(json.contains("LATE_EVENT"));
		assertTrue(service.aggregator().snapshot().setupDiagnostics().stream()
				.anyMatch(d -> d.kind() == SetupDiagnostic.Kind.ASYNC_SETUP_UNSUPPORTED));
		assertEquals(3, probe.occurrences());
	}

	@Test void cachedLeafHitAfterCloseIsStillLate() {
		var service = service(); var probe = probe(0, "a"); var test = identity("one");
		service.beginTest(test); Runnable captured = service.wrap(() -> service.recordMethod(probe));
		captured.run(); service.endTest(test, SUCCESS); captured.run(); captured.run();
		var snapshot = service.aggregator().testSnapshots().get(0);
		assertEquals(1, snapshot.methods.values().iterator().next());
		assertEquals(2, snapshot.unattributed.values().iterator().next());
		assertEquals(3, probe.occurrences());
	}

	@Test void containerClosedOnAnotherThreadInvalidatesCapturedAndSynchronousCaches() throws Exception {
		var service = service(); var probe = probe(0, "a");
		service.beginContainer("container", "example.Tests", false);
		Runnable captured = service.wrap(() -> service.recordMethod(probe));
		captured.run();
		Thread closer = new Thread(() -> service.endContainer("container"));
		closer.start(); closer.join();
		captured.run(); service.recordMethod(probe);
		assertEquals(2, service.aggregator().unattributedSnapshot().values().stream().mapToInt(Integer::intValue).sum());
		assertTrue(service.aggregator().snapshot().setupDiagnostics().stream()
				.allMatch(d -> d.kind() == SetupDiagnostic.Kind.ASYNC_SETUP_UNSUPPORTED));
		assertEquals(3, probe.occurrences());
	}

	@Test void unboundedSetupAndUnownedRepeatedHitsAreNeverDeduplicated() {
		var service = service(); var probe = probe(0, "a");
		service.recordMethod(probe); service.recordMethod(probe);
		assertEquals(2, service.aggregator().unattributedSnapshot().values().iterator().next());
		service.beginTest(identity("one")); service.recordMethod(probe);
		service.beginUnboundedSharedContextSetup("shared"); service.recordMethod(probe);
		assertTrue(service.aggregator().snapshot().setupDiagnostics().stream()
				.anyMatch(d -> d.kind() == SetupDiagnostic.Kind.SHARED_CONTEXT_SETUP_UNSUPPORTED));
	}
}
