// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.Certainty;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.EvidenceSource;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContextServiceTest {
	private static final RuntimeJsonSerializer JSON = new RuntimeJsonSerializer();
	private static final TestResult SUCCESS = new TestResult(TestExecutionStatus.SUCCESSFUL, null, null);

	@Test
	void immediateSameThreadEventAfterCompletionIsLateForFinishedTest() {
		RuntimeContextService service = service();
		TestIdentity finished = test("finished");
		service.beginTest(finished);
		service.endTest(finished, SUCCESS);
		service.record(method("immediateLate"));

		String json = JSON.serialize(service.aggregator());
		assertTrue(testSection(json, "finished").contains("\"reason\":\"LATE_EVENT\""));
		assertTrue(testSection(json, "finished").contains("Target#immediateLate()V"));
	}

	@Test
	void beginningNextTestClearsPreviousFinishedMarker() {
		RuntimeContextService service = service();
		TestIdentity first = test("first");
		TestIdentity second = test("second");
		service.beginTest(first);
		service.endTest(first, SUCCESS);
		service.beginTest(second);
		service.record(method("onlySecond"));
		service.endTest(second, SUCCESS);

		String json = JSON.serialize(service.aggregator());
		assertFalse(testSection(json, "first").contains("onlySecond"));
		assertTrue(testSection(json, "second").contains("Target#onlySecond()V"));
	}

	@Test
	void unrelatedThreadWithoutLastFinishedMarkerRecordsGlobalNoActiveTest() throws Exception {
		RuntimeContextService service = service();
		TestIdentity finished = test("main-thread-finished");
		service.beginTest(finished);
		service.endTest(finished, SUCCESS);
		Thread unrelated = new Thread(() -> service.record(method("unrelatedThread")), "unrelated-observer");
		unrelated.start();
		unrelated.join();

		String json = JSON.serialize(service.aggregator());
		assertFalse(testSection(json, "main-thread-finished").contains("unrelatedThread"));
		String global = json.substring(json.lastIndexOf("\"unattributedEvents\""));
		assertTrue(global.contains("\"reason\":\"NO_ACTIVE_TEST\""));
		assertTrue(global.contains("Target#unrelatedThread()V"));
	}

	@Test
	void wrappedTasksCaptureSubmissionContextWithoutLeakingAcrossReusedWorker() throws Exception {
		RuntimeContextService service = service();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			TestIdentity first = test("first");
			service.beginTest(first);
			worker.submit(service.wrap(() -> service.record(method("firstTask")))).get();
			service.endTest(first, SUCCESS);

			TestIdentity second = test("second");
			service.beginTest(second);
			worker.submit(service.wrap(() -> service.record(method("secondTask")))).get();
			service.endTest(second, SUCCESS);
			worker.submit(service.wrap(() -> service.record(method("unrelatedTask")))).get();

			String json = JSON.serialize(service.aggregator());
			assertTrue(testSection(json, "first").contains("firstTask"));
			assertFalse(testSection(json, "first").contains("secondTask"));
			assertTrue(testSection(json, "second").contains("secondTask"));
			assertFalse(testSection(json, "second").contains("unrelatedTask"));
			assertTrue(globalSection(json).contains("unrelatedTask"));
		} finally {
			worker.shutdownNow();
		}
	}

	@Test
	void nestedSubmissionCapturesThePropagatedParentContext() throws Exception {
		RuntimeContextService service = service();
		ExecutorService worker = Executors.newFixedThreadPool(2);
		try {
			TestIdentity identity = test("nested");
			service.beginTest(identity);
			Future<?> outer = worker.submit(service.wrap(() -> {
				service.record(method("outer"));
				try {
					worker.submit(service.wrap(() -> service.record(method("inner")))).get();
				} catch (Exception failure) {
					throw new AssertionError(failure);
				}
			}));
			outer.get();
			service.endTest(identity, SUCCESS);
			String section = testSection(JSON.serialize(service.aggregator()), "nested");
			assertTrue(section.contains("outer"));
			assertTrue(section.contains("inner"));
		} finally {
			worker.shutdownNow();
		}
	}

	@Test
	void delayedCallableKeepsSubmittingTestAndPreservesResult() throws Exception {
		RuntimeContextService service = service();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		CountDownLatch release = new CountDownLatch(1);
		try {
			worker.submit((Callable<Void>) () -> {
				release.await();
				return null;
			});
			TestIdentity identity = test("delayed");
			service.beginTest(identity);
			Object result = new Object();
			Future<Object> future = worker.submit(service.wrap((Callable<Object>) () -> {
				service.record(method("afterEnd"));
				return result;
			}));
			service.endTest(identity, SUCCESS);
			release.countDown();
			assertSame(result, future.get(5, TimeUnit.SECONDS));
			assertTrue(testSection(JSON.serialize(service.aggregator()), "delayed").contains("afterEnd"));
		} finally {
			release.countDown();
			worker.shutdownNow();
		}
	}

	@Test
	void failingTaskRestoresWorkerAndPreservesExceptionIdentity() throws Exception {
		RuntimeContextService service = service();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			TestIdentity identity = test("failure");
			service.beginTest(identity);
			IllegalStateException expected = new IllegalStateException("boom");
			Future<?> future = worker.submit(service.wrap(() -> {
				service.record(method("beforeFailure"));
				throw expected;
			}));
			ExecutionException actual = assertThrows(ExecutionException.class, future::get);
			assertSame(expected, actual.getCause());
			service.endTest(identity, SUCCESS);
			worker.submit(service.wrap(() -> service.record(method("afterFailure")))).get();
			String json = JSON.serialize(service.aggregator());
			assertTrue(testSection(json, "failure").contains("beforeFailure"));
			assertTrue(globalSection(json).contains("afterFailure"));
		} finally {
			worker.shutdownNow();
		}
	}

	@Test
	void wrappedTaskRestoresAnExistingWorkerContext() throws Exception {
		RuntimeContextService service = service();
		TestIdentity captured = test("captured");
		service.beginTest(captured);
		Runnable wrapped = service.wrap(() -> service.record(method("capturedTask")));
		service.endTest(captured, SUCCESS);

		TestIdentity worker = test("worker");
		service.beginTest(worker);
		wrapped.run();
		assertEquals(worker, service.currentTest().orElseThrow());
		service.record(method("workerAfterRestore"));
		service.endTest(worker, SUCCESS);

		String json = JSON.serialize(service.aggregator());
		assertTrue(testSection(json, "captured").contains("capturedTask"));
		assertTrue(testSection(json, "worker").contains("workerAfterRestore"));
	}

	@Test
	void sameRunnableAndCallableCanBeCapturedIndependentlyForDifferentTests() throws Exception {
		RuntimeContextService service = service();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Runnable runnable = () -> service.record(method("sameRunnable"));
			Callable<String> callable = () -> { service.record(method("sameCallable")); return "result"; };
			for (String id : new String[] { "reuse-a", "reuse-b" }) {
				TestIdentity identity = test(id);
				service.beginTest(identity);
				worker.submit(service.wrap(runnable)).get();
				assertEquals("result", worker.submit(service.wrap(callable)).get());
				service.endTest(identity, SUCCESS);
			}
			String json = JSON.serialize(service.aggregator());
			assertTrue(testSection(json, "reuse-a").contains("sameRunnable"));
			assertTrue(testSection(json, "reuse-a").contains("sameCallable"));
			assertTrue(testSection(json, "reuse-b").contains("sameRunnable"));
			assertTrue(testSection(json, "reuse-b").contains("sameCallable"));
		} finally {
			worker.shutdownNow();
		}
	}

	@Test
	void overlappingLogicalContextsRemainIsolated() throws Exception {
		RuntimeContextService service = service();
		ExecutorService workers = Executors.newFixedThreadPool(2);
		CountDownLatch attached = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread submitterA = submitter(service, workers, "overlap-a", "eventA", attached, release, failure);
		Thread submitterB = submitter(service, workers, "overlap-b", "eventB", attached, release, failure);
		try {
			submitterA.start();
			submitterB.start();
			assertTrue(attached.await(5, TimeUnit.SECONDS));
			release.countDown();
			submitterA.join();
			submitterB.join();
			if (failure.get() != null) throw new AssertionError(failure.get());
			String json = JSON.serialize(service.aggregator());
			assertTrue(testSection(json, "overlap-a").contains("eventA"));
			assertFalse(testSection(json, "overlap-a").contains("eventB"));
			assertTrue(testSection(json, "overlap-b").contains("eventB"));
			assertFalse(testSection(json, "overlap-b").contains("eventA"));
		} finally {
			release.countDown();
			workers.shutdownNow();
		}
	}

	private static Thread submitter(RuntimeContextService service, ExecutorService workers, String id,
			String event, CountDownLatch attached, CountDownLatch release, AtomicReference<Throwable> failure) {
		return new Thread(() -> {
			TestIdentity identity = test(id);
			try {
				service.beginTest(identity);
				workers.submit(service.wrap(() -> {
					attached.countDown();
					try {
						release.await();
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new AssertionError(interrupted);
					}
					service.record(method(event));
				})).get();
				service.endTest(identity, SUCCESS);
			} catch (Throwable throwable) {
				failure.compareAndSet(null, throwable);
			}
		}, "submitter-" + id);
	}

	private static RuntimeContextService service() {
		return new RuntimeContextService(new RuntimeEventAggregator("run-1", "jvm-1"));
	}

	private static TestIdentity test(String id) {
		return new TestIdentity(id, id, "fixture.Test", id, "junit-jupiter", "run-1", "jvm-1");
	}

	private static MethodHitEvent method(String name) {
		return new MethodHitEvent(new MethodIdentity("fixture.Target", name, "()V"),
				new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED));
	}

	private static String testSection(String json, String testId) {
		int start = json.indexOf("\"testId\": \"" + testId + "\"");
		int next = json.indexOf("\"testId\": \"", start + 1);
		return json.substring(start, next < 0 ? json.indexOf("\n  ],", start) : next);
	}

	private static String globalSection(String json) {
		return json.substring(json.lastIndexOf("\"unattributedEvents\""));
	}
}
