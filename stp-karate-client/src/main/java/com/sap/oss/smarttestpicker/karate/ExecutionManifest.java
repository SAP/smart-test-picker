// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** One local execution artifact. No request bodies, header values, or response data. */
final class ExecutionManifest {
    record Request(String testId, String requestId, String featurePath, int sectionIndex,
            int scenarioLine, int exampleIndex, String httpMethod, String requestUri) { }

    private final String suiteId;
    private final String runId;
    private final Path output;
    private final ConcurrentHashMap<String, Request> requests = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean finished;

    ExecutionManifest(String suiteId, Path directory, String prefix) {
        this(suiteId, directory, prefix, UUID.randomUUID());
    }

    // Explicit UUID only for deterministic collision tests; production always generates one.
    ExecutionManifest(String suiteId, Path directory, String prefix, UUID runId) {
        this.suiteId = StpKarateClient.resolveSuiteId(suiteId, null);
        this.runId = runId.toString();
        if (directory == null || directory.toString().isBlank())
            throw new IllegalArgumentException("stp.outputDirectory must be configured and nonblank");
        if (prefix == null || !prefix.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("stp.outputPrefix must be 1-64 filename-safe characters, starting with a letter or digit");
        output = directory.toAbsolutePath().normalize().resolve(prefix + "-" + safeSuite(suiteId) + "-" + runId + ".json");
        try {
            Files.createDirectories(output.getParent());
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("manifest already exists");
            // Fail before tests start when the directory cannot accept files.
            Path probe = Files.createTempFile(output.getParent(), ".stp-write-check-", ".tmp");
            Files.delete(probe);
        } catch (IOException failure) { throw outputFailure(failure); }
    }

    Path output() { return output; }
    String runId() { return runId; }

    void record(Request request) {
        lifecycle.readLock().lock();
        try {
            if (finished) throw new IllegalStateException("STP manifest is already finalized: " + output);
            if (requests.putIfAbsent(request.requestId(), request) != null)
                throw new IllegalStateException("Duplicate STP RequestID in manifest: " + output);
        } finally { lifecycle.readLock().unlock(); }
    }

    void finish() {
        lifecycle.writeLock().lock();
        Path temporary = null;
        try {
            if (finished) return;
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("manifest already exists");
            temporary = Files.createTempFile(output.getParent(), "." + output.getFileName() + "-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write("{\"schemaVersion\":1,\"source\":{\"suiteId\":" + quote(suiteId)
                        + ",\"runId\":" + quote(runId) + "},\"requests\":[");
                boolean first = true;
                for (Request r : requests.values().stream().sorted(Comparator.comparing(Request::testId)
                        .thenComparing(Request::requestId)).toList()) {
                    if (!first) writer.write(',');
                    first = false;
                    writer.write("{\"testId\":" + quote(r.testId()) + ",\"requestId\":" + quote(r.requestId())
                            + ",\"featurePath\":" + quote(r.featurePath()) + ",\"sectionIndex\":" + r.sectionIndex()
                            + ",\"scenarioLine\":" + r.scenarioLine() + ",\"exampleIndex\":" + r.exampleIndex()
                            + ",\"httpMethod\":" + quote(r.httpMethod()) + ",\"requestUri\":" + quote(r.requestUri()) + "}");
                }
                writer.write("]}\n");
            }
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
            // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING.
            // Link + unlink publishes complete bytes atomically AND fails if the target exists.
            // A filesystem without hard-link support fails visibly instead of weakening safety.
            Files.createLink(output, temporary);
            finished = true;
        } catch (IOException | RuntimeException failure) { throw outputFailure(failure); }
        finally {
            try { if (temporary != null) Files.deleteIfExists(temporary); }
            catch (IOException failure) { throw outputFailure(failure); }
            finally { lifecycle.writeLock().unlock(); }
        }
    }

    private IllegalStateException outputFailure(Exception cause) {
        return new IllegalStateException("Cannot write STP execution manifest '" + output + "': " + cause.getMessage(), cause);
    }

    static String safeUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
                throw new IllegalArgumentException();
            String authority = uri.getRawAuthority();
            authority = authority.substring(authority.lastIndexOf('@') + 1);
            // Query, fragment, user-info and path parameters (including session IDs) are omitted.
            return uri.getScheme() + "://" + authority + uri.getRawPath().replaceAll(";[^/]*", "");
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Cannot record requestUri: expected an absolute HTTP(S) URI (value omitted)");
        }
    }

    private static String safeSuite(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 64) safe = safe.substring(0, 64);
        if (safe.equals(value) && !safe.equals(".") && !safe.equals("..")) return safe;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            return safe + "-" + hash.substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
