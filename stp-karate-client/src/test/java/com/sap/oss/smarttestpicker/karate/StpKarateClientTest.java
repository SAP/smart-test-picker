// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import com.sun.net.httpserver.HttpServer;
import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import io.karatelabs.http.HttpRequest;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StpKarateClientTest {
	private RecordingServer server;

	@BeforeEach void startServer() throws Exception { server = new RecordingServer(); }
	@AfterEach void stopServer() { server.close(); }

	@Test void enrichesEveryRequestInOneScenarioWithOneSuiteAndTestIdentity() {
		SuiteResult result = run("classpath:features/workflow.feature", "Suite-A", Map.of());
		assertTrue(result.isPassed(), result.getErrors().toString());
		assertEquals(1, result.getScenarioPassedCount());
		assertEquals(2, server.records.size());
		assertEquals("step-one", server.records.get(0).operation);
		assertEquals("step-two", server.records.get(1).operation);
		assertRequestIdentities(server.records, "Suite-A");
		assertEquals(server.records.get(0).testId, server.records.get(1).testId,
				"all HTTP calls from one Scenario must keep one Karate execution identity");
		assertNotEquals(server.records.get(0).requestId, server.records.get(1).requestId);
	}

	@Test void sameKarateScenarioInDifferentSuitesHasDifferentSuiteIdentity() {
		SuiteResult suiteA = run("classpath:features/single.feature", "Suite-A", Map.of());
		RecordedRequest requestA = server.records.getFirst();
		server.records.clear();
		SuiteResult suiteB = run("classpath:features/single.feature", "Suite-B", Map.of());
		RecordedRequest requestB = server.records.getFirst();
		assertTrue(suiteA.isPassed(), suiteA.getErrors().toString());
		assertTrue(suiteB.isPassed(), suiteB.getErrors().toString());
		assertEquals(requestA.testId, requestB.testId, "TestID represents the same Karate scenario execution");
		assertNotEquals(requestA.requestId, requestB.requestId, "repeated executions get independent request IDs");
		assertNotEquals(requestA.suiteId, requestB.suiteId);
		assertEquals("Suite-A", requestA.suiteId);
		assertEquals("Suite-B", requestB.suiteId);
	}

	@Test void distinctFeaturesAndParallelScenariosDoNotShareTestIdentity() {
		SuiteResult parallel = runner("classpath:features/parallel.feature", "Suite-Parallel", Map.of()).parallel(2);
		assertTrue(parallel.isPassed(), parallel.getErrors().toString());
		assertEquals(2, parallel.getScenarioPassedCount());
		assertEquals(2, server.records.size());
		assertNotEquals(server.records.get(0).testId, server.records.get(1).testId);
		assertNotEquals(server.records.get(0).requestId, server.records.get(1).requestId);
		assertTrue(server.records.stream().allMatch(record -> record.suiteId.equals("Suite-Parallel")));
		assertTrue(server.records.stream().allMatch(RecordedRequest::concurrentArrival),
				"both parallel HTTP requests must arrive before either server response is released");
		Set<String> parallelIds = server.records.stream().map(RecordedRequest::testId).collect(java.util.stream.Collectors.toSet());

		server.records.clear();
		SuiteResult otherFeature = run("classpath:features/other.feature", "Suite-Parallel", Map.of());
		assertTrue(otherFeature.isPassed(), otherFeature.getErrors().toString());
		assertFalse(parallelIds.contains(server.records.getFirst().testId), "a different feature must have a different TestID");
	}

	@Test void scenarioOutlineRowsHaveDistinctRuntimeTestIds() {
		SuiteResult result = run("classpath:features/outline.feature", "Suite-Outline", Map.of());
		assertTrue(result.isPassed(), result.getErrors().toString());
		assertEquals(2, result.getScenarioPassedCount(), "Karate expands the two example rows into two Scenario executions");
		assertEquals(2, server.records.size());
		assertNotEquals(server.records.get(0).testId, server.records.get(1).testId);
		assertNotEquals(server.records.get(0).requestId, server.records.get(1).requestId);
		assertRequestIdentities(server.records, "Suite-Outline");
	}

	@Test void allowsMatchingExistingStpHeadersAndFailsOnConflictsBeforeSend() {
		String feature = "classpath:features/preconfigured-headers.feature";
		SuiteResult initial = run(feature, "Suite-Headers", Map.of());
		assertTrue(initial.isPassed(), initial.getErrors().toString());
		RecordedRequest generated = server.records.getFirst();
		server.records.clear();

		SuiteResult matching = run(feature, "Suite-Headers", Map.of(StpKarateClient.BAGGAGE_HEADER,
				baggage(generated.suiteId, generated.testId, generated.requestId)));
		assertTrue(matching.isPassed(), matching.getErrors().toString());
		assertRequestIdentities(server.records, "Suite-Headers");
		assertEquals(generated.testId, server.records.getFirst().testId);
		assertEquals(generated.requestId, server.records.getFirst().requestId, "matching pre-existing RequestID is reused");
		server.records.clear();

		SuiteResult conflict = run(feature, "Suite-Headers", Map.of(StpKarateClient.BAGGAGE_HEADER,
				baggage("wrong-suite", generated.testId, generated.requestId)));
		assertEquals(1, conflict.getScenarioFailedCount());
		assertTrue(conflict.getErrors().stream().anyMatch(error -> error.contains("Conflicting STP baggage entry")),
				conflict.getErrors().toString());
		assertTrue(server.records.isEmpty(), "conflicting identity must fail before the request is sent");
		HttpRequest malformed = new HttpRequest();
		malformed.putHeader(StpKarateClient.BAGGAGE_HEADER, "stp.request.id=not-a-uuid");
		assertThrows(IllegalStateException.class, () -> StpKarateClient.headers(malformed, "Suite-A", "features/a.feature", 1, 2, -1));
		HttpRequest duplicate = new HttpRequest();
		duplicate.putHeader(StpKarateClient.BAGGAGE_HEADER, "stp.request.id=" + java.util.UUID.randomUUID()
				+ ",stp.request.id=" + java.util.UUID.randomUUID());
		assertThrows(IllegalStateException.class, () -> StpKarateClient.headers(duplicate, "Suite-A", "features/a.feature", 1, 2, -1));
	}

	@Test void readsSuiteIdFromTheRunLevelSystemProperty() {
		SuiteResult result = runner("classpath:features/single.feature", "Suite-System-Property", Map.of()).parallel(1);
		assertTrue(result.isPassed(), result.getErrors().toString());
		assertEquals("Suite-System-Property", server.records.getFirst().suiteId);
	}

	@Test void rejectsInvalidSuiteIdAndHeaderConflicts() {
		assertThrows(IllegalArgumentException.class,
				() -> StpKarateClient.headers(new HttpRequest(), "bad\r\nheader", "features/a.feature", 1, 2, -1));
		HttpRequest request = new HttpRequest();
		request.putHeader(StpKarateClient.BAGGAGE_HEADER, StpKarateClient.TEST_ID_KEY + "=wrong");
		IllegalStateException conflict = assertThrows(IllegalStateException.class,
				() -> StpKarateClient.headers(request, "Suite-A", "features/a.feature", 1, 2, -1));
		assertTrue(conflict.getMessage().contains(StpKarateClient.TEST_ID_KEY));
	}

	private SuiteResult run(String feature, String suiteId, Map<String, Object> injectedHeaders) {
		return runner(feature, suiteId, injectedHeaders).parallel(1);
	}

	private Runner.Builder runner(String feature, String suiteId, Map<String, Object> injectedHeaders) {
		return Runner.path(feature)
				.global("baseUrl", server.baseUrl())
				.global("injectedHeaders", injectedHeaders)
				.systemProperty(StpKarateClient.SUITE_ID_PROPERTY, suiteId);
	}

	private static void assertRequestIdentities(List<RecordedRequest> records, String suiteId) {
		assertTrue(records.stream().allMatch(record -> suiteId.equals(record.suiteId)), records.toString());
		assertTrue(records.stream().allMatch(record -> record.testId != null && record.testId.matches("karate-[0-9a-f]{64}")), records.toString());
		assertTrue(records.stream().allMatch(record -> record.requestId != null && record.requestId.matches("[0-9a-f-]{36}")), records.toString());
		assertEquals(records.size(), records.stream().map(RecordedRequest::requestId).distinct().count(), "each HTTP request gets a distinct RequestID");
	}

	@Test void resolvesSuiteIdFromEitherConfiguredSourceAndRejectsConflicts() {
		assertEquals("property", StpKarateClient.resolveSuiteId("property", null));
		assertEquals("environment", StpKarateClient.resolveSuiteId(null, "environment"));
		assertEquals("same", StpKarateClient.resolveSuiteId("same", "same"));
		assertThrows(IllegalArgumentException.class, () -> StpKarateClient.resolveSuiteId("a", "b"));
		assertThrows(IllegalArgumentException.class, () -> StpKarateClient.resolveSuiteId(null, null));
	}

	private record RecordedRequest(String operation, String suiteId, String testId, String requestId, boolean concurrentArrival) { }
	private record TestIdentity(String testSuiteId, String testId, String requestId) { }

	private static final class RecordingServer implements AutoCloseable {
		private final HttpServer httpServer;
		private final CountDownLatch parallelArrivals = new CountDownLatch(2);
		private final List<RecordedRequest> records = new CopyOnWriteArrayList<>();
		private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
		private RecordingServer() throws Exception {
			httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			httpServer.setExecutor(executor);
			httpServer.createContext("/record", exchange -> {
				String operation = queryValue(exchange.getRequestURI().getRawQuery(), "operation");
				boolean concurrent = false;
				if (operation.startsWith("parallel-")) {
					parallelArrivals.countDown();
					try { concurrent = parallelArrivals.await(3, TimeUnit.SECONDS); }
					catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
				}
				TestIdentity identity = identity(exchange.getRequestHeaders().getFirst(StpKarateClient.BAGGAGE_HEADER));
				records.add(new RecordedRequest(operation, identity.testSuiteId(), identity.testId(), identity.requestId(), concurrent));
				byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, response.length);
				try (var output = exchange.getResponseBody()) { output.write(response); }
			});
			httpServer.start();
		}
		private String baseUrl() { return "http://127.0.0.1:" + httpServer.getAddress().getPort(); }
		@Override public void close() { httpServer.stop(0); executor.shutdownNow(); }
		private static String queryValue(String query, String key) {
			for (String pair : query.split("&")) {
				String[] parts = pair.split("=", 2);
				if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(key))
					return parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
			}
			return "";
		}
	}

	private static TestIdentity identity(String header) {
		Context context = W3CBaggagePropagator.getInstance().extract(Context.root(), header,
				new TextMapGetter<String>() {
					@Override public Iterable<String> keys(String carrier) { return List.of(StpKarateClient.BAGGAGE_HEADER); }
					@Override public String get(String carrier, String key) { return carrier; }
				});
		Baggage baggage = Baggage.fromContext(context);
		return new TestIdentity(baggage.getEntryValue(StpKarateClient.SUITE_ID_KEY),
				baggage.getEntryValue(StpKarateClient.TEST_ID_KEY), baggage.getEntryValue(StpKarateClient.REQUEST_ID_KEY));
	}
	private static String baggage(String suite, String test, String request) {
		return StpKarateClient.SUITE_ID_KEY + "=" + suite + "," + StpKarateClient.TEST_ID_KEY + "=" + test
				+ "," + StpKarateClient.REQUEST_ID_KEY + "=" + request;
	}
}
