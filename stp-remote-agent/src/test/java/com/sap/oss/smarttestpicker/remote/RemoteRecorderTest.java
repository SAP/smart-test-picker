// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RemoteRecorderTest {
	@Test void storesSeparateMethodSetsForDifferentRequestIdsOfTheSameTest() throws Exception {
		var output = Files.createTempDirectory("remote-schema-v2").resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		assertTrue(Files.exists(output), "output path is reserved when the agent starts");
		assertEquals(0, Files.size(output), "final JSON is written at shutdown");
		RemoteRequestIdentity first = TestRequests.request("same-test");
		RemoteRequestIdentity second = TestRequests.request("same-test");
		try (var scope = RemoteTestContext.enter(first)) { RemoteRecorder.methodHit("example.Vet", "list", "()V"); }
		try (var scope = RemoteTestContext.enter(second)) { RemoteRecorder.methodHit("example.Owner", "show", "()V"); }
		RemoteRecorder.writeOutput();
		String json = Files.readString(output);
		assertTrue(json.contains("\"schemaVersion\": 2"), json);
		assertTrue(json.contains("\"requests\""), json);
		assertEquals(2, occurrences(json, "\"testSuiteId\":\"fixture-suite\""), json);
		assertEquals(2, occurrences(json, "\"testId\":\"same-test\""), json);
		assertNotEquals(first.requestId(), second.requestId());
		assertTrue(json.contains("Vet#list"), json);
		assertTrue(json.contains("Owner#show"), json);
		assertTrue(methodsFor(json, first.requestId()).contains("Vet#list"));
		assertFalse(methodsFor(json, first.requestId()).contains("Owner#show"));
		assertTrue(methodsFor(json, second.requestId()).contains("Owner#show"));
		assertFalse(methodsFor(json, second.requestId()).contains("Vet#list"));
	}

	@Test void concurrentHitsProduceOneCompleteSnapshotWithoutLostOrDuplicateRequests() throws Exception {
		var output = Files.createTempDirectory("remote-concurrent-output").resolve("nested/observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		int workers = 8;
		int perWorker = 40;
		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(workers)) {
			List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
			for (int worker = 0; worker < workers; worker++) {
				int workerId = worker;
				futures.add(pool.submit(() -> {
					start.await();
					for (int request = 0; request < perWorker; request++) {
						RemoteRequestIdentity identity = new RemoteRequestIdentity("suite", "test-" + workerId,
								"request-" + workerId + "-" + request);
						try (var scope = RemoteTestContext.enter(identity)) {
							RemoteRecorder.methodHit("example.Work", "hit" + request, "()V");
						}
					}
					return null;
				}));
			}
			start.countDown();
			for (var future : futures) future.get(10, TimeUnit.SECONDS);
		}
		RemoteRecorder.writeOutput();
		String json = Files.readString(output);
		assertEquals(workers * perWorker, occurrences(json, "\"requestId\":"), json);
		assertEquals(workers * perWorker, occurrences(json, "\"methods\":"), json);
		assertEquals(workers * perWorker, occurrences(json, "example.Work#hit"), json);
		assertTrue(json.startsWith("{\n  \"schemaVersion\": 2"), json);
		assertTrue(json.endsWith("\n}\n"), json);
	}

	@Test void writeFailureNamesOutputAndCleansTemporaryFile() throws Exception {
		var parent = Files.createTempDirectory("remote-output-failure");
		var output = parent.resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		try (var scope = RemoteTestContext.enter(TestRequests.request("write-failure"))) {
			RemoteRecorder.methodHit("example.Work", "hit", "()V");
		}
		Files.delete(output);
		Files.createDirectory(output);
		Files.writeString(output.resolve("blocker"), "keep directory in place");
		IllegalStateException failure = assertThrows(IllegalStateException.class, RemoteRecorder::writeOutput);
		assertTrue(failure.getMessage().contains(output.toAbsolutePath().normalize().toString()), failure.getMessage());
		assertTrue(Files.exists(output.resolveSibling(output.getFileName() + ".inprogress")));
		try (var siblings = Files.list(parent)) {
			assertFalse(siblings.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")), "failed write must not leave an orphan temporary file");
		}
	}

	private static String methodsFor(String json, String requestId) {
		int start = json.indexOf("\"requestId\":\"" + requestId + "\"");
		assertTrue(start >= 0, json);
		return json.substring(start, json.indexOf('}', start));
	}

	private static int occurrences(String text, String needle) {
		int count = 0;
		for (int offset = 0; (offset = text.indexOf(needle, offset)) >= 0; offset += needle.length()) count++;
		return count;
	}
}
