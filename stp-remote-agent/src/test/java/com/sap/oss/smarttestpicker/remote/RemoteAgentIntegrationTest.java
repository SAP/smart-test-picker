// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import example.remote.RemoteServletFixtureMain;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAgentIntegrationTest {
	@Test void servletRequestsAreAttributedByHeaderWithoutCrossRequestLeakage() throws Exception {
		Path output = Files.createTempDirectory("stp-remote-agent-").resolve("observations.json");
		Path agentJar = Path.of(System.getProperty("stp.remote.agent.jar"));
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String agentArgs = "output=" + output + ";includes=example.remote.";
		Path childLogFile = output.resolveSibling("server.log");
		Process process = new ProcessBuilder(java, "-javaagent:" + agentJar + "=" + agentArgs,
				"-cp", System.getProperty("java.class.path"), RemoteServletFixtureMain.class.getName())
				.redirectErrorStream(true).redirectOutput(childLogFile.toFile()).start();
		try {
			String first = awaitReady(process, childLogFile);
			URI root = URI.create("http://127.0.0.1:" + first);
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			assertEquals("a", get(client, root.resolve("a"), "test-A"));
			assertEquals("a", get(client, root.resolve("a"), null));
			assertEquals("b", get(client, root.resolve("b"), "test-B"));
			assertEquals("interface-default", get(client, root.resolve("interface"), "test-interface"));
			assertEquals(500, status(client, root.resolve("fail"), "test-fail"));
			assertEquals("forward-target", get(client, root.resolve("forward-source"), "test-forward"));
			assertTrue(get(client, root.resolve("include-source"), "test-include").contains("include-target"));
			assertEquals("async-target", get(client, root.resolve("async"), "test-async"));
			assertDifferentThreads(get(client, root.resolve("async-start-a"), "test-async-A"));
			assertDifferentThreads(get(client, root.resolve("async-start-b"), "test-async-B"));
			get(client, root.resolve("async-start-fail"), "test-async-failure");
			assertDifferentThreads(get(client, root.resolve("async-start"), null));
			get(client, root.resolve("async-listener-start"), "listener-start");
			assertEquals("auto-complete", get(client, root.resolve("async-listener-complete-a"), "listener-complete-A"));
			assertEquals("auto-complete", get(client, root.resolve("async-listener-complete-b"), "listener-complete-B"));
			get(client, root.resolve("async-listener-timeout"), "listener-timeout");
			get(client, root.resolve("async-listener-error"), "listener-error");
			assertEquals("auto-complete", get(client, root.resolve("async-listener-complete-a"), null));
			assertEquals("a", get(client, root.resolve("a"), null));
			CompletableFuture<String> concurrentA = async(client, root.resolve("a"), "test-A-concurrent");
			CompletableFuture<String> concurrentB = async(client, root.resolve("b"), "test-B-concurrent");
			assertEquals("a", concurrentA.get(10, TimeUnit.SECONDS));
			assertEquals("b", concurrentB.get(10, TimeUnit.SECONDS));
			CompletableFuture<String> asyncConcurrentA = async(client, root.resolve("async-start-a"), "test-async-concurrent-A");
			CompletableFuture<String> asyncConcurrentB = async(client, root.resolve("async-start-b"), "test-async-concurrent-B");
			assertDifferentThreads(asyncConcurrentA.get(10, TimeUnit.SECONDS));
			assertDifferentThreads(asyncConcurrentB.get(10, TimeUnit.SECONDS));
			CompletableFuture<String> listenerConcurrentA = async(client, root.resolve("async-listener-concurrent-a"), "listener-concurrent-A");
			CompletableFuture<String> listenerConcurrentB = async(client, root.resolve("async-listener-concurrent-b"), "listener-concurrent-B");
			assertEquals("auto-complete", listenerConcurrentA.get(10, TimeUnit.SECONDS));
			assertEquals("auto-complete", listenerConcurrentB.get(10, TimeUnit.SECONDS));
		} finally {
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
		}
		String childLog = Files.readString(childLogFile);
		String json = Files.readString(output);
		assertMethods(json, "test-A", "FixtureServlet#doGet", "FixtureService#handleA", "FixtureRepository#readA");
		assertMethods(json, "test-B", "FixtureServlet#doGet", "FixtureService#handleB", "FixtureRepository#readB");
		assertMethods(json, "test-interface", "FixtureService#handleInterface", "FixtureGreeting#greet()Ljava/lang/String;");
		assertMethods(json, "test-fail", "FixtureServlet#doGet", "FixtureService#fail", "FixtureRepository#fail");
		assertMethods(json, "test-forward", "FixtureService#forwardSource", "FixtureService#forwardTarget", "FixtureRepository#forwardTarget");
		assertMethods(json, "test-include", "FixtureService#includeSource", "FixtureService#includeTarget", "FixtureRepository#includeTarget");
		assertMethods(json, "test-async", "FixtureService#asyncTarget", "FixtureRepository#asyncTarget");
		assertMethods(json, "test-async-A", "FixtureService#asyncWorkA", "FixtureRepository#asyncWorkA");
		assertMethods(json, "test-async-B", "FixtureService#asyncWorkB", "FixtureRepository#asyncWorkB");
		assertMethods(json, "test-async-failure", "FixtureService#asyncFailure", "FixtureRepository#asyncFailure");
		assertMethods(json, "test-async-concurrent-A", "FixtureService#asyncWorkA", "FixtureRepository#asyncWorkA");
		assertMethods(json, "test-async-concurrent-B", "FixtureService#asyncWorkB", "FixtureRepository#asyncWorkB");
		assertMethods(json, "listener-start", "FixtureAsyncListener#onStartAsync", "FixtureAsyncListenerApplication#onStart",
				"FixtureRepository#listenerStart", "FixtureRepository#listenerComplete");
		assertMethods(json, "listener-complete-A", "FixtureAsyncListener#onComplete", "FixtureAsyncListenerApplication#onComplete",
				"FixtureRepository#listenerCompleteA");
		assertMethods(json, "listener-complete-B", "FixtureAsyncListener#onComplete", "FixtureAsyncListenerApplication#onComplete",
				"FixtureRepository#listenerCompleteB");
		assertMethods(json, "listener-timeout", "FixtureAsyncListener#onTimeout", "FixtureAsyncListenerApplication#onTimeout",
				"FixtureRepository#listenerTimeout");
		assertMethods(json, "listener-error", "FixtureAsyncListener#onError", "FixtureAsyncListenerApplication#onError",
				"FixtureRepository#listenerError");
		assertMethods(json, "listener-concurrent-A", "FixtureRepository#listenerCompleteA");
		assertMethods(json, "listener-concurrent-B", "FixtureRepository#listenerCompleteB");
		assertMethods(json, "test-A-concurrent", "FixtureService#handleA", "FixtureRepository#readA");
		assertMethods(json, "test-B-concurrent", "FixtureService#handleB", "FixtureRepository#readB");
		assertMethods(json, "test-A", "FixtureService#serviceBoundary", "FixtureRepository#servletService",
				"FixtureService#afterFilterChain", "FixtureRepository#afterFilterChain",
				"FixtureListenerApplication#initialized", "FixtureListenerApplication#destroyed",
				"FixtureRepository#listenerInitialized", "FixtureRepository#listenerDestroyed");
		assertMethods(json, "test-B", "FixtureService#serviceBoundary", "FixtureRepository#servletService",
				"FixtureService#afterFilterChain", "FixtureRepository#afterFilterChain");
		assertEquals(21, occurrences(json, "\"testExecutionId\":"), "headerless requests must have no STP observation");
		for (String id : new String[] {"test-A", "test-B", "test-interface", "test-fail", "test-forward",
				"test-include", "test-async", "test-async-A", "test-async-B", "test-async-failure",
				"test-async-concurrent-A", "test-async-concurrent-B", "test-A-concurrent", "test-B-concurrent",
				"listener-start", "listener-complete-A", "listener-complete-B", "listener-timeout", "listener-error",
				"listener-concurrent-A", "listener-concurrent-B"}) {
			assertEquals(1, occurrences(json, "\"testExecutionId\":\"" + id + "\""), "duplicate observation for " + id);
		}
		assertFalse(json.contains("FixtureRepository#readOrdinary"), "headerless request must not inherit a thread context");
		assertFalse(section(json, "test-async-A").contains("asyncWorkB"));
		assertFalse(section(json, "test-async-B").contains("asyncWorkA"));
		assertFalse(section(json, "test-async-concurrent-A").contains("asyncWorkB"));
		assertFalse(section(json, "test-async-concurrent-B").contains("asyncWorkA"));
		assertFalse(section(json, "listener-complete-A").contains("listenerCompleteB"));
		assertFalse(section(json, "listener-complete-B").contains("listenerCompleteA"));
		assertFalse(section(json, "listener-concurrent-A").contains("listenerCompleteB"));
		assertFalse(section(json, "listener-concurrent-B").contains("listenerCompleteA"));
		assertFalse(section(json, "test-A").contains("handleB"));
		assertFalse(section(json, "test-B").contains("handleA"));
		assertFalse(section(json, "test-A-concurrent").contains("handleB"));
		assertFalse(section(json, "test-B-concurrent").contains("handleA"));
		assertListenerCallbacksOverlap(childLog);
		assertTrue(childLog.contains("READY:"), childLog);
	}

	private static String awaitReady(Process process, Path childLog) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (System.nanoTime() < deadline && process.isAlive()) {
			if (Files.exists(childLog)) {
				for (String line : Files.readAllLines(childLog)) {
					if (line.startsWith("READY:")) return line.substring("READY:".length()).trim();
				}
			}
			Thread.sleep(50);
		}
		throw new AssertionError("server child exited before readiness: "
				+ (Files.exists(childLog) ? Files.readString(childLog) : "no child log"));
	}

	private static String get(HttpClient client, URI uri, String id) throws Exception {
		return client.send(request(uri, id), HttpResponse.BodyHandlers.ofString()).body();
	}
	private static int status(HttpClient client, URI uri, String id) throws Exception {
		return client.send(request(uri, id), HttpResponse.BodyHandlers.ofString()).statusCode();
	}
	private static CompletableFuture<String> async(HttpClient client, URI uri, String id) {
		return client.sendAsync(request(uri, id), HttpResponse.BodyHandlers.ofString()).thenApply(HttpResponse::body);
	}
	private static void assertDifferentThreads(String body) {
		String[] threadIds = body.split(":", -1);
		assertEquals(2, threadIds.length, "expected requestThread:asyncThread response, got: " + body);
		assertNotEquals(threadIds[0], threadIds[1], "AsyncContext.start task must run on another thread");
	}
	private static HttpRequest request(URI uri, String id) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET();
		if (id != null) builder.header("X-STP-Test-Execution-Id", id);
		return builder.build();
	}
	private static void assertMethods(String json, String id, String... expected) {
		String section = section(json, id);
		for (String method : expected) assertTrue(section.contains(method), id + " missing " + method + ": " + section);
	}
	private static String section(String json, String id) {
		int start = json.indexOf("\"testExecutionId\":\"" + id + "\"");
		assertTrue(start >= 0, "missing ID " + id + " in " + json);
		int end = json.indexOf("}", start);
		return json.substring(start, end < 0 ? json.length() : end);
	}
	private static int occurrences(String text, String needle) {
		int count = 0;
		for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
		return count;
	}
	private static void assertListenerCallbacksOverlap(String log) {
		long aStart = listenerTimestamp(log, "START", "concurrent-a");
		long aEnd = listenerTimestamp(log, "END", "concurrent-a");
		long bStart = listenerTimestamp(log, "START", "concurrent-b");
		long bEnd = listenerTimestamp(log, "END", "concurrent-b");
		assertTrue(aStart < bEnd && bStart < aEnd,
				"AsyncListener callbacks did not overlap: a=" + aStart + ".." + aEnd + ", b=" + bStart + ".." + bEnd);
	}
	private static long listenerTimestamp(String log, String phase, String scenario) {
		String marker = "ASYNC_LISTENER_EVENT phase=" + phase + " scenario=" + scenario + " nanos=";
		int start = log.indexOf(marker);
		assertTrue(start >= 0, "missing callback timing marker " + marker + " in " + log);
		start += marker.length();
		int end = start;
		while (end < log.length() && Character.isDigit(log.charAt(end))) end++;
		return Long.parseLong(log.substring(start, end));
	}
}
