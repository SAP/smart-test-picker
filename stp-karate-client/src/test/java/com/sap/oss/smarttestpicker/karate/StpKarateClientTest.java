// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import com.sun.net.httpserver.HttpServer;
import com.intuit.karate.Runner;
import com.intuit.karate.Results;
import com.intuit.karate.http.HttpRequest;
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
	@org.junit.jupiter.api.io.TempDir java.nio.file.Path manifests;
	private final List<StpKarateHook> hooks = new java.util.ArrayList<>();
	private RecordingServer server;

	@BeforeEach void startServer() throws Exception { server = new RecordingServer(); }
	@AfterEach void stopServer() throws Exception {
		server.close(); assertTrue(server.headerErrors.isEmpty(), server.headerErrors.toString());
		for (var hook : hooks) {
			assertTrue(java.nio.file.Files.isRegularFile(hook.manifestPath()));
			Map<?, ?> doc = (Map<?, ?>) com.intuit.karate.JsonUtils.fromJson(java.nio.file.Files.readString(hook.manifestPath()));
			assertEquals(1, ((Number)doc.get("schemaVersion")).intValue());
			assertEquals(hook.runId(), ((Map<?, ?>)doc.get("source")).get("runId"));
		}
	}

	@Test void enrichesEveryRequestInOneScenarioWithOneSuiteAndTestIdentity() {
		Results result = run("classpath:features/workflow.feature", "Suite-A", Map.of());
		assertEquals(0, result.getFailCount(), result.getErrorMessages());
		assertEquals(1, result.getScenariosPassed());
		assertEquals(2, server.records.size());
		assertEquals("step-one", server.records.get(0).operation);
		assertEquals("step-two", server.records.get(1).operation);
		assertRequestIdentities(server.records, "Suite-A");
		assertEquals(server.records.get(0).testId, server.records.get(1).testId,
				"all HTTP calls from one Scenario must keep one Karate execution identity");
		assertNotEquals(server.records.get(0).requestId, server.records.get(1).requestId);
	}

	@Test void sameKarateScenarioInDifferentSuitesHasDifferentSuiteIdentity() {
		Results suiteA = run("classpath:features/single.feature", "Suite-A", Map.of());
		RecordedRequest requestA = server.records.get(0);
		server.records.clear();
		Results suiteB = run("classpath:features/single.feature", "Suite-B", Map.of());
		RecordedRequest requestB = server.records.get(0);
		assertEquals(0, suiteA.getFailCount(), suiteA.getErrorMessages());
		assertEquals(0, suiteB.getFailCount(), suiteB.getErrorMessages());
		assertEquals(requestA.testId, requestB.testId, "TestID represents the same Karate scenario execution");
		assertNotEquals(requestA.requestId, requestB.requestId, "repeated executions get independent request IDs");
		assertNotEquals(requestA.suiteId, requestB.suiteId);
		assertEquals("Suite-A", requestA.suiteId);
		assertEquals("Suite-B", requestB.suiteId);
	}

	@Test void distinctFeaturesAndParallelScenariosDoNotShareTestIdentity() {
		Results parallel = runner("classpath:features/parallel.feature", "Suite-Parallel", Map.of()).parallel(2);
		assertEquals(0, parallel.getFailCount(), parallel.getErrorMessages());
		assertEquals(2, parallel.getScenariosPassed());
		assertManifestMatchesWire(hooks.get(hooks.size() - 1), server.records);
		assertEquals(2, server.records.size());
		assertNotEquals(server.records.get(0).testId, server.records.get(1).testId);
		assertNotEquals(server.records.get(0).requestId, server.records.get(1).requestId);
		assertTrue(server.records.stream().allMatch(record -> record.suiteId.equals("Suite-Parallel")));
		assertTrue(server.records.stream().allMatch(RecordedRequest::concurrentArrival),
				"both parallel HTTP requests must arrive before either server response is released");
		Set<String> parallelIds = server.records.stream().map(RecordedRequest::testId).collect(java.util.stream.Collectors.toSet());

		server.records.clear();
		Results otherFeature = run("classpath:features/other.feature", "Suite-Parallel", Map.of());
		assertEquals(0, otherFeature.getFailCount(), otherFeature.getErrorMessages());
		assertFalse(parallelIds.contains(server.records.get(0).testId), "a different feature must have a different TestID");
	}

	@Test void scenarioOutlineRowsHaveDistinctRuntimeTestIds() {
		Results result = run("classpath:features/outline.feature", "Suite-Outline", Map.of());
		assertEquals(0, result.getFailCount(), result.getErrorMessages());
		assertEquals(2, result.getScenariosPassed(), "Karate expands the two example rows into two Scenario executions");
		assertEquals(2, server.records.size());
		assertNotEquals(server.records.get(0).testId, server.records.get(1).testId);
		assertNotEquals(server.records.get(0).requestId, server.records.get(1).requestId);
		assertRequestIdentities(server.records, "Suite-Outline");
	}

	@Test void allowsMatchingExistingStpHeadersAndFailsOnConflictsBeforeSend() {
		String feature = "classpath:features/preconfigured-headers.feature";
		Results initial = run(feature, "Suite-Headers", Map.of());
		assertEquals(0, initial.getFailCount(), initial.getErrorMessages());
		RecordedRequest generated = server.records.get(0);
		server.records.clear();

		Results matching = run(feature, "Suite-Headers", Map.of(StpKarateClient.BAGGAGE_HEADER,
				baggage(generated.suiteId, generated.testId)));
		assertEquals(0, matching.getFailCount(), matching.getErrorMessages());
		assertRequestIdentities(server.records, "Suite-Headers");
		assertEquals(generated.testId, server.records.get(0).testId);
		assertNotEquals(generated.requestId, server.records.get(0).requestId, "matching logical identity still gets a fresh request ID");
		server.records.clear();

		Results conflict = run(feature, "Suite-Headers", Map.of(StpKarateClient.BAGGAGE_HEADER,
				baggage("wrong-suite", generated.testId)));
		assertEquals(1, conflict.getFailCount());
		assertTrue(conflict.getErrorMessages().contains("Conflicting STP baggage entry"),
				conflict.getErrorMessages());
		assertTrue(server.records.isEmpty(), "conflicting identity must fail before the request is sent");
		HttpRequest malformed = new HttpRequest();
		malformed.putHeader(StpKarateClient.BAGGAGE_HEADER, "stp.request.id=not-a-uuid");
		assertThrows(IllegalStateException.class, () -> StpKarateClient.headers(malformed.getHeaders(), "Suite-A", "features/a.feature", "example scenario", null));
		HttpRequest duplicate = new HttpRequest();
		duplicate.putHeader(StpKarateClient.BAGGAGE_HEADER, "stp.request.id=" + java.util.UUID.randomUUID()
				+ ",stp.request.id=" + java.util.UUID.randomUUID());
		assertThrows(IllegalStateException.class, () -> StpKarateClient.headers(duplicate.getHeaders(), "Suite-A", "features/a.feature", "example scenario", null));
	}

	@Test void readsSuiteIdFromTheRunLevelSystemProperty() {
		String previous = System.getProperty(StpKarateClient.SUITE_ID_PROPERTY);
		String previousOutput = System.getProperty(StpKarateHook.OUTPUT_DIRECTORY_PROPERTY);
		try {
			System.setProperty(StpKarateClient.SUITE_ID_PROPERTY, "Suite-System-Property");
			System.setProperty(StpKarateHook.OUTPUT_DIRECTORY_PROPERTY, manifests.toString());
			Results result = Runner.path("classpath:features/single.feature")
					.hook(StpKarateHook.fromSystemProperties())
					.systemProperty("fixture.baseUrl", server.baseUrl()).outputHtmlReport(false).parallel(1);
			assertEquals(0, result.getFailCount(), result.getErrorMessages());
			assertEquals("Suite-System-Property", server.records.get(0).suiteId);
		} finally {
			if (previousOutput == null) System.clearProperty(StpKarateHook.OUTPUT_DIRECTORY_PROPERTY);
			else System.setProperty(StpKarateHook.OUTPUT_DIRECTORY_PROPERTY, previousOutput);
			if (previous == null) System.clearProperty(StpKarateClient.SUITE_ID_PROPERTY);
			else System.setProperty(StpKarateClient.SUITE_ID_PROPERTY, previous);
		}
	}

	@Test void rejectsInvalidSuiteIdAndHeaderConflicts() {
		assertThrows(IllegalArgumentException.class,
				() -> StpKarateClient.headers(Map.of(), "bad\r\nheader", "features/a.feature", "example scenario", null));
		HttpRequest request = new HttpRequest();
		request.putHeader(StpKarateClient.BAGGAGE_HEADER, StpKarateClient.TEST_ID_KEY + "=wrong");
		IllegalStateException conflict = assertThrows(IllegalStateException.class,
				() -> StpKarateClient.headers(request.getHeaders(), "Suite-A", "features/a.feature", "example scenario", null));
		assertTrue(conflict.getMessage().contains(StpKarateClient.TEST_ID_KEY));
	}

	private Results run(String feature, String suiteId, Map<String, Object> injectedHeaders) {
		int before = server.records.size();
		var result = runner(feature, suiteId, injectedHeaders).parallel(1);
		assertManifestMatchesWire(hooks.get(hooks.size() - 1), server.records.subList(before, server.records.size()));
		return result;
	}

	@SuppressWarnings("unchecked")
	private void assertManifestMatchesWire(StpKarateHook hook, List<RecordedRequest> expected) {
		try {
			Map<?, ?> doc = (Map<?, ?>) com.intuit.karate.JsonUtils.fromJson(java.nio.file.Files.readString(hook.manifestPath()));
			String suite = (String)((Map<?, ?>)doc.get("source")).get("suiteId");
			var entries = (List<Map<String, Object>>)doc.get("requests");
			assertEquals(expected.size(), entries.size());
			assertEquals(expected.stream().map(RecordedRequest::requestId).collect(java.util.stream.Collectors.toSet()),
					entries.stream().map(r -> r.get("requestId")).collect(java.util.stream.Collectors.toSet()));
			for (Map<String, Object> r : entries) {
				var wire = expected.stream().filter(x -> x.requestId.equals(r.get("requestId"))).findFirst().orElseThrow();
				assertEquals(suite, wire.suiteId);
				assertEquals(wire.testId, r.get("testId"));
				assertEquals(wire.httpMethod, r.get("httpMethod"));
				assertFalse(((String)r.get("requestUri")).contains("?"));
			}
		} catch (java.io.IOException failure) { throw new AssertionError(failure); }
	}

	private Runner.Builder runner(String feature, String suiteId, Map<String, Object> injectedHeaders) {
		var hook = new StpKarateHook(suiteId, manifests);
		hooks.add(hook);
		return Runner.path(feature)
				.hook(hook)
				.systemProperty("fixture.baseUrl", server.baseUrl())
				.systemProperty("fixture.headers", com.intuit.karate.JsonUtils.toJson(injectedHeaders))
				.outputHtmlReport(false)
				.reportDir("build/karate-reports/" + java.util.UUID.randomUUID());
	}

	private static void assertRequestIdentities(List<RecordedRequest> records, String suiteId) {
		assertTrue(records.stream().allMatch(record -> suiteId.equals(record.suiteId)), records.toString());
		assertTrue(records.stream().allMatch(record -> record.testId != null && record.testId.matches("[\\p{L}\\p{N}-]+-[0-9a-f]{12}(-[0-9a-f]{12})?")), records.toString());
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

	@Test void retryAttemptsAndNextRequestHaveUniqueIdsButKeepScenarioIdentity() {
		Results result = run("classpath:features/retry.feature", "Suite-Retry", Map.of());
		assertEquals(0, result.getFailCount(), result.getErrorMessages());
		assertEquals(List.of("retry", "retry", "retry", "after-retry"),
				server.records.stream().map(RecordedRequest::operation).toList());
		assertRequestIdentities(server.records, "Suite-Retry");
		assertEquals(1, server.records.stream().map(RecordedRequest::testId).distinct().count());
	}

	@Test void concurrentRetriesDoNotReuseOrMixRequestIds() {
		Results result = runner("classpath:features/parallel-retry.feature", "Suite-Retry", Map.of()).parallel(2);
		assertEquals(0, result.getFailCount(), result.getErrorMessages());
		assertEquals(6, server.records.size());
		assertManifestMatchesWire(hooks.get(hooks.size() - 1), server.records);
		assertRequestIdentities(server.records, "Suite-Retry");
		assertTrue(server.records.stream().allMatch(RecordedRequest::concurrentArrival));
		var groups = server.records.stream().collect(java.util.stream.Collectors.groupingBy(RecordedRequest::operation));
		assertEquals(2, groups.size());
		for (var group : groups.values()) {
			assertEquals(3, group.size());
			assertEquals(1, group.stream().map(RecordedRequest::testId).distinct().count());
		}
		assertEquals(2, server.records.stream().map(RecordedRequest::testId).distinct().count());
	}

	@Test void exhaustedRetriesStillHaveUniqueRequestIds() {
		Results result = run("classpath:features/exhausted-retry.feature", "Suite-Retry", Map.of());
		assertEquals(1, result.getFailCount());
		assertTrue(result.getErrorMessages().contains("retry"), result.getErrorMessages());
		assertEquals(2, server.records.size(), "Karate retry count is the total attempt limit");
		assertRequestIdentities(server.records, "Suite-Retry");
	}

	@Test void sameSuiteAndTestRepeatedGetFreshRequestIds() {
		assertEquals(0, run("classpath:features/single.feature", "Suite-A", Map.of()).getFailCount());
		assertEquals(0, run("classpath:features/single.feature", "Suite-A", Map.of()).getFailCount());
		assertEquals(2, server.records.size());
		assertEquals(server.records.get(0).testId, server.records.get(1).testId);
		assertRequestIdentities(server.records, "Suite-A");
	}

	@Test void preservesUnrelatedBaggageAndFeatureHeaders() {
		Results result = run("classpath:features/preconfigured-headers.feature", "Suite-A",
				Map.of("Baggage", "vendor=value", "X-Feature", "preserved"));
		assertEquals(0, result.getFailCount(), result.getErrorMessages());
		assertTrue(server.records.get(0).baggage.contains("vendor=value"));
		assertEquals("preserved", server.records.get(0).featureHeader);
	}

	@Test void preexistingRequestIdIsRejectedBeforeNetworkRatherThanReused() {
		for (String value : List.of(java.util.UUID.randomUUID().toString(), "bad", "",
				java.util.UUID.randomUUID() + ",stp.request.id=" + java.util.UUID.randomUUID())) {
			Results result = run("classpath:features/preconfigured-headers.feature", "Suite-A",
					Map.of("baggage", "stp.request.id=" + value));
			assertEquals(1, result.getFailCount());
			assertTrue(result.getErrorMessages().contains("Preexisting STP RequestID"), result.getErrorMessages());
			assertTrue(server.records.isEmpty());
		}
	}

	@Test void manifestRecordsPostGetDeleteWithoutBodiesCookiesOrQuerySecrets() throws Exception {
		assertEquals(0, run("classpath:features/methods.feature", "Suite-Methods", Map.of()).getFailCount());
		assertEquals(List.of("POST", "GET", "DELETE"), server.records.stream().map(RecordedRequest::httpMethod).toList());
		assertEquals(1, server.records.stream().map(RecordedRequest::testId).distinct().count());
		assertRequestIdentities(server.records, "Suite-Methods");
		String json = java.nio.file.Files.readString(hooks.get(0).manifestPath());
		for (String secret : List.of("body-secret", "query-secret", "cookie-secret", "fixture-token"))
			assertFalse(json.contains(secret), secret);
	}

	@Test void runWithoutHttpStillWritesOneEmptyManifest() throws Exception {
		assertEquals(0, run("classpath:features/no-http.feature", "Suite-NoHttp", Map.of()).getFailCount());
		assertTrue(server.records.isEmpty());
		try (var files = java.nio.file.Files.list(manifests)) { assertEquals(1, files.count()); }
	}

	@Test void hookCannotBeReusedForAnotherRun() throws Exception {
		var hook = new StpKarateHook("Suite-A", manifests);
		assertEquals(0, bareRunner(hook).parallel(1).getFailCount());
		String original = java.nio.file.Files.readString(hook.manifestPath());
		var failure = assertThrows(RuntimeException.class, () -> bareRunner(hook).parallel(1));
		assertTrue(failure.toString().contains("new StpKarateHook"), failure.toString());
		assertEquals(original, java.nio.file.Files.readString(hook.manifestPath()));
		assertEquals(1, server.records.size());
	}

	@Test void finalWriteFailureFailsRunnerAndNeverOverwritesExistingOutput() throws Exception {
		var hook = new StpKarateHook("Suite-A", manifests);
		java.nio.file.Files.writeString(hook.manifestPath(), "keep-existing-file");
		var failure = assertThrows(RuntimeException.class, () -> bareRunner(hook).parallel(1));
		assertTrue(failure.toString().contains(hook.manifestPath().toString()), failure.toString());
		assertEquals("keep-existing-file", java.nio.file.Files.readString(hook.manifestPath()));
	}

	@Test void outputDirectoryIsRequiredForSystemPropertyConfiguration() {
		String previous = System.getProperty(StpKarateHook.OUTPUT_DIRECTORY_PROPERTY);
		try {
			System.clearProperty(StpKarateHook.OUTPUT_DIRECTORY_PROPERTY);
			assertThrows(IllegalArgumentException.class, () -> new StpKarateHook("suite"));
			System.setProperty(StpKarateHook.OUTPUT_DIRECTORY_PROPERTY, " ");
			assertThrows(IllegalArgumentException.class, () -> new StpKarateHook("suite"));
		} finally {
			if (previous == null) System.clearProperty(StpKarateHook.OUTPUT_DIRECTORY_PROPERTY);
			else System.setProperty(StpKarateHook.OUTPUT_DIRECTORY_PROPERTY, previous);
		}
	}

	private Runner.Builder bareRunner(StpKarateHook hook) {
		return Runner.path("classpath:features/single.feature").hook(hook)
				.systemProperty("fixture.baseUrl", server.baseUrl()).outputHtmlReport(false)
				.reportDir("build/karate-reports/" + java.util.UUID.randomUUID());
	}

	private record RecordedRequest(String operation, String suiteId, String testId, String requestId,
			boolean concurrentArrival, String baggage, String featureHeader, String httpMethod) { }
	private record TestIdentity(String testSuiteId, String testId, String requestId) { }

	private static final class RecordingServer implements AutoCloseable {
		private final HttpServer httpServer;
		private final CountDownLatch parallelArrivals = new CountDownLatch(2);
		private final List<RecordedRequest> records = new CopyOnWriteArrayList<>();
		private final Map<String, java.util.concurrent.atomic.AtomicInteger> attempts = new java.util.concurrent.ConcurrentHashMap<>();
		private final List<String> headerErrors = new CopyOnWriteArrayList<>();
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
				records.add(new RecordedRequest(operation, identity.testSuiteId(), identity.testId(), identity.requestId(), concurrent,
						exchange.getRequestHeaders().getFirst("baggage"), exchange.getRequestHeaders().getFirst("X-Feature"), exchange.getRequestMethod()));
				assertHeader(exchange, "Authorization", "Bearer fixture-token");
				assertHeader(exchange, "X-Configured", "preserved");
				int attempt = attempts.computeIfAbsent(operation, ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
				int status = !operation.equals("after-retry") && operation.contains("retry")
						&& (operation.contains("exhausted") || attempt <= 2) ? 503 : 200;
				byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(status, response.length);
				try (var output = exchange.getResponseBody()) { output.write(response); }
			});
			httpServer.start();
		}
		private void assertHeader(com.sun.net.httpserver.HttpExchange exchange, String key, String expected) {
			if (!expected.equals(exchange.getRequestHeaders().getFirst(key))) headerErrors.add(key);
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
	private static String baggage(String suite, String test) {
		return StpKarateClient.SUITE_ID_KEY + "=" + suite + "," + StpKarateClient.TEST_ID_KEY + "=" + test;
	}
}
