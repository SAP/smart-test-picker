// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import example.remote.CompletableFutureFixtureMain;
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

class RemoteCompletableFutureIntegrationTest {
	@Test void selectedCompletableFutureStagesKeepRegistrationIdentityAcrossRealHttpRequests() throws Exception {
		Path output = Files.createTempDirectory("stp-remote-cf-").resolve("observations.json");
		Path agentJar = Path.of(System.getProperty("stp.remote.agent.jar"));
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Path log = output.resolveSibling("server.log");
		Process process = new ProcessBuilder(java, "-javaagent:" + agentJar + "=output=" + output + ";includes=example.remote.",
				"-cp", System.getProperty("java.class.path"), CompletableFutureFixtureMain.class.getName())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			String port = awaitReady(process, log);
			URI root = URI.create("http://127.0.0.1:" + port);
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			assertEquals("done", get(client, root.resolve("run-common"), "cf-run-common"));
			assertEquals("done", get(client, root.resolve("run-explicit"), "cf-run-explicit"));
			assertEquals("common-result", get(client, root.resolve("supply-common"), "cf-supply-common"));
			assertEquals("explicit-result", get(client, root.resolve("supply-explicit"), "cf-supply-explicit"));
			registerAndComplete(client, root, "then-run-register-common", "complete-then-run-common", "cf-then-run-common");
			registerAndComplete(client, root, "then-run-register-explicit", "complete-then-run-explicit", "cf-then-run-explicit");
			registerAndComplete(client, root, "then-apply-register-common", "complete-then-apply-common", "cf-then-apply-common", "input-result");
			registerAndComplete(client, root, "then-apply-register-explicit", "complete-then-apply-explicit", "cf-then-apply-explicit", "input-result");
			assertEquals("registered", get(client, root.resolve("then-run-register-concurrent-a"), "cf-concurrent-A"));
			assertEquals("registered", get(client, root.resolve("then-run-register-concurrent-b"), "cf-concurrent-B"));
			var completionA = asyncGet(client, root.resolve("complete-concurrent-a"));
			var completionB = asyncGet(client, root.resolve("complete-concurrent-b"));
			assertEquals(200, completionA.get(10, TimeUnit.SECONDS).statusCode());
			assertEquals(200, completionB.get(10, TimeUnit.SECONDS).statusCode());
			assertEquals("done", get(client, root.resolve("run-no-context"), null));
			assertEquals("failed", get(client, root.resolve("run-failure"), "cf-failure"));
			assertEquals("done", get(client, root.resolve("run-explicit-after-failure"), "cf-after-failure"));
		} finally {
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
		}
		String json = Files.readString(output);
		assertMethods(json, "cf-run-common", "CompletableFutureRepository#runCommon");
		assertMethods(json, "cf-run-explicit", "CompletableFutureRepository#runExplicit");
		assertMethods(json, "cf-supply-common", "CompletableFutureRepository#supplyCommon");
		assertMethods(json, "cf-supply-explicit", "CompletableFutureRepository#supplyExplicit");
		assertMethods(json, "cf-then-run-common", "CompletableFutureRepository#thenRunCommon");
		assertMethods(json, "cf-then-run-explicit", "CompletableFutureRepository#thenRunExplicit");
		assertMethods(json, "cf-then-apply-common", "CompletableFutureRepository#thenApplyCommon");
		assertMethods(json, "cf-then-apply-explicit", "CompletableFutureRepository#thenApplyExplicit");
		assertMethods(json, "cf-concurrent-A", "CompletableFutureRepository#concurrentLeafA");
		assertMethods(json, "cf-concurrent-B", "CompletableFutureRepository#concurrentLeafB");
		assertMethods(json, "cf-failure", "CompletableFutureRepository#failure");
		assertMethods(json, "cf-after-failure", "CompletableFutureRepository#afterFailure");
		assertFalse(json.contains("CompletableFutureRepository#noContext"), "stage registered without an ID remains unattributed");
		assertFalse(section(json, "cf-then-run-common").contains("CompletableFutureRepository#completer"));
		assertFalse(section(json, "cf-then-run-explicit").contains("CompletableFutureRepository#completer"));
		assertFalse(section(json, "cf-then-apply-common").contains("CompletableFutureRepository#completer"));
		assertFalse(section(json, "cf-then-apply-explicit").contains("CompletableFutureRepository#completer"));
		assertFalse(section(json, "cf-concurrent-A").contains("concurrentLeafB"));
		assertFalse(section(json, "cf-concurrent-B").contains("concurrentLeafA"));
		assertFalse(section(json, "cf-failure").contains("afterFailure"));
		assertFalse(section(json, "cf-after-failure").contains("#failure"));
		assertEquals(12, occurrences(json, "\"testId\":"), "no-context stage must not create an observation");
	}

	private static void registerAndComplete(HttpClient client, URI root, String registrationPath, String completePath,
			String id, String... expected) throws Exception {
		assertEquals("registered", get(client, root.resolve(registrationPath), id));
		assertEquals(expected.length == 0 ? "complete" : expected[0], get(client, root.resolve(completePath), null));
	}
	private static String awaitReady(Process process, Path log) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (System.nanoTime() < deadline && process.isAlive()) {
			if (Files.exists(log)) for (String line : Files.readAllLines(log)) if (line.startsWith("READY:")) return line.substring(6).trim();
			Thread.sleep(50);
		}
		throw new AssertionError("CompletableFuture fixture did not start: " + (Files.exists(log) ? Files.readString(log) : "no log"));
	}
	private static String get(HttpClient client, URI uri, String id) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
		RemoteTestHeaders.apply(builder, id);
		HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		return response.body();
	}
	private static java.util.concurrent.CompletableFuture<HttpResponse<String>> asyncGet(HttpClient client, URI uri) {
		return client.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
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
