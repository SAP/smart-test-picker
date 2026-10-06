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
			assertEquals(500, status(client, root.resolve("fail"), "test-fail"));
			assertEquals("a", get(client, root.resolve("a"), null));
			CompletableFuture<String> concurrentA = async(client, root.resolve("a"), "test-A-concurrent");
			CompletableFuture<String> concurrentB = async(client, root.resolve("b"), "test-B-concurrent");
			assertEquals("a", concurrentA.get(10, TimeUnit.SECONDS));
			assertEquals("b", concurrentB.get(10, TimeUnit.SECONDS));
		} finally {
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
		}
		String childLog = Files.readString(childLogFile);
		String json = Files.readString(output);
		assertMethods(json, "test-A", "FixtureServlet#doGet", "FixtureService#handleA", "FixtureRepository#readA");
		assertMethods(json, "test-B", "FixtureServlet#doGet", "FixtureService#handleB", "FixtureRepository#readB");
		assertMethods(json, "test-fail", "FixtureServlet#doGet", "FixtureService#fail", "FixtureRepository#fail");
		assertMethods(json, "test-A-concurrent", "FixtureService#handleA", "FixtureRepository#readA");
		assertMethods(json, "test-B-concurrent", "FixtureService#handleB", "FixtureRepository#readB");
		assertEquals(5, occurrences(json, "\"testExecutionId\":"), "headerless requests must have no STP observation");
		assertFalse(section(json, "test-A").contains("handleB"));
		assertFalse(section(json, "test-B").contains("handleA"));
		assertFalse(section(json, "test-A-concurrent").contains("handleB"));
		assertFalse(section(json, "test-B-concurrent").contains("handleA"));
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
}
