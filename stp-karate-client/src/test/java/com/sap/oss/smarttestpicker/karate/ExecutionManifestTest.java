// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import com.intuit.karate.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionManifestTest {
    @TempDir Path directory;

    private ExecutionManifest.Request row(String id) {
        return new ExecutionManifest.Request("karate-test", id, "classpath:features/orders.feature", 0, 2, -1,
                "POST", ExecutionManifest.safeUri("https://user:password@example.test/orders;jsessionid=secret?token=secret#secret"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Path output) throws Exception {
        Map<?, ?> doc = (Map<?, ?>) JsonUtils.fromJson(Files.readString(output));
        assertEquals(1, ((Number)doc.get("schemaVersion")).intValue());
        assertEquals(java.util.Set.of("schemaVersion", "source", "requests"), doc.keySet());
        return (List<Map<String, Object>>)doc.get("requests");
    }

    @Test void completesEmptyRunAndFinalizationIsIdempotent() throws Exception {
        var manifest = new ExecutionManifest("suite", directory.resolve("nested"), "stp-karate");
        assertFalse(Files.exists(manifest.output()));
        manifest.finish();
        assertTrue(rows(manifest.output()).isEmpty());
        byte[] before = Files.readAllBytes(manifest.output());
        manifest.finish();
        assertArrayEquals(before, Files.readAllBytes(manifest.output()));
        assertThrows(IllegalStateException.class, () -> manifest.record(row(UUID.randomUUID().toString())));
    }

    @Test void filenameIsSafeAndRunIdsAreUniqueWithoutChangingSuiteIdentity() throws Exception {
        String suite = "checkout/日本 ? \\\"";
        var first = new ExecutionManifest(suite, directory, "custom");
        var second = new ExecutionManifest(suite, directory, "custom");
        assertNotEquals(first.runId(), second.runId());
        assertEquals(directory, first.output().getParent());
        assertTrue(first.output().getFileName().toString().matches("custom-[A-Za-z0-9._-]+\\.json"));
        assertTrue(first.output().getFileName().toString().endsWith(first.runId() + ".json"));
        first.finish(); second.finish();
        Map<?, ?> doc = (Map<?, ?>) JsonUtils.fromJson(Files.readString(first.output()));
        assertEquals(Map.of("suiteId", suite, "runId", first.runId()), doc.get("source"));
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }

    @Test void sanitizedSuiteNamesRemainDistinct() {
        UUID id = UUID.randomUUID();
        var first = new ExecutionManifest("a/b", directory, "stp-karate", id);
        var second = new ExecutionManifest("a?b", directory, "stp-karate", id);
        assertNotEquals(first.output(), second.output());
    }

    @Test void invalidOutputFailsBeforeRunStarts() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionManifest("suite", null, "stp-karate"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionManifest("suite", Path.of(""), "stp-karate"));
        for (String prefix : List.of("", "../outside", "a/b", "a\\b", "a\n", "x".repeat(65)))
            assertThrows(IllegalArgumentException.class, () -> new ExecutionManifest("suite", directory, prefix));
        Path file = Files.writeString(directory.resolve("file"), "existing");
        var failure = assertThrows(IllegalStateException.class, () -> new ExecutionManifest("suite", file, "stp-karate"));
        assertTrue(failure.getMessage().contains(file.toString()));
    }

    @Test void existingFileIsNeverOverwrittenAtInitializationOrCompletion() throws Exception {
        UUID id = UUID.randomUUID();
        var manifest = new ExecutionManifest("suite", directory, "stp-karate", id);
        Files.writeString(manifest.output(), "existing completed output");
        assertThrows(IllegalStateException.class, () -> new ExecutionManifest("suite", directory, "stp-karate", id));
        assertThrows(IllegalStateException.class, manifest::finish);
        assertEquals("existing completed output", Files.readString(manifest.output()));
    }

    @Test void concurrentRecordingPersistsEveryUniqueRequestOnce() throws Exception {
        var manifest = new ExecutionManifest("suite", directory, "stp-karate");
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var work = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) work.add(pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                for (int j = 0; j < 100; j++) manifest.record(row(UUID.randomUUID().toString()));
            }));
            start.countDown();
            for (var future : work) future.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        manifest.finish();
        var rows = rows(manifest.output());
        assertEquals(800, rows.size());
        assertEquals(800, rows.stream().map(r -> r.get("requestId")).distinct().count());
        assertTrue(rows.stream().allMatch(r -> "karate-test".equals(r.get("testId"))));
    }

    @Test void serializationFailureLeavesNoPartialFinalFileOrTemporaryFile() throws Exception {
        var manifest = new ExecutionManifest("suite", directory, "stp-karate");
        // Inject malformed in-memory metadata to fail after the JSON header was written.
        manifest.record(new ExecutionManifest.Request("test", "request", null, 0, 0, 0, "GET", "/"));
        var failure = assertThrows(IllegalStateException.class, manifest::finish);
        assertTrue(failure.getMessage().contains(manifest.output().toString()));
        assertFalse(Files.exists(manifest.output()));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void writeFailureLeavesUnrelatedFilesUntouched() throws Exception {
        var manifest = new ExecutionManifest("suite", directory.resolve("child"), "stp-karate");
        manifest.record(row(UUID.randomUUID().toString()));
        Files.delete(manifest.output().getParent());
        Files.writeString(manifest.output().getParent(), "block directory");
        Path unrelated = Files.writeString(directory.resolve("unrelated.tmp"), "keep");
        var failure = assertThrows(IllegalStateException.class, manifest::finish);
        assertTrue(failure.getMessage().contains(manifest.output().toString()));
        assertFalse(Files.exists(manifest.output()));
        assertEquals("keep", Files.readString(unrelated));
    }

    @Test void storesOnlyAllowedMetadataAndSanitizesUri() throws Exception {
        var manifest = new ExecutionManifest("suite", directory, "stp-karate");
        manifest.record(row(UUID.randomUUID().toString()));manifest.finish();
        var record = rows(manifest.output()).get(0);
        assertEquals(java.util.Set.of("testId", "requestId", "featurePath", "sectionIndex", "scenarioLine",
                "exampleIndex", "httpMethod", "requestUri"), record.keySet());
        assertEquals("https://example.test/orders", record.get("requestUri"));
        String text = Files.readString(manifest.output());
        assertFalse(text.contains("password"));assertFalse(text.contains("secret"));assertFalse(text.contains("jsessionid"));
        assertEquals("https://example.test/a%20b", ExecutionManifest.safeUri("https://example.test/a%20b?secret=1"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionManifest.safeUri("not-a-url-secret"));
    }
}
