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

	@Test void repeatedCheckpointsMergeMethodsAndCompactOnlyPersistedMemory() throws Exception {
		var output = Files.createTempDirectory("remote-repeated-checkpoint").resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		RemoteRequestIdentity identity = TestRequests.request("checkpointed");
		try (var scope = RemoteTestContext.enter(identity)) { RemoteRecorder.methodHit("example.Work", "first", "()V"); }
		RemoteRecorder.checkpointNow();
		String first = Files.readString(output);
		assertTrue(first.contains("example.Work#first()V"), first);
		assertTrue(RemoteObservationJson.isValidSchemaV2(RemoteRecorder.memorySnapshot()));
		assertTrue(RemoteRecorder.memorySnapshot().contains("\"requests\": [\n\n"), RemoteRecorder.memorySnapshot());

		try (var scope = RemoteTestContext.enter(identity)) { RemoteRecorder.methodHit("example.Work", "second", "()V"); }
		RemoteRecorder.checkpointNow();
		String second = Files.readString(output);
		assertTrue(second.contains("example.Work#first()V"), second);
		assertTrue(second.contains("example.Work#second()V"), second);
		assertEquals(1, occurrences(second, "\"requestId\":"), second);
		assertTrue(RemoteRecorder.memorySnapshot().contains("\"requests\": [\n\n"));
		RemoteRecorder.writeOutput();
		assertEquals(second, Files.readString(output));
	}

	@Test void failedCheckpointRetainsPendingMemoryAndPreviousOutput() throws Exception {
		var output = Files.createTempDirectory("remote-checkpoint-failure").resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		RemoteRequestIdentity identity = TestRequests.request("checkpoint-failure");
		try (var scope = RemoteTestContext.enter(identity)) { RemoteRecorder.methodHit("example.Work", "persisted", "()V"); }
		RemoteRecorder.checkpointNow();
		String previous = Files.readString(output);
		try (var scope = RemoteTestContext.enter(identity)) { RemoteRecorder.methodHit("example.Work", "pending", "()V"); }
		RemoteRecorder.beforeCheckpointPersistForTests(() -> { throw new IllegalStateException("simulated write failure"); });
		IllegalStateException failure = assertThrows(IllegalStateException.class, RemoteRecorder::checkpointNow);
		assertTrue(failure.getMessage().contains(output.toAbsolutePath().normalize().toString()), failure.getMessage());
		assertEquals(previous, Files.readString(output));
		assertTrue(RemoteRecorder.memorySnapshot().contains("example.Work#pending()V"));
		assertFalse(RemoteRecorder.memorySnapshot().contains("example.Work#persisted()V"));
		RemoteRecorder.beforeCheckpointPersistForTests(null);
		RemoteRecorder.checkpointNow();
		assertTrue(Files.readString(output).contains("example.Work#pending()V"));
		RemoteRecorder.writeOutput();
	}

	@Test void hitsRecordedDuringCheckpointRemainPendingAndArePersistedByNextCheckpoint() throws Exception {
		var output = Files.createTempDirectory("remote-checkpoint-concurrent").resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		RemoteRequestIdentity first = TestRequests.request("during-checkpoint-A");
		RemoteRequestIdentity during = TestRequests.request("during-checkpoint-B");
		try (var scope = RemoteTestContext.enter(first)) { RemoteRecorder.methodHit("example.Work", "before", "()V"); }
		var enteredPersistence = new CountDownLatch(1);
		var resumePersistence = new CountDownLatch(1);
		RemoteRecorder.beforeCheckpointPersistForTests(() -> {
			enteredPersistence.countDown();
			try { assertTrue(resumePersistence.await(10, TimeUnit.SECONDS)); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
		});
		var checkpoint = Executors.newSingleThreadExecutor();
		try {
			var future = checkpoint.submit(RemoteRecorder::checkpointNow);
			assertTrue(enteredPersistence.await(5, TimeUnit.SECONDS));
			try (var scope = RemoteTestContext.enter(during)) { RemoteRecorder.methodHit("example.Work", "during", "()V"); }
			resumePersistence.countDown();
			future.get(10, TimeUnit.SECONDS);
		} finally {
			resumePersistence.countDown();
			checkpoint.shutdownNow();
			RemoteRecorder.beforeCheckpointPersistForTests(null);
		}
		String persistedFirst = Files.readString(output);
		assertTrue(persistedFirst.contains("example.Work#before()V"));
		assertFalse(persistedFirst.contains("example.Work#during()V"));
		assertTrue(RemoteRecorder.memorySnapshot().contains("example.Work#during()V"));
		RemoteRecorder.checkpointNow();
		assertTrue(Files.readString(output).contains("example.Work#during()V"));
		RemoteRecorder.writeOutput();
	}

	@Test void configuredPeriodicCheckpointPersistsWithoutWaitingForShutdown() throws Exception {
		var output = Files.createTempDirectory("remote-periodic-checkpoint").resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		RemoteRecorder.startPeriodicCheckpoints(1);
		try (var scope = RemoteTestContext.enter(TestRequests.request("periodic"))) {
			RemoteRecorder.methodHit("example.Work", "periodic", "()V");
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
		while (System.nanoTime() < deadline && !Files.readString(output).contains("example.Work#periodic()V")) Thread.sleep(25);
		assertTrue(Files.readString(output).contains("example.Work#periodic()V"));
		assertTrue(RemoteRecorder.memorySnapshot().contains("\"requests\": [\n\n"));
		RemoteRecorder.writeOutput();
	}

	@Test void cleanShutdownFinalCheckpointIncludesHitsAfterLastPeriodicCheckpoint() throws Exception {
		var output = Files.createTempDirectory("remote-shutdown-checkpoint").resolve("observations.json");
		RemoteRecorder.clearForTests();
		RemoteRecorder.install(output);
		RemoteRequestIdentity checkpointed = TestRequests.request("shutdown-first");
		try (var scope = RemoteTestContext.enter(checkpointed)) { RemoteRecorder.methodHit("example.Work", "first", "()V"); }
		RemoteRecorder.checkpointNow();
		RemoteRequestIdentity pending = TestRequests.request("shutdown-final");
		try (var scope = RemoteTestContext.enter(pending)) { RemoteRecorder.methodHit("example.Work", "final", "()V"); }
		RemoteRecorder.writeOutput();
		String json = Files.readString(output);
		assertTrue(json.contains("example.Work#first()V"));
		assertTrue(json.contains("example.Work#final()V"));
		assertFalse(Files.exists(output.resolveSibling(output.getFileName() + ".inprogress")));
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
