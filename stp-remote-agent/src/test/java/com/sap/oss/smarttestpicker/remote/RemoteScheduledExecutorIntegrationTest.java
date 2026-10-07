// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import example.remote.ScheduledExecutorFixtureMain;
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

class RemoteScheduledExecutorIntegrationTest {
	@Test void scheduledTasksKeepRegistrationIdentityAndRestoreWorkerContext() throws Exception {
		Path output = Files.createTempDirectory("stp-remote-scheduled-").resolve("observations.json");
		Path log = output.resolveSibling("server.log");
		Path agentJar = Path.of(System.getProperty("stp.remote.agent.jar"));
		Path otelAgent = Path.of(System.getProperty("stp.otel.agent.jar"));
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String agentArgs = "output=" + output + ";includes=example.remote.;serviceId=fixture-service;revision=test-revision;excludes=example.remote.ScheduledExecutorFixtureMain$InspectingScheduler";
		Process process = new ProcessBuilder(java, "-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none",
				"-Dotel.logs.exporter=none", "-javaagent:" + otelAgent, "-javaagent:" + agentJar + "=" + agentArgs, "-cp",
				System.getProperty("java.class.path"), ScheduledExecutorFixtureMain.class.getName())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			String port = awaitReady(process, log);
			URI root = URI.create("http://127.0.0.1:" + port);
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			assertEquals("scheduled", get(client, root.resolve("schedule-runnable/a"), "sched-A"));
			String workerA = get(client, root.resolve("await/a"), null);
			assertTrue(workerA.matches("\\d+"), workerA);
			assertEquals("scheduled", get(client, root.resolve("schedule-callable/b"), "sched-B"));
			String callableResult = get(client, root.resolve("await/b"), null);
			assertTrue(callableResult.startsWith("b-result:"), callableResult);
			assertEquals(workerA, callableResult.substring(callableResult.indexOf(':') + 1), "single scheduler worker is reused without retaining A");

			assertEquals("registered", get(client, root.resolve("fixed-rate/rate"), "sched-C"));
			String periodicWorker = get(client, root.resolve("wait-first/rate"), null);
			assertEquals(periodicWorker, get(client, root.resolve("schedule-worker-b/worker"), "sched-worker-B"));
			String rateRuns = get(client, root.resolve("wait-second/rate"), null);
			assertTrue(rateRuns.startsWith("2:"), rateRuns);
			assertEquals(periodicWorker, rateRuns.substring(rateRuns.indexOf(':') + 1));

			assertEquals("registered", get(client, root.resolve("fixed-delay/delay"), "sched-D"));
			String delayRuns = get(client, root.resolve("wait-second/delay"), null);
			assertTrue(delayRuns.startsWith("2:"), delayRuns);

			assertEquals("failed", get(client, root.resolve("failure/fail"), "sched-failure"));
			String afterFailureWorker = get(client, root.resolve("after-failure/after"), "sched-after-failure");
			assertEquals(workerA, afterFailureWorker, "scheduled worker remains reusable after an exception");
			assertEquals("clear", get(client, root.resolve("no-context"), null));

			assertEquals("scheduled", get(client, root.resolve("concurrent/A"), "sched-concurrent-A"));
			assertEquals("scheduled", get(client, root.resolve("concurrent/B"), "sched-concurrent-B"));
			var concurrentA = asyncGet(client, root.resolve("await-concurrent/A"));
			var concurrentB = asyncGet(client, root.resolve("await-concurrent/B"));
			assertEquals(200, concurrentA.get(10, TimeUnit.SECONDS).statusCode());
			assertEquals(200, concurrentB.get(10, TimeUnit.SECONDS).statusCode());
			assertEquals("clear", get(client, root.resolve("no-context"), null),
				"a later headerless task on the reused worker must not inherit a previous scheduled TestID");
		} finally {
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
		}

		String json = Files.readString(output);
		assertMethods(json, "sched-A", "ScheduledRepository#delayedRunnable");
		assertMethods(json, "sched-B", "ScheduledRepository#delayedCallable");
		assertMethods(json, "sched-C", "ScheduledRepository#fixedRate");
		assertMethods(json, "sched-D", "ScheduledRepository#fixedDelay");
		assertMethods(json, "sched-worker-B", "ScheduledRepository#workerB");
		assertMethods(json, "sched-failure", "ScheduledRepository#failure");
		assertMethods(json, "sched-after-failure", "ScheduledRepository#afterFailure");
		assertMethods(json, "sched-concurrent-A", "ScheduledRepository#concurrentA");
		assertMethods(json, "sched-concurrent-B", "ScheduledRepository#concurrentB");
		assertFalse(json.contains("ScheduledRepository#noContext"), "headerless scheduled work remains unattributed");
		assertFalse(section(json, "sched-C").contains("workerB"));
		assertFalse(section(json, "sched-worker-B").contains("fixedRate"));
		assertFalse(section(json, "sched-concurrent-A").contains("concurrentB"));
		assertFalse(section(json, "sched-concurrent-B").contains("concurrentA"));
		assertEquals(9, occurrences(json, "\"testId\":"));
	}

	private static String awaitReady(Process process, Path log) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (System.nanoTime() < deadline && process.isAlive()) {
			if (Files.exists(log)) for (String line : Files.readAllLines(log)) if (line.startsWith("READY:")) return line.substring(6).trim();
			Thread.sleep(50);
		}
		throw new AssertionError("ScheduledExecutor fixture did not start: " + (Files.exists(log) ? Files.readString(log) : "no log"));
	}
	private static String get(HttpClient client, URI uri, String id) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET();
		RemoteTestHeaders.apply(request, id);
		HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		return response.body();
	}
	private static java.util.concurrent.CompletableFuture<HttpResponse<String>> asyncGet(HttpClient client, URI uri) {
		return client.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}
	private static void assertMethods(String json, String id, String... methods) {
		String observation = section(json, id);
		for (String method : methods) assertTrue(observation.contains(method), id + " missing " + method + ": " + observation);
	}
	private static String section(String json, String id) {
		int start = json.indexOf("\"testId\":\"" + id + "\"");
		if (start < 0) return "";
		int end = json.indexOf("\"testId\":", start + 1);
		return json.substring(start, end < 0 ? json.length() : end);
	}
	private static int occurrences(String text, String needle) {
		int count = 0, offset = 0;
		while ((offset = text.indexOf(needle, offset)) >= 0) { count++; offset += needle.length(); }
		return count;
	}
}
