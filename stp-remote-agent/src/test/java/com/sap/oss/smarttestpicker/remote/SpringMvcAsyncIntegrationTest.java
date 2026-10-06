// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import example.springremote.SpringMvcFixtureMain;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SpringMvcAsyncIntegrationTest {
	@Test void measuresSpringMvcAsyncBoundariesAgainstGenericPropagation() throws Exception {
		Path directory = Files.createTempDirectory("stp-spring-mvc-");
		Path observations = directory.resolve("observations.json");
		Path log = directory.resolve("spring-server.log");
		Path agent = Path.of(System.getProperty("stp.remote.agent.jar"));
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String args = "output=" + observations + ";includes=example.springremote.";
		Process process = new ProcessBuilder(java, "-javaagent:" + agent + "=" + args, "-cp",
				System.getProperty("java.class.path"), SpringMvcFixtureMain.class.getName())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			int port = awaitReady(process, log);
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			URI base = URI.create("http://127.0.0.1:" + port);
			for (String path : new String[]{"/callable", "/web-async-task", "/deferred", "/async",
					"/task-executor/execute", "/task-executor/submit-runnable", "/task-executor/submit-callable"}) {
				HttpResponse<String> response = send(client, base.resolve(path), testId("A", path));
				assertEquals(200, response.statusCode(), path);
				if (path.equals("/async")) assertTrue(response.body().startsWith("asyncService|" + testId("A", path) + "|spring-async-"), response.body());
			}
			for (String path : new String[]{"/callable", "/web-async-task", "/deferred", "/async"}) {
				HttpResponse<String> response = send(client, base.resolve(path), testId("B", path));
				assertEquals(200, response.statusCode(), path);
				if (path.equals("/async")) assertTrue(response.body().startsWith("asyncService|" + testId("B", path) + "|spring-async-"), response.body());
			}
			for (String path : new String[]{"/task-executor/execute", "/task-executor/submit-runnable", "/task-executor/submit-callable"}) {
				assertEquals(200, send(client, base.resolve(path), testId("B", path)).statusCode(), path);
			}
			assertEquals(500, send(client, base.resolve("/callable-fail"), "A-callable-fail").statusCode());
			assertEquals(500, send(client, base.resolve("/web-async-task-fail"), "B-web-async-task-fail").statusCode());
			HttpResponse<String> asyncFailure = send(client, base.resolve("/async-fail"), "A-async-fail");
			assertEquals(500, asyncFailure.statusCode(), "@Async exception must retain normal exceptional completion");
			for (String path : new String[]{"/callable", "/web-async-task", "/deferred", "/async",
					"/task-executor/execute", "/task-executor/submit-runnable", "/task-executor/submit-callable"}) {
				assertEquals(200, send(client, base.resolve(path), null).statusCode(), path);
			}
			var callableOverlap = client.sendAsync(request(base.resolve("/callable-overlap"), "overlap-callable-A"),
					HttpResponse.BodyHandlers.ofString());
			var webTaskOverlap = client.sendAsync(request(base.resolve("/web-async-task-overlap"), "overlap-web-task-B"),
					HttpResponse.BodyHandlers.ofString());
			var asyncOverlapA = client.sendAsync(request(base.resolve("/async-overlap-a"), "async-overlap-A"),
					HttpResponse.BodyHandlers.ofString());
			var asyncOverlapB = client.sendAsync(request(base.resolve("/async-overlap-b"), "async-overlap-B"),
					HttpResponse.BodyHandlers.ofString());
			assertEquals(200, callableOverlap.get(15, TimeUnit.SECONDS).statusCode());
			assertEquals(200, webTaskOverlap.get(15, TimeUnit.SECONDS).statusCode());
			assertEquals(200, asyncOverlapA.get(15, TimeUnit.SECONDS).statusCode());
			assertEquals(200, asyncOverlapB.get(15, TimeUnit.SECONDS).statusCode());
		} finally {
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
		}
		String output = Files.readString(log);
		String json = Files.readString(observations);
		assertEquals(expectedTestIds(), observedTestIds(json), "headerless requests must not create observations; every supplied ID should appear");
		assertTransitions(output, "callable");
		assertTransitions(output, "webAsyncTask");
		assertTransitions(output, "async");
		assertCallbackOverlap(output);
		assertAsyncWorkAfterInitialDispatch(output, "/callable", "callable", testId("A", "/callable"), 0);
		assertAsyncWorkAfterInitialDispatch(output, "/callable", "callable", testId("B", "/callable"), 1);
		assertAsyncWorkAfterInitialDispatch(output, "/callable", "callable", "<none>", 2);
		assertAsyncWorkAfterInitialDispatch(output, "/web-async-task", "webAsyncTask", testId("A", "/web-async-task"), 0);
		assertAsyncWorkAfterInitialDispatch(output, "/web-async-task", "webAsyncTask", testId("B", "/web-async-task"), 1);
		assertAsyncWorkAfterInitialDispatch(output, "/web-async-task", "webAsyncTask", "<none>", 2);
		assertAsyncWorkAfterInitialDispatch(output, "/deferred", "deferredComplete", testId("A", "/deferred"), 0);
		assertAsyncWorkAfterInitialDispatch(output, "/deferred", "deferredComplete", testId("B", "/deferred"), 1);
		assertAsyncWorkAfterInitialDispatch(output, "/deferred", "deferredComplete", "<none>", 2);
		assertAsyncWorkAfterInitialDispatch(output, "/async", "asyncService", testId("A", "/async"), 0);
		assertAsyncWorkAfterInitialDispatch(output, "/async", "asyncService", testId("B", "/async"), 1);
		assertAsyncWorkAfterInitialDispatch(output, "/async", "asyncService", "<none>", 2);
		assertEventCount(output, "callable", testId("A", "/callable"), 1);
		assertEventCount(output, "callable", testId("B", "/callable"), 1);
		assertEventCount(output, "callable", "<none>", 1);
		assertEventCount(output, "webAsyncTask", testId("A", "/web-async-task"), 1);
		assertEventCount(output, "webAsyncTask", testId("B", "/web-async-task"), 1);
		assertEventCount(output, "webAsyncTask", "<none>", 1);
		assertEventCount(output, "callableFailure", "A-callable-fail", 1);
		assertEventCount(output, "webAsyncTaskFailure", "B-web-async-task-fail", 1);
		assertEquals(eventThread(output, "callable", testId("A", "/callable")), eventThread(output, "callable", testId("B", "/callable")));
		assertEquals(eventThread(output, "callableFailure", "A-callable-fail"), eventThread(output, "callable", "<none>"),
				"Callable worker must be clean after callback exception");
		assertEquals(eventThread(output, "webAsyncTask", testId("A", "/web-async-task")),
				eventThread(output, "webAsyncTask", testId("B", "/web-async-task")));
		assertEquals(eventThread(output, "webAsyncTaskFailure", "B-web-async-task-fail"), eventThread(output, "webAsyncTask", "<none>"),
				"WebAsyncTask worker must be clean after callback exception");
		assertEventCount(output, "callable", "overlap-callable-A", 1);
		assertEventCount(output, "webAsyncTask", "overlap-web-task-B", 1);
		assertTrue(section(json, "overlap-callable-A").contains("SpringMvcRepository#hit"));
		assertTrue(section(json, "overlap-web-task-B").contains("SpringMvcRepository#hit"));
		assertFalse(section(json, "overlap-callable-A").contains("webAsyncTaskOverlap"), "overlapping WebAsyncTask must not contaminate Callable");
		assertFalse(section(json, "overlap-web-task-B").contains("callableOverlap"), "overlapping Callable must not contaminate WebAsyncTask");
		assertEventCount(output, "asyncService", testId("A", "/async"), 1);
		assertEventCount(output, "asyncService", testId("B", "/async"), 1);
		assertEventCount(output, "asyncService", "<none>", 1);
		assertEventCount(output, "asyncFailure", "A-async-fail", 1);
		assertEquals(eventThread(output, "asyncService", "<none>"), eventThread(output, "asyncFailure", "A-async-fail"),
				"single @Async worker must be reused and clean after exceptional completion");
		assertEquals(eventThread(output, "asyncService", testId("A", "/async")), eventThread(output, "asyncService", testId("B", "/async")));
		assertEventCount(output, "asyncOverlapA", "async-overlap-A", 1);
		assertEventCount(output, "asyncOverlapB", "async-overlap-B", 1);
		assertNotEquals(eventThread(output, "asyncOverlapA", "async-overlap-A"), eventThread(output, "asyncOverlapB", "async-overlap-B"));
		assertAsyncOverlap(output);
		assertEventCount(output, "deferredComplete", testId("A", "/deferred"), 1);
		assertEventCount(output, "deferredRedispatch", testId("A", "/deferred"), 1);
		assertEventCount(output, "deferredComplete", testId("B", "/deferred"), 1);
		assertEventCount(output, "deferredRedispatch", testId("B", "/deferred"), 1);
		for (String path : new String[]{"/task-executor/execute", "/task-executor/submit-runnable", "/task-executor/submit-callable"}) {
			String scenario = path.endsWith("execute") ? "taskExecutorExecute"
					: path.endsWith("runnable") ? "taskExecutorSubmitRunnable" : "taskExecutorSubmitCallable";
			assertEventCount(output, scenario, testId("A", path), 1);
			assertEventCount(output, scenario, testId("B", path), 1);
			assertEventCount(output, scenario, "<none>", 1);
			assertEquals(eventThread(output, scenario, testId("A", path)), eventThread(output, scenario, testId("B", path)));
			assertEquals(eventThread(output, scenario, testId("A", path)), eventThread(output, scenario, "<none>"),
					"single AsyncTaskExecutor worker must be reused and clean after completion");
		}
		assertEventCount(output, "deferredComplete", "<none>", 1);
		assertTrue(section(json, testId("A", "/async")).contains("SpringMvcRepository#hit"), "@Async application method must be attributed to A");
		assertTrue(section(json, testId("B", "/async")).contains("SpringMvcRepository#hit"), "@Async application method must be attributed to B");
		assertTrue(section(json, "A-async-fail").contains("SpringMvcRepository#hit"));
		assertFalse(section(json, "async-overlap-A").contains("asyncOverlapB"), "parallel @Async B must not contaminate A");
		assertFalse(section(json, "async-overlap-B").contains("asyncOverlapA"), "parallel @Async A must not contaminate B");
		for (String id : new String[]{testId("A", "/callable"), testId("B", "/callable")}) {
			String methods = section(json, id);
			assertTrue(methods.contains("SpringMvcController#callable"), methods);
			assertTrue(methods.contains("SpringMvcRepository#hit"), "Callable body must be attributed: " + methods);
			assertFalse(methods.contains("webAsyncTask"), "Callable must not receive the WebAsyncTask callback: " + methods);
			assertTrue(section(json, testId(id.startsWith("A") ? "A" : "B", "/deferred"))
					.contains("SpringMvcRepository#hit"), "DeferredResult app completion should be attributed to " + id);
		}
		for (String id : new String[]{testId("A", "/web-async-task"), testId("B", "/web-async-task")}) {
			String methods = section(json, id);
			assertTrue(methods.contains("SpringMvcRepository#hit"), "WebAsyncTask body must be attributed: " + methods);
			assertFalse(methods.contains("SpringMvcController#callable("), "WebAsyncTask must not receive Callable work: " + methods);
		}
		assertTrue(section(json, "A-callable-fail").contains("SpringMvcRepository#hit"));
		assertTrue(section(json, "B-web-async-task-fail").contains("SpringMvcRepository#hit"));
		assertFalse(section(json, "A-callable").contains("webAsyncTask"));
		assertFalse(section(json, "B-web-async-task").contains("SpringMvcController#callable("));
		for (String id : new String[]{testId("A", "/task-executor/execute"), testId("B", "/task-executor/execute")})
			assertTrue(section(json, id).contains("SpringMvcRepository#hit"), "AsyncTaskExecutor app work should be attributed: " + id);
	}

	private static void assertTransitions(String log, String scenario) {
		String request = line(log, "SPRING_TRANSITION:" + scenario + ":request=");
		String worker = line(log, "SPRING_TRANSITION:" + scenario + ":worker=");
		assertNotNull(request, log);
		assertNotNull(worker, log);
		String requestThread = request.substring(request.lastIndexOf('=') + 1);
		String workerThread = worker.substring(worker.lastIndexOf('=') + 1);
		assertNotEquals(requestThread, workerThread, "async callback must change threads: " + scenario);
	}
	private static void assertEventCount(String log, String method, String id, int expected) {
		String marker = "SPRING_EVENT:" + method + "|" + id + "|";
		assertEquals(expected, occurrences(log, marker), "wrong runtime event count for " + method + " under " + id + "\n" + log);
	}
	private static String eventThread(String log, String method, String id) {
		String event = line(log, "SPRING_EVENT:" + method + "|" + id + "|");
		assertNotNull(event, "missing event for thread lookup: " + method + "/" + id);
		return event.split("\\|", -1)[3];
	}
	private static void assertAsyncWorkAfterInitialDispatch(String log, String path, String scenario, String id, int requestIndex) {
		String marker = "SPRING_DISPATCH:exit:" + path + ":REQUEST:";
		List<String> dispatches = log.lines().filter(value -> value.contains(marker)).toList();
		assertTrue(dispatches.size() > requestIndex, "missing initial request dispatch exit for " + path + "\n" + log);
		String dispatch = dispatches.get(requestIndex);
		long dispatchNanos = Long.parseLong(dispatch.substring(dispatch.lastIndexOf(':') + 1));
		String event = line(log, "SPRING_EVENT:" + scenario + "|" + id + "|");
		assertNotNull(event, "missing async app event for " + path + "\n" + log);
		long eventNanos = Long.parseLong(event.substring(event.lastIndexOf('|') + 1));
		assertTrue(eventNanos > dispatchNanos, "application async work must run after initial dispatch returned: " + path);
	}
	private static int occurrences(String text, String needle) {
		int count = 0;
		for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
		return count;
	}
	private static void assertCallbackOverlap(String log) {
		long callableStart = markerNanos(log, "SPRING_OVERLAP:START:callable:");
		long callableEnd = markerNanos(log, "SPRING_OVERLAP:END:callable:");
		long webTaskStart = markerNanos(log, "SPRING_OVERLAP:START:webAsyncTask:");
		long webTaskEnd = markerNanos(log, "SPRING_OVERLAP:END:webAsyncTask:");
		assertTrue(callableStart < webTaskEnd && webTaskStart < callableEnd,
				"Callable and WebAsyncTask callbacks did not overlap: callable=" + callableStart + ".." + callableEnd
						+ ", WebAsyncTask=" + webTaskStart + ".." + webTaskEnd);
	}
	private static void assertAsyncOverlap(String log) {
		long a = markerNanos(log, "SPRING_ASYNC_OVERLAP:START:asyncOverlapA:");
		long b = markerNanos(log, "SPRING_ASYNC_OVERLAP:START:asyncOverlapB:");
		long aEvent = eventNanos(log, "asyncOverlapA", "async-overlap-A");
		long bEvent = eventNanos(log, "asyncOverlapB", "async-overlap-B");
		assertTrue(a < bEvent && b < aEvent, "@Async application callbacks must overlap");
	}
	private static long eventNanos(String log, String method, String id) {
		String event = line(log, "SPRING_EVENT:" + method + "|" + id + "|");
		assertNotNull(event, log);
		return Long.parseLong(event.substring(event.lastIndexOf('|') + 1));
	}
	private static long markerNanos(String log, String marker) {
		String value = line(log, marker);
		assertNotNull(value, "missing overlap marker " + marker + "\n" + log);
		return Long.parseLong(value.substring(value.lastIndexOf(':') + 1));
	}
	private static String section(String json, String id) {
		int start = json.indexOf("\"testId\":\"" + id + "\"");
		assertTrue(start >= 0, "missing observation for " + id + ": " + json);
		int end = json.indexOf('}', start);
		return json.substring(start, end < 0 ? json.length() : end);
	}
	private static Set<String> observedTestIds(String json) {
		Set<String> ids = new HashSet<>();
		Matcher matcher = Pattern.compile("\\\"testId\\\":\\\"([^\\\"]+)\\\"").matcher(json);
		while (matcher.find()) ids.add(matcher.group(1));
		return ids;
	}
	private static Set<String> expectedTestIds() {
		Set<String> ids = new HashSet<>();
		for (String prefix : new String[]{"A", "B"}) {
			for (String path : new String[]{"/callable", "/web-async-task", "/deferred", "/async",
					"/task-executor/execute", "/task-executor/submit-runnable", "/task-executor/submit-callable"})
				ids.add(testId(prefix, path));
		}
		ids.add("A-callable-fail");
		ids.add("B-web-async-task-fail");
		ids.add("A-async-fail");
		ids.add("overlap-callable-A");
		ids.add("overlap-web-task-B");
		ids.add("async-overlap-A");
		ids.add("async-overlap-B");
		return ids;
	}
	private static String testId(String prefix, String path) { return prefix + path.replace('/', '-'); }
	private static String line(String log, String prefix) {
		return log.lines().filter(value -> value.contains(prefix)).findFirst().orElse(null);
	}
	private static int awaitReady(Process process, Path log) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline && process.isAlive()) {
			if (Files.exists(log)) {
				for (String line : Files.readAllLines(log)) if (line.startsWith("SPRING_READY:"))
					return Integer.parseInt(line.substring("SPRING_READY:".length()).trim());
			}
			Thread.sleep(50);
		}
		throw new AssertionError("Spring MVC child failed to start: " + (Files.exists(log) ? Files.readString(log) : "no log"));
	}
	private static HttpResponse<String> send(HttpClient client, URI uri, String id) throws Exception {
		return client.send(request(uri, id), HttpResponse.BodyHandlers.ofString());
	}
	private static HttpRequest request(URI uri, String id) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET();
		RemoteTestHeaders.apply(builder, id);
		return builder.build();
	}
}
