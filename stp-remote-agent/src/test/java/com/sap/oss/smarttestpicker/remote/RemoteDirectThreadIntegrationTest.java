// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import example.remote.DirectThreadFixtureMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteDirectThreadIntegrationTest {
	@Test void directThreadRunnableConstructorsCaptureContextAndPreserveThreadSemantics() throws Exception {
		Fixture fixture = startFixture("raw-threads");
		try {
			assertEquals("created", get(fixture.client, fixture.root.resolve("create/plain-A"), "thread-A"));
			get(fixture.client, fixture.root.resolve("start/A"), null);
			assertEquals("ran", get(fixture.client, fixture.root.resolve("result/A"), null));

			get(fixture.client, fixture.root.resolve("create/named-named"), "thread-named");
			get(fixture.client, fixture.root.resolve("start/named"), null);
			assertTrue(get(fixture.client, fixture.root.resolve("metadata/named"), null).startsWith("supplied-name-named|"));

			get(fixture.client, fixture.root.resolve("create/group-grouped"), "thread-group");
			get(fixture.client, fixture.root.resolve("start/grouped"), null);
			assertTrue(get(fixture.client, fixture.root.resolve("metadata/grouped"), null).endsWith("|group-grouped"));

			get(fixture.client, fixture.root.resolve("create/groupnamed-groupnamed"), "thread-groupnamed");
			get(fixture.client, fixture.root.resolve("start/groupnamed"), null);
			assertEquals("supplied-name-groupnamed|group-groupnamed",
					get(fixture.client, fixture.root.resolve("metadata/groupnamed"), null));

			get(fixture.client, fixture.root.resolve("create/failure-failed"), "thread-failure");
			get(fixture.client, fixture.root.resolve("start/failed"), null);
			assertEquals("null", get(fixture.client, fixture.root.resolve("failure-context/failed"), null),
					"uncaught failure handler must run after the wrapper restored context");

			get(fixture.client, fixture.root.resolve("create-shared/A"), "thread-shared-A");
			get(fixture.client, fixture.root.resolve("create-shared/B"), "thread-shared-B");
			get(fixture.client, fixture.root.resolve("start-shared"), null);

			get(fixture.client, fixture.root.resolve("create/nocontext-ordinary"), null);
			get(fixture.client, fixture.root.resolve("start/ordinary"), null);
		} finally { stop(fixture.process); }
		String json = Files.readString(fixture.output);
		assertMethods(json, "thread-A", "ThreadRepository#run");
		assertMethods(json, "thread-named", "ThreadRepository#run");
		assertMethods(json, "thread-group", "ThreadRepository#run");
		assertMethods(json, "thread-groupnamed", "ThreadRepository#run");
		assertMethods(json, "thread-failure", "ThreadRepository#failure");
		assertMethods(json, "thread-shared-A", "ThreadRepository#sharedA");
		assertMethods(json, "thread-shared-B", "ThreadRepository#sharedB");
		assertFalse(section(json, "thread-shared-A").contains("sharedB"));
		assertFalse(section(json, "thread-shared-B").contains("sharedA"));
		assertFalse(json.contains("ThreadRepository#noContext"), "thread created without a TestID must remain unattributed");
		assertEquals(7, occurrences(json, "\"testExecutionId\":"));
	}

	@Test void virtualThreadAndBuilderCallSitesPropagateWhenTheRunningJdkSupportsThem() throws Exception {
		boolean supports = supportsVirtualThreads();
		Assumptions.assumeTrue(supports, "Skipped: this JDK does not expose Thread.startVirtualThread and Thread.ofVirtual");
		Fixture fixture = startFixture("virtual-threads");
		try {
			assertEquals("true", get(fixture.client, fixture.root.resolve("virtual/static"), "thread-virtual-static"));
			assertEquals("true", get(fixture.client, fixture.root.resolve("builder/builder"), "thread-virtual-builder"));
		} finally { stop(fixture.process); }
		String json = Files.readString(fixture.output);
		assertMethods(json, "thread-virtual-static", "ThreadRepository#virtual");
		assertMethods(json, "thread-virtual-builder", "ThreadRepository#virtual");
	}

	private static Fixture startFixture(String prefix) throws Exception {
		Path output = Files.createTempDirectory("stp-remote-" + prefix + "-").resolve("observations.json");
		Path log = output.resolveSibling("server.log");
		Path agentJar = Path.of(System.getProperty("stp.remote.agent.jar"));
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String args = "output=" + output + ";includes=example.remote.";
		Process process = new ProcessBuilder(java, "-javaagent:" + agentJar + "=" + args, "-cp",
				System.getProperty("java.class.path"), DirectThreadFixtureMain.class.getName())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		String port = awaitReady(process, log);
		return new Fixture(process, output, log, URI.create("http://127.0.0.1:" + port),
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
	}
	private static boolean supportsVirtualThreads() {
		try { Thread.class.getMethod("startVirtualThread", Runnable.class); Thread.class.getMethod("ofVirtual"); return true; }
		catch (NoSuchMethodException ignored) { return false; }
	}
	private static void stop(Process process) throws Exception {
		process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
	}
	private static String awaitReady(Process process, Path log) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (System.nanoTime() < deadline && process.isAlive()) {
			if (Files.exists(log)) for (String line : Files.readAllLines(log)) if (line.startsWith("READY:")) return line.substring(6).trim();
			Thread.sleep(50);
		}
		throw new AssertionError("Direct-thread fixture did not start: " + (Files.exists(log) ? Files.readString(log) : "no log"));
	}
	private static String get(HttpClient client, URI uri, String id) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
		if (id != null) request.header("X-STP-Test-Execution-Id", id);
		HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body()); return response.body();
	}
	private static void assertMethods(String json, String id, String... methods) {
		String observation = section(json, id);
		for (String method : methods) assertTrue(observation.contains(method), id + " missing " + method + ": " + observation);
	}
	private static String section(String json, String id) {
		int start = json.indexOf("\"testExecutionId\":\"" + id + "\"");
		if (start < 0) return "";
		int end = json.indexOf("\"testExecutionId\":", start + 1);
		return json.substring(start, end < 0 ? json.length() : end);
	}
	private static int occurrences(String text, String needle) {
		int count = 0, offset = 0;
		while ((offset = text.indexOf(needle, offset)) >= 0) { count++; offset += needle.length(); }
		return count;
	}
	private record Fixture(Process process, Path output, Path log, URI root, HttpClient client) { }
}
