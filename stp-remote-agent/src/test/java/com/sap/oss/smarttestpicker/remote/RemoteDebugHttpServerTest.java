// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteDebugHttpServerTest {
	private Path output;
	private RemoteDebugHttpServer server;
	private HttpClient client;
	private URI baseUri;

	@BeforeEach void setUp() throws Exception {
		output = Files.createTempDirectory("remote-debug").resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		client = HttpClient.newHttpClient();
		server = RemoteDebugHttpServer.start(0);
		InetSocketAddress address = server.address();
		baseUri = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), null, null, null);
	}

	@AfterEach void tearDown() {
		if (server != null) server.stop();
		RemoteRecorder.clearForTests();
	}

	@Test void absentPortDisablesServerAndValidPortStartsOnLoopback() throws Exception {
		RemoteAgentConfiguration disabled = RemoteAgentConfiguration.parse("output=disabled.json;includes=example.;serviceId=fixture-service;revision=test-revision");
		assertNull(disabled.debugPort());
		assertNull(RemoteDebugHttpServer.startIfConfigured(disabled.debugPort()));
		assertTrue(server.address().getAddress().isLoopbackAddress());
		assertTrue(server.address().getPort() > 0);
	}

	@Test void memoryReturnsCurrentSchemaAndReflectsLaterHits() throws Exception {
		RemoteRequestIdentity identity = identity("first");
		try (RemoteTestContext.Scope ignored = RemoteTestContext.enter(identity)) {
			RemoteRecorder.methodHit("example.VetController", "list", "()V");
		}
		HttpResponse<String> first = get("/stp/debug/memory");
		assertEquals(200, first.statusCode());
		assertTrue(first.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
		assertTrue(RemoteObservationJson.isValidSchemaV2(first.body()));
		assertTrue(first.body().contains("\"serviceId\":\"test-service\""));
		assertTrue(first.body().contains("\"instanceId\":\"test-instance\""));
		assertTrue(first.body().contains("\"revision\":\"test-revision\""));
		assertTrue(first.body().contains("example.VetController#list()V"));

		try (RemoteTestContext.Scope ignored = RemoteTestContext.enter(identity("later"))) {
			RemoteRecorder.methodHit("example.OwnerController", "show", "()V");
		}
		HttpResponse<String> later = get("/stp/debug/memory");
		assertTrue(later.body().contains("example.OwnerController#show()V"));
	}

	@Test void memoryRemainsValidDuringConcurrentRecording() throws Exception {
		var pool = Executors.newFixedThreadPool(5);
		try {
			List<java.util.concurrent.Future<?>> writers = new ArrayList<>();
			for (int worker = 0; worker < 4; worker++) {
				int id = worker;
				writers.add(pool.submit(() -> {
					for (int hit = 0; hit < 80; hit++) {
						try (RemoteTestContext.Scope ignored = RemoteTestContext.enter(identity("w" + id + "-" + hit))) {
							RemoteRecorder.methodHit("example.Work", "hit", "()V");
						}
					}
				}));
			}
			for (int request = 0; request < 30; request++) {
				for (String path : List.of("/stp/debug/memory", "/stp/debug/snapshot")) {
					HttpResponse<String> response = get(path);
					assertEquals(200, response.statusCode());
					assertTrue(RemoteObservationJson.isValidSchemaV2(response.body()));
				}
			}
			for (var writer : writers) writer.get(10, TimeUnit.SECONDS);
			assertEquals(320, requestCount(get("/stp/debug/memory").body()));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test void outputIs404UntilFinalizedThenReturnsOnlyExactFinalFile() throws Exception {
		Files.writeString(output.resolveSibling(output.getFileName() + ".tmp"), "temporary secret");
		Files.writeString(output.resolveSibling(output.getFileName() + ".inprogress"), "");
		HttpResponse<String> before = get("/stp/debug/output");
		assertEquals(404, before.statusCode());
		assertTrue(before.body().contains("not_checkpointed"));
		assertTrue(before.body().contains(output.toString()));

		try (RemoteTestContext.Scope ignored = RemoteTestContext.enter(identity("persisted"))) {
			RemoteRecorder.methodHit("example.VetController", "list", "()V");
		}
		RemoteRecorder.writeOutput();
		String expected = Files.readString(output);
		for (String path : List.of("/stp/debug/output", "/stp/debug/snapshot")) {
			HttpResponse<String> response = get(path);
			assertEquals(200, response.statusCode());
			assertTrue(response.body().contains("\"serviceId\":\"test-service\""));
			assertTrue(response.body().contains("\"instanceId\":\"test-instance\""));
			assertTrue(response.body().contains("\"revision\":\"test-revision\""));
		}
		Files.writeString(output.resolveSibling(output.getFileName() + ".tmp"), "do not expose");
		Files.writeString(output.resolveSibling(output.getFileName() + ".inprogress"), "stale marker");
		HttpResponse<String> after = get("/stp/debug/output");
		assertEquals(200, after.statusCode());
		assertEquals(expected, after.body());
		assertTrue(RemoteObservationJson.isValidSchemaV2(after.body()));
		assertFalse(after.body().contains("do not expose"));
	}

	@Test void memoryOutputAndSnapshotHaveDistinctCheckpointSemantics() throws Exception {
		RemoteRequestIdentity identity = identity("checkpoint-semantics");
		try (RemoteTestContext.Scope ignored = RemoteTestContext.enter(identity)) {
			RemoteRecorder.methodHit("example.Work", "before", "()V");
		}
		assertEquals(404, get("/stp/debug/output").statusCode());
		assertTrue(get("/stp/debug/memory").body().contains("example.Work#before()V"));
		assertTrue(get("/stp/debug/snapshot").body().contains("example.Work#before()V"));

		RemoteRecorder.checkpointNow();
		String persisted = get("/stp/debug/output").body();
		assertTrue(persisted.contains("example.Work#before()V"));
		assertFalse(get("/stp/debug/memory").body().contains("example.Work#before()V"));
		assertTrue(get("/stp/debug/snapshot").body().contains("example.Work#before()V"));

		try (RemoteTestContext.Scope ignored = RemoteTestContext.enter(identity)) {
			RemoteRecorder.methodHit("example.Work", "after", "()V");
		}
		String memory = get("/stp/debug/memory").body();
		String output = get("/stp/debug/output").body();
		String snapshot = get("/stp/debug/snapshot").body();
		assertTrue(memory.contains("example.Work#after()V"));
		assertFalse(memory.contains("example.Work#before()V"));
		assertTrue(output.contains("example.Work#before()V"));
		assertFalse(output.contains("example.Work#after()V"));
		assertTrue(snapshot.contains("example.Work#before()V"));
		assertTrue(snapshot.contains("example.Work#after()V"));
		assertEquals(1, (int) snapshot.lines().filter(line -> line.contains("\"requestId\"")).count());
		assertTrue(RemoteObservationJson.isValidSchemaV2(snapshot));
		RemoteRecorder.writeOutput();
	}

	@Test void unknownPathsReturnJsonNotFound() throws Exception {
		for (String path : List.of("/stp/debug/unknown", "/stp/debug/output.tmp", "/stp/debug/output.lock", "/stp/debug/output.inprogress")) {
			HttpResponse<String> response = get(path);
			assertEquals(404, response.statusCode());
			assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
			assertTrue(response.body().contains("not_found"));
		}
	}

	private HttpResponse<String> get(String path) throws Exception {
		return client.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private static RemoteRequestIdentity identity(String request) {
		return new RemoteRequestIdentity("debug-suite", "debug-test", request);
	}

	private static int requestCount(String json) {
		return (int) json.lines().filter(line -> line.contains("\"testSuiteId\"")).count();
	}
}
