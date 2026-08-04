// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeEventAggregator;
import com.sap.oss.smarttestpicker.runtime.RuntimeJsonSerializer;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryEventRecordingTest {
	@Test
	void successfulAndFailedTerminalEventsPreserveBehaviorAndProceedOnce() throws Throwable {
		RuntimeContextService runtime = runtime();
		TestIdentity test = test("test-a");
		runtime.beginTest(test);
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime)) {
			RepositoryAdapterMetrics metrics = new RepositoryAdapterMetrics();
			CallerBoundaryAdvice advice = enabledAdvice(metrics);
			AtomicInteger proceeds = new AtomicInteger();
			Object expected = new Object();
			Method method = CrudRepository.class.getMethod("save", Object.class);
			assertSame(expected, advice.invoke(invocation(method, () -> {
				proceeds.incrementAndGet();
				return expected;
			})));

			Throwable repositoryFailure = new IllegalArgumentException("repository");
			Throwable actual = assertThrows(Throwable.class, () -> advice.invoke(invocation(method, () -> {
				proceeds.incrementAndGet();
				throw repositoryFailure;
			})));
			assertSame(repositoryFailure, actual);
			assertEquals(2, proceeds.get());
			String json = json(runtime);
			assertTrue(json.contains("\"jvmDescriptor\":\"(Ljava/lang/Object;)Ljava/lang/Object;\""));
			assertTrue(json.contains("\"repositoryKind\":\"SPRING_DATA_PROXY\""));
			assertTrue(json.contains("\"repositoryInterface\":\"" + OverloadedRepository.class.getName() + "\""));
			assertTrue(json.contains("\"beanName\":\"sampleRepository\""));
			assertTrue(json.contains("\"domainType\":\"example.SampleEntity\""));
			assertTrue(json.contains("\"evidenceSource\":\"SPRING_DATA\""));
			assertTrue(json.contains("\"certainty\":\"OBSERVED\""));
			assertTrue(json.contains("\"outcome\":\"SUCCEEDED\""));
			assertTrue(json.contains("\"outcome\":\"FAILED\""));
			assertEquals(2, metrics.snapshot().invocationsObserved());
			assertEquals(1, metrics.snapshot().successfulEventsAttempted());
			assertEquals(1, metrics.snapshot().failedEventsAttempted());
			assertEquals(2, metrics.snapshot().eventsRecorded());
		}
	}

	@Test
	void repeatedEventsDeduplicateWithCountAndOverloadsRemainDistinct() throws Throwable {
		RuntimeContextService runtime = runtime();
		runtime.beginTest(test("test-a"));
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime)) {
			CallerBoundaryAdvice advice = enabledAdvice(new RepositoryAdapterMetrics());
			Method stringMethod = OverloadedRepository.class.getMethod("find", String.class);
			Method longMethod = OverloadedRepository.class.getMethod("find", long.class);
			advice.invoke(invocation(stringMethod, () -> "one"));
			advice.invoke(invocation(stringMethod, () -> "two"));
			advice.invoke(invocation(longMethod, () -> "three"));
			String json = json(runtime);
			assertTrue(json.contains("\"jvmDescriptor\":\"(Ljava/lang/String;)Ljava/lang/String;\""));
			assertTrue(json.contains("\"jvmDescriptor\":\"(J)Ljava/lang/String;\""));
			assertTrue(json.contains("\"count\":2"));
			assertEquals(json, json(runtime));
		}
	}

	@Test
	void runtimeUnavailableAndRecordingFailureNeverChangeRepositoryBehavior() throws Throwable {
		RepositoryAdapterMetrics unavailableMetrics = new RepositoryAdapterMetrics();
		CallerBoundaryAdvice unavailable = enabledAdvice(unavailableMetrics);
		Object result = new Object();
		assertSame(result, unavailable.invoke(invocation(sampleMethod(), () -> result)));
		assertEquals(1, unavailableMetrics.snapshot().runtimeUnavailable());

		RepositoryAdapterMetrics failedMetrics = new RepositoryAdapterMetrics();
		CallerBoundaryAdvice failingRecorder = advice(failedMetrics, event -> {
			throw new IllegalStateException("recording");
		});
		failingRecorder.enableAfterAudit();
		assertSame(result, failingRecorder.invoke(invocation(sampleMethod(), () -> result)));
		Throwable repositoryFailure = new UnsupportedOperationException("original");
		Throwable actual = assertThrows(Throwable.class,
				() -> failingRecorder.invoke(invocation(sampleMethod(), () -> { throw repositoryFailure; })));
		assertSame(repositoryFailure, actual);
		assertEquals(2, failedMetrics.snapshot().recordingFailures());
		assertTrue(failedMetrics.snapshot().recordingNanos() >= 0);
	}

	@Test
	void activeNoActiveAndLateAttributionUseExistingRuntimeSemantics() throws Throwable {
		RuntimeContextService runtime = runtime();
		CallerBoundaryAdvice advice = enabledAdvice(new RepositoryAdapterMetrics());
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime)) {
			advice.invoke(invocation(sampleMethod(), () -> null));
			assertTrue(json(runtime).contains("\"reason\":\"NO_ACTIVE_TEST\""));

			TestIdentity active = test("active");
			runtime.beginTest(active);
			advice.invoke(invocation(sampleMethod(), () -> null));
			runtime.endTest(active, TestResult.successful());
			advice.invoke(invocation(sampleMethod(), () -> null));
			String json = json(runtime);
			assertTrue(json.contains("\"testId\": \"active\""));
			assertTrue(json.contains("\"reason\":\"LATE_EVENT\""));
		}
	}

	@Test
	void sequentialTestsDoNotContaminateEachOther() throws Throwable {
		RuntimeContextService runtime = runtime();
		CallerBoundaryAdvice advice = enabledAdvice(new RepositoryAdapterMetrics());
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime)) {
			TestIdentity first = test("first");
			runtime.beginTest(first);
			advice.invoke(invocation(OverloadedRepository.class.getMethod("find", String.class), () -> "first"));
			runtime.endTest(first, TestResult.successful());
			TestIdentity second = test("second");
			runtime.beginTest(second);
			advice.invoke(invocation(OverloadedRepository.class.getMethod("find", long.class), () -> "second"));
			runtime.endTest(second, TestResult.successful());
			String json = json(runtime);
			int firstStart = json.indexOf("\"testId\": \"first\"");
			int secondStart = json.indexOf("\"testId\": \"second\"");
			String firstBucket = json.substring(firstStart, secondStart);
			String secondBucket = json.substring(secondStart);
			assertTrue(firstBucket.contains("(Ljava/lang/String;)Ljava/lang/String;"));
			assertFalse(firstBucket.contains("(J)Ljava/lang/String;"));
			assertTrue(secondBucket.contains("(J)Ljava/lang/String;"));
		}
	}

	@Test
	void unauditedAdviceEmitsNothingAndMetricsClearOnClose() throws Throwable {
		RuntimeContextService runtime = runtime();
		runtime.beginTest(test("test-a"));
		RepositoryAdapterMetrics metrics = new RepositoryAdapterMetrics();
		CallerBoundaryAdvice advice = advice(metrics, RuntimeEventRecorder.SHARED_RUNTIME);
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime)) {
			advice.invoke(invocation(sampleMethod(), () -> null));
			assertTrue(json(runtime).contains("\"repositories\": []"));
			assertEquals(0, metrics.snapshot().invocationsObserved());
		}
		metrics.destroy();
		assertEquals(new RepositoryAdapterMetrics.Snapshot(0, 0, 0, 0, 0, 0, 0), metrics.snapshot());
	}

	private static CallerBoundaryAdvice enabledAdvice(RepositoryAdapterMetrics metrics) {
		CallerBoundaryAdvice advice = advice(metrics, RuntimeEventRecorder.SHARED_RUNTIME);
		advice.enableAfterAudit();
		return advice;
	}

	private static CallerBoundaryAdvice advice(RepositoryAdapterMetrics metrics, RuntimeEventRecorder recorder) {
		return new CallerBoundaryAdvice(eligibility(), metrics, recorder);
	}

	private static RepositoryEligibility eligibility() {
		return new RepositoryEligibility("sampleRepository", OverloadedRepository.class.getName(),
				"example.SampleEntity", true, RepositoryEligibilityReason.ELIGIBLE, RepositoryProxyKind.JDK,
				List.of(OverloadedRepository.class.getName()), 0, List.of(), true, "context");
	}

	private static Method sampleMethod() throws NoSuchMethodException {
		return OverloadedRepository.class.getMethod("find", String.class);
	}

	private static MethodInvocation invocation(Method method, ThrowingSupplier supplier) {
		return new MethodInvocation() {
			@Override public Method getMethod() { return method; }
			@Override public Object[] getArguments() { return new Object[0]; }
			@Override public Object proceed() throws Throwable { return supplier.get(); }
			@Override public Object getThis() { return this; }
			@Override public AccessibleObject getStaticPart() { return method; }
		};
	}

	private static RuntimeContextService runtime() {
		return new RuntimeContextService(new RuntimeEventAggregator("run", "jvm"));
	}

	private static TestIdentity test(String id) {
		return new TestIdentity(id, id, "example.Test", id, "junit-jupiter", "run", "jvm");
	}

	private static String json(RuntimeContextService runtime) {
		return new RuntimeJsonSerializer().serialize(runtime.aggregator());
	}

	interface OverloadedRepository {
		String find(String value);
		String find(long value);
	}

	@FunctionalInterface
	private interface ThrowingSupplier {
		Object get() throws Throwable;
	}
}
