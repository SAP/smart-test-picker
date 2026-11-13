// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExecutableCoverageMapStreamingTest
{
    @TempDir Path directory;
    private final ExecutableCoverageMapCodec codec = new ExecutableCoverageMapCodec();
    private final ExecutionTarget target = ExecutionTarget.parse("maven:.");

    @Test void dictionariesRoundTripAndShareMethodAndClassObjects() throws Exception
    {
        var a = id("example.Tests", "a"); var b = id("example.Tests", "b");
        var method = new MethodIdentity("example.Value", "value", "()I");
        var coverage = new TestCoverage(Set.of("example.Value"), Set.of(method), TestOutcome.PASS,
                CollectionStatus.COLLECTED_WITH_COVERAGE);
        var original = map(Map.of(a, coverage, b, coverage));
        Path file = directory.resolve("map.gz");
        String checksum = codec.write(original, file);
        var decoded = codec.readVerified(file);
        assertEquals(original, decoded.map()); assertEquals(checksum, decoded.checksum());
        var left = decoded.map().tests().get(a); var right = decoded.map().tests().get(b);
        assertSame(left.coveredMethods().iterator().next(), right.coveredMethods().iterator().next());
        assertSame(left.coveredClasses().iterator().next(), right.coveredMethods().iterator().next().binaryClassName());
        String json = plain(file);
        String body = json.substring(0, json.lastIndexOf(",\"checksum\":")) + "}";
        assertEquals("sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8))), checksum);
        assertTrue(json.contains("\"classMode\":\"derived\""));
        assertTrue(json.contains("\"methods\":[[0,\"value\",\"()I\"]]"));
        assertEquals(checksum, codec.write(decoded.map(), directory.resolve("again.gz")));
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(directory.resolve("again.gz")));
    }

    @Test void classOnlyCoverageUsesExplicitDeltasWithoutInventingMethods() throws Exception
    {
        var coverage = new TestCoverage(Set.of("example.Value"), Set.of(), TestOutcome.PASS,
                CollectionStatus.COLLECTED_WITH_COVERAGE);
        var original = map(Map.of(id("example.Tests", "a"), coverage));
        Path file = directory.resolve("map.gz"); codec.write(original, file);
        assertEquals(original, codec.read(file));
        assertTrue(plain(file).contains("\"classMode\":\"explicit-delta\""));
    }

    @Test void checksumMismatchIsRejectedEvenWithValidGzipTrailer() throws Exception
    {
        Path file = directory.resolve("map.gz"); codec.write(map(Map.of(id("example.Tests", "a"), empty())), file);
        gzip(file, plain(file).replace("\"revision\":\"revision\"", "\"revision\":\"modified\""), 6);
        var error = assertThrows(CoverageMapValidationException.class, () -> codec.read(file));
        assertTrue(error.getMessage().contains("checksum mismatch"));
    }

    @Test void truncatedGzipIsRejectedAfterTheJsonDocumentEnds() throws Exception
    {
        Path file = directory.resolve("map.gz"); codec.write(map(Map.of(id("example.Tests", "a"), empty())), file);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 8));
        assertThrows(IOException.class, () -> codec.read(file));
    }

    @Test void checksumIsIndependentOfCompressionLevel() throws Exception
    {
        Path file = directory.resolve("map.gz");
        String checksum = codec.write(map(Map.of(id("example.Tests", "a"), empty())), file);
        String json = plain(file);
        gzip(file, json, 1); assertEquals(checksum, codec.readVerified(file).checksum());
        gzip(file, json, 9); assertEquals(checksum, codec.readVerified(file).checksum());
    }

    @Test @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void writesAndReadsMoreThanTwoGiBUncompressedWithoutWholeMapBuffers() throws Exception
    {
        // A shared long feature name keeps the model small while the real wire stream exceeds 2 GiB.
        // The name appears in tests, expectedTests and reportedTests; no full JSON is constructed.
        String name = "x".repeat(800_000);
        Map<ExecutableTestIdentity, TestCoverage> tests = new HashMap<>();
        for (int n = 0; n < 900; n++) { tests.put(id("example.Test" + n, name), empty()); }
        Path file = directory.resolve("large.gz");
        String checksum = codec.write(map(tests), file);
        var decoded = codec.readVerified(file);
        assertTrue(decoded.uncompressedBytes() > Integer.MAX_VALUE, "Actual decompressed stream must exceed 2 GiB");
        assertEquals(checksum, decoded.checksum()); assertEquals(900, decoded.map().tests().size());
        assertEquals(tests.keySet(), decoded.map().tests().keySet());
        assertTrue(Files.size(file) < 10_000_000, "Repetitive test data should compress, not consume gigabytes of disk");
        System.out.println("Large map streaming proof: uncompressedBytes=" + decoded.uncompressedBytes()
                + ", gzipBytes=" + Files.size(file) + ", tests=" + decoded.map().tests().size());
    }

    private ExecutableTestIdentity id(String owner, String name)
    {
        return new ExecutableTestIdentity(target, new TestIdentity(owner, name, null));
    }
    private static TestCoverage empty()
    {
        return new TestCoverage(Set.of(), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_EMPTY);
    }
    private static ExecutableCoverageMap map(Map<ExecutableTestIdentity, TestCoverage> tests)
    {
        ShardId shard = new ShardId("1");
        var completeness = new ExecutableCompleteness(tests.keySet(), tests.keySet(), Set.of(shard), Set.of(shard),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        long classes = tests.values().stream().mapToLong(t -> t.coveredClasses().size()).sum();
        long methods = tests.values().stream().mapToLong(t -> t.coveredMethods().size()).sum();
        return new ExecutableCoverageMap(3, new CoverageMapRevision("revision"), Instant.EPOCH,
                new GeneratorProvenance("test", "test", null, "17"), tests, List.of(), List.of(), completeness,
                new MapStatistics(tests.size(), tests.size(), 0, 0, classes, methods), CoverageMapLifecycleState.PUBLISHED, null);
    }
    private static String plain(Path file) throws IOException
    {
        try (var gzip = new GZIPInputStream(Files.newInputStream(file)))
        { return new String(gzip.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private static void gzip(Path file, String json, int level) throws IOException
    {
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(file)) {{ def.setLevel(level); }})
        { gzip.write(json.getBytes(StandardCharsets.UTF_8)); }
    }
}
