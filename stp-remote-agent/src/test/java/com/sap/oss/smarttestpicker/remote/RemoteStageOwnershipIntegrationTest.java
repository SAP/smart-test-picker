// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import com.fasterxml.jackson.databind.*;
import example.cfsupport.StageOwnershipServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RemoteStageOwnershipIntegrationTest {
    static final List<String> FAMILIES = List.of("thenApply", "thenAccept", "thenRun", "thenCompose", "handle",
            "whenComplete", "exceptionally", "thenCombine", "thenAcceptBoth", "runAfterBoth",
            "applyToEither", "acceptEither", "runAfterEither");
    static final List<String> MODES = List.of("sync", "async", "executor");
    static final String REGISTER = "example.cf.ObservedStages#register(Ljava/lang/String;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;Lexample/cfsupport/StageCallbacks;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";
    static final ObjectMapper JSON = new ObjectMapper();
    static String leaf(String name) { return "example.cf.ObservedStages#" + name + "()V"; }

    @Test void measuresOtelOnlyRegistrationOwnership() throws Exception {
        List<String> gaps = new ArrayList<>();
        Map<String, Set<String>> expected = new TreeMap<>();
        try (ServerRun server = new ServerRun(true)) {
            for (String family : FAMILIES) for (String mode : MODES) {
                String shape = family + "-" + mode;
                server.get("register?key=" + shape + "&shape=" + shape, shape);
                server.get("complete?key=" + shape, shape + "-completer");
                if (family.equals("thenCompose")) server.get("inner?key=" + shape, shape + "-completer");
                expect(expected, shape, REGISTER);
                expect(expected, shape + "-completer", leaf("completion"), leaf("afterCompletion"), leaf("a"));
                JsonNode result = server.get("result?key=" + shape, null);
                if (!result.path("owner").equals(result.path("actual")) || !shape.equals(result.path("extra").asText())) gaps.add(shape);
            }
            server.close();
            assertFragment(server.output, server.instance, expected);
        }
        assertEquals(39, gaps.size(), "Pinned OTel baseline changed: re-evaluate which adapters remain necessary");
        System.out.println("OTel 2.32.0 registration ownership gaps (completion under B): " + gaps);
    }

    @Test void allStageShapesKeepRegistrationOwnershipAndApiSemantics() throws Exception {
        Map<String, Set<String>> expected = new TreeMap<>();
        try (ServerRun server = new ServerRun(false)) {
            Long explicitWorker = null;
            for (String family : FAMILIES) for (String mode : MODES) {
                String shape = family + "-" + mode;
                for (String scenario : List.of("completed", "no-completion-context", "completion-B", "headerless", "throws", "source-exception", "cancel-source", "cancel-stage")) {
                    String key = shape + "-" + scenario;
                    String owner = scenario.equals("headerless") ? null : key;
                    String completer = scenario.equals("no-completion-context") ? null : key + "-B";
                    boolean ready = scenario.equals("completed");
                    boolean cancelledStage = scenario.equals("cancel-stage");
                    boolean cancelledSource = scenario.equals("cancel-source");
                    boolean failedSource = scenario.equals("source-exception");
                    boolean callbackRuns = !cancelledStage && (!(cancelledSource || failedSource) || List.of("handle", "whenComplete", "exceptionally").contains(family));
                    server.get("register?key=" + key + "&shape=" + shape + "&ready=" + ready + "&fail=" + scenario.equals("throws"), owner);
                    expect(expected, owner, REGISTER);
                    if (cancelledStage) assertTrue(server.get("cancel?key=" + key, null).path("cancelled").asBoolean());
                    server.get("complete?key=" + key + (cancelledSource ? "&outcome=cancel" : failedSource ? "&outcome=exception" : ""), completer);
                    expect(expected, completer, leaf("completion"), leaf("afterCompletion"));
                    if (family.equals("thenCompose") && callbackRuns && !scenario.equals("throws")) {
                        assertTrue(server.get("inner?key=" + key, completer).path("pending").asBoolean());
                    }
                    JsonNode result = server.get("result?key=" + key, null);
                    assertEquals(callbackRuns ? 1 : 0, result.path("calls").asInt(), key);
                    if (callbackRuns) {
                        assertEquals(result.path("owner"), result.path("actual"), key + " identity");
                        assertEquals(key, result.path("extra").asText(), key + " full Context key");
                        assertEquals(result.path("registrationTrace"), result.path("trace"), key + " trace identity");
                        if (!mode.equals("sync")) assertNotEquals(result.path("requestThread"), result.path("callbackThread"), key + " async thread");
                        if (mode.equals("async")) assertTrue(result.path("callbackThreadName").asText().startsWith("ForkJoinPool.commonPool-worker-"), key);
                        if (mode.equals("executor")) {
                            long worker = result.path("callbackThread").asLong();
                            if (explicitWorker == null) explicitWorker = worker;
                            else assertEquals(explicitWorker.longValue(), worker, "same physical worker reused across requests and failures");
                        }
                        String input = (cancelledSource || failedSource || family.equals("exceptionally")
                                || List.of("thenRun", "runAfterBoth", "runAfterEither").contains(family)) ? null
                                : (family.equals("thenCombine") || family.equals("thenAcceptBoth")) ? "left:right" : "left";
                        if (input == null) assertTrue(result.path("input").isNull(), key);
                        else assertEquals(input, result.path("input").asText(), key);
                        String error = cancelledSource ? "java.util.concurrent.CancellationException"
                                : (failedSource || family.equals("exceptionally")) ? "java.lang.IllegalArgumentException" : null;
                        if (error == null) assertTrue(result.path("error").isNull(), key);
                        else assertEquals(error, result.path("error").asText(), key);
                        expect(expected, owner, leaf("a"));
                    }
                    if (scenario.equals("throws")) assertEquals("java.lang.IllegalStateException", result.path("failure").asText(), key);
                    else if (cancelledStage || (cancelledSource && !List.of("handle", "exceptionally").contains(family)))
                        assertEquals("java.util.concurrent.CancellationException", result.path("failure").asText(), key);
                    else if (failedSource && !List.of("handle", "exceptionally").contains(family))
                        assertEquals("java.lang.IllegalArgumentException", result.path("failure").asText(), key);
                    else {
                        String value = switch (family) {
                            case "thenApply", "applyToEither" -> "left-result";
                            case "handle" -> (cancelledSource || failedSource) ? "recovered" : "left-result";
                            case "exceptionally" -> "recovered";
                            case "thenCompose" -> "inner-result";
                            case "whenComplete" -> "left";
                            case "thenCombine" -> "left:right-result";
                            default -> null;
                        };
                        if (value == null) assertTrue(result.path("result").isNull(), key + result);
                        else assertEquals(value, result.path("result").asText(), key);
                        assertFalse(result.has("failure"), key + result);
                    }
                    assertEquals("null:null", server.get("probe", null).path("worker").asText(), key + " worker cleanup");
                }
            }
            sharedFutureAndConcurrentCallbacks(server, expected);
            binaryOwnersAndDeferredChain(server, expected);
            server.close();
            assertFragment(server.output, server.instance, expected);
        }
    }

    private static void sharedFutureAndConcurrentCallbacks(ServerRun server, Map<String, Set<String>> expected) throws Exception {
        for (String family : FAMILIES) for (String mode : MODES) {
            String key = "shared-" + family + "-" + mode;
            // Same predecessor; async callbacks rendezvous before returning to prove actual overlap.
            for (String owner : List.of("A", "B")) {
                server.get("register?key=" + key + owner + "&source=" + key + "&shape=" + family + "-" + mode
                        + "&leaf=" + owner.toLowerCase() + (mode.equals("sync") ? "" : "&barrier=" + key), key + owner);
                expect(expected, key + owner, REGISTER, leaf(owner.toLowerCase()));
            }
            server.get("complete?key=" + key + "A", key + "C");
            expect(expected, key + "C", leaf("completion"), leaf("afterCompletion"));
            for (String owner : List.of("A", "B")) {
                if (family.equals("thenCompose")) server.get("inner?key=" + key + owner, null);
                JsonNode result = server.get("result?key=" + key + owner, null);
                assertEquals(result.path("owner"), result.path("actual"), key + owner);
                assertEquals(1, result.path("calls").asInt(), key + owner);
                assertFalse(result.has("failure"), key + owner + result);
            }
        }
    }

    private static void binaryOwnersAndDeferredChain(ServerRun server, Map<String, Set<String>> expected) throws Exception {
        for (String family : FAMILIES.subList(7, 13)) for (String mode : MODES) {
            String key = "binary-" + family + "-" + mode;
            server.get("create?key=" + key, key + "-A");
            server.get("create?key=" + key + "-other", key + "-B");
            expect(expected, key + "-A", leaf("a"));
            expect(expected, key + "-B", leaf("a"));
            server.get("register?key=" + key + "&shape=" + family + "-" + mode + "&leaf=c", key + "-C");
            expect(expected, key + "-C", REGISTER, leaf("c"));
            server.get("complete?key=" + key + "&side=left", key + "-A");
            server.get("complete?key=" + key + "&side=right", key + "-B");
            expect(expected, key + "-A", leaf("completion"), leaf("afterCompletion"));
            expect(expected, key + "-B", leaf("completion"), leaf("afterCompletion"));
            JsonNode result = server.get("result?key=" + key, null);
            assertEquals(result.path("owner"), result.path("actual"), key);
            assertEquals(1, result.path("calls").asInt(), key);
        }
        for (String mode : MODES) {
            String key = "chain-" + mode;
            server.get("register?key=" + key + "&shape=thenCompose-" + mode + "&chain=true", key);
            expect(expected, key, REGISTER, leaf("a"), leaf("continuation"),
                    "example.cf.ObservedStages#follow(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;");
            server.get("complete?key=" + key, key + "-B");
            expect(expected, key + "-B", leaf("completion"), leaf("afterCompletion"));
            assertTrue(server.get("inner?key=" + key, key + "-C").path("pending").asBoolean());
            JsonNode result = server.get("result?key=" + key, null);
            assertTrue(result.path("result").isNull());
            assertFalse(result.has("failure"));
            assertEquals(result.path("owner"), result.path("actual"));
        }
    }

    static void expect(Map<String, Set<String>> expected, String owner, String... methods) {
        if (owner != null) expected.computeIfAbsent(owner, ignored -> new TreeSet<>()).addAll(List.of(methods));
    }
    static void assertFragment(Path output, String instance, Map<String, Set<String>> expected) throws Exception {
        JsonNode json = JSON.readTree(output.toFile());
        assertEquals(2, json.path("schemaVersion").asInt());
        assertEquals("cf-service", json.path("source").path("serviceId").asText());
        assertEquals(instance, json.path("source").path("instanceId").asText());
        assertEquals("cf-revision", json.path("source").path("revision").asText());
        Map<String, Set<String>> actual = new TreeMap<>();
        for (JsonNode request : json.path("requests")) {
            String id = request.path("requestId").asText();
            assertEquals("suite-" + id, request.path("testSuiteId").asText());
            assertEquals("test-" + id, request.path("testId").asText());
            Set<String> methods = new TreeSet<>();
            request.path("methods").forEach(m -> assertTrue(methods.add(m.asText()), "duplicate method"));
            assertNull(actual.put(id, methods), "duplicate request");
        }
        assertEquals(expected, actual, "Exact descriptor-aware positive AND negative method relations in finalized fragment");
    }

    static final class ServerRun implements AutoCloseable {
        final String instance = "cf-jvm-" + UUID.randomUUID();
        final Path output;
        final Process process;
        final URI root;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        boolean closed;
        ServerRun(boolean otelOnly) throws Exception {
            Path evidence = Files.createTempDirectory(Path.of("build"), otelOnly ? "cf-otel-only-" : "cf-registration-");
            output = evidence.resolve("observations.json").toAbsolutePath();
            Path log = evidence.resolve("server.log");
            process = new ProcessBuilder(System.getenv().getOrDefault("STP_CF_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                    "-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none",
                    "-Djava.util.concurrent.ForkJoinPool.common.parallelism=2",
                    "-Dstp.remote.otel.contextOnly=" + otelOnly,
                    "-javaagent:" + System.getProperty("stp.otel.agent.jar"),
                    "-javaagent:" + System.getProperty("stp.remote.agent.jar") + "=output=" + output
                            + ";includes=example.cf.;serviceId=cf-service;instanceId=" + instance + ";revision=cf-revision",
                    "-cp", System.getProperty("java.class.path"), StageOwnershipServer.class.getName())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            // Readiness polling only; scenario ordering is HTTP completion, latches and barriers.
            String port = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (port == null && process.isAlive() && System.nanoTime() < deadline) {
                for (String line : Files.readAllLines(log)) if (line.startsWith("READY:")) port = line.substring(6).trim();
                if (port == null) Thread.sleep(25);
            }
            if (port == null) { process.destroyForcibly(); throw new AssertionError(Files.readString(log)); }
            root = URI.create("http://127.0.0.1:" + port + "/");
            System.out.println("CF child JVM=" + process.pid() + " evidence=" + evidence.toAbsolutePath());
        }
        JsonNode get(String path, String owner) throws Exception {
            var request = HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(20));
            if (owner != null) request.header("baggage", "stp.test.suite.id=suite-" + owner
                    + ",stp.test.id=test-" + owner + ",stp.request.id=" + owner);
            var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path + ": " + response.body());
            return JSON.readTree(response.body());
        }
        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("CF fixture did not finalize cleanly: " + output);
            }
            assertTrue(Files.isRegularFile(output), "missing final fragment " + output);
        }
    }
}
