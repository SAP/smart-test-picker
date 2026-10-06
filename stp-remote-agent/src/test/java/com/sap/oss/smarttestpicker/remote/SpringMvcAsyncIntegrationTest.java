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
import java.util.concurrent.TimeUnit;

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
				assertEquals(200, send(client, base.resolve(path), testId("A", path)).statusCode(), path);
			}
			for (String path : new String[]{"/callable", "/web-async-task", "/deferred", "/async"}) {
				assertEquals(200, send(client, base.resolve(path), testId("B", path)).statusCode(), path);
			}
			for (String path : new String[]{"/task-executor/execute", "/task-executor/submit-runnable", "/task-executor/submit-callable"}) {
				assertEquals(200, send(client, base.resolve(path), testId("B", path)).statusCode(), path);
			}
			for (String path : new String[]{"/callable", "/web-async-task", "/deferred", "/async",
					"/task-executor/execute", "/task-executor/submit-runnable", "/task-executor/submit-callable"}) {
				assertEquals(200, send(client, base.resolve(path), null).statusCode(), path);
			}
		} finally {
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
		}
		String output = Files.readString(log);
		String json = Files.readString(observations);
		assertTransitions(output, "callable");
		assertTransitions(output, "webAsyncTask");
		assertTransitions(output, "async");
		for (String path : new String[]{"/callable", "/web-async-task", "/deferred", "/async"})
			assertAsyncWorkAfterInitialDispatch(output, path);
		for (String scenario : new String[]{"callable", "webAsyncTask", "asyncService"}) {
			assertEventCount(output, scenario, "<none>", 3); // A, B, then headerless request
		}
		for (String path : new String[]{"/callable", "/web-async-task", "/async"}) {
			String scenario = path.equals("/callable") ? "callable"
					: path.equals("/web-async-task") ? "webAsyncTask" : "asyncService";
			assertEventCount(output, scenario, testId("A", path), 0);
			assertEventCount(output, scenario, testId("B", path), 0);
		}
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
		for (String id : new String[]{testId("A", "/callable"), testId("B", "/callable")}) {
			String methods = section(json, id);
			assertTrue(methods.contains("SpringMvcController#callable"), methods);
			assertFalse(methods.contains("SpringMvcRepository#hit"), "Callable body must not be attributed: " + methods);
			assertTrue(section(json, testId(id.startsWith("A") ? "A" : "B", "/deferred"))
					.contains("SpringMvcRepository#hit"), "DeferredResult app completion should be attributed to " + id);
		}
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
	private static void assertAsyncWorkAfterInitialDispatch(String log, String path) {
		String marker = "SPRING_DISPATCH:exit:" + path + ":REQUEST:";
		String dispatch = line(log, marker);
		assertNotNull(dispatch, "missing initial request dispatch exit for " + path + "\n" + log);
		long dispatchNanos = Long.parseLong(dispatch.substring(dispatch.lastIndexOf(':') + 1));
		String scenario = switch (path) {
			case "/callable" -> "callable";
			case "/web-async-task" -> "webAsyncTask";
			case "/deferred" -> "deferredComplete";
			default -> "asyncService";
		};
		String event = line(log, "SPRING_EVENT:" + scenario + "|");
		assertNotNull(event, "missing async app event for " + path + "\n" + log);
		long eventNanos = Long.parseLong(event.substring(event.lastIndexOf('|') + 1));
		assertTrue(eventNanos > dispatchNanos, "application async work must run after initial dispatch returned: " + path);
	}
	private static int occurrences(String text, String needle) {
		int count = 0;
		for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
		return count;
	}
	private static String section(String json, String id) {
		int start = json.indexOf("\"testExecutionId\":\"" + id + "\"");
		assertTrue(start >= 0, "missing observation for " + id + ": " + json);
		int end = json.indexOf('}', start);
		return json.substring(start, end < 0 ? json.length() : end);
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
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET();
		if (id != null) builder.header("X-STP-Test-Execution-Id", id);
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}
}
