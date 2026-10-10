// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import com.fasterxml.jackson.databind.*;
import example.chains.ChainBoundaries;
import example.chainsupport.ChainServer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RemoteCombinedPropagationIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> PLATFORM = List.of("cf", "fork", "bulk", "thread", "reflection", "handle", "shared");
    static final Map<String, String> BOUNDARIES = new HashMap<>();
    static {
        for (var method : ChainBoundaries.class.getDeclaredMethods())
            BOUNDARIES.put(method.getName(), "example.chains.ChainBoundaries#" + method.getName() + Type.getMethodDescriptor(method));
    }
    static String leaf(String name) { return "example.chains.ChainMethods#" + name + "()V"; }
    static String testId(String request) { return "test-" + request.substring(request.lastIndexOf('-') + 1); }
    static String initialStep(String kind) {
        return switch (kind) { case "fork" -> "fork"; case "thread", "virtual" -> "thread"; case "reflection", "handle" -> "indirect"; default -> "executor"; };
    }
    static void expect(Map<String, Set<String>> map, String id, String... methods) {
        if (id != null) map.computeIfAbsent(id, ignored -> new TreeSet<>()).addAll(List.of(methods));
    }
    static Map<String, Set<String>> copy(Map<String, Set<String>> source) {
        Map<String, Set<String>> result = new TreeMap<>(); source.forEach((id, methods) -> result.put(id, new TreeSet<>(methods))); return result;
    }
    static void beforeCompletion(Map<String, Set<String>> expected, String kind, String id, String side) {
        String boundary = switch (kind) { case "cf", "bulk", "shared" -> "execute"; default -> kind; };
        expect(expected, id, BOUNDARIES.get(boundary), leaf(initialStep(kind) + side), BOUNDARIES.get("callback"));
        if (kind.equals("fork")) expect(expected, id, BOUNDARIES.get("submit"), leaf("nested" + side));
        if (kind.equals("bulk")) expect(expected, id, BOUNDARIES.get("bulk"), leaf("bulk" + side));
    }
    static void afterCompletion(Map<String, Set<String>> expected, String kind, String id, String side) {
        expect(expected, id, leaf("callback" + side));
        if (kind.equals("cf")) expect(expected, id, BOUNDARIES.get("schedule"), leaf("scheduled" + side));
    }

    @Test void combinedPlatformChainsCheckpointAndIsolation() throws Exception { verify(PLATFORM); }
    @Test void combinedVirtualThreadChainOnJdk21() throws Exception { verify(List.of("virtual")); }

    private void verify(List<String> kinds) throws Exception {
        Map<String, Set<String>> expected = new TreeMap<>();
        try (ServerRun server = new ServerRun()) {
            assertTrue(server.get("snapshots", null).path("output").isNull());
            Integer sharedCallback = null;
            for (String kind : kinds) {
                Map<String, Set<String>> previousDisk = copy(expected);
                Map<String, JsonNode> owners = new HashMap<>();
                String prefix = kind + "-parallel";
                // Both HTTP responses return before the initial task gate is opened.
                for (String side : List.of("A", "B")) {
                    String key = prefix + "-" + side;
                    JsonNode start = server.get("start?key=" + key + "&kind=" + kind + "&side=" + side + "&barrier=" + prefix, key);
                    owners.put(side, start.path("owner"));
                    if (sharedCallback == null) sharedCallback = start.path("callbackObject").asInt();
                    else assertEquals(sharedCallback.intValue(), start.path("callbackObject").asInt(), "same original Function object across requests/chains");
                    beforeCompletion(expected, kind, key, side);
                }
                for (String side : List.of("A", "B")) server.get("release?key=" + prefix + "-" + side, null);
                for (String side : List.of("A", "B")) {
                    String key = prefix + "-" + side;
                    JsonNode registered = server.get("registered?key=" + key, null);
                    assertEquals(kind.equals("bulk") ? 2 : 1, registered.path("callbacks").asInt());
                    if (kind.equals("bulk")) assertEquals(JSON.readTree("[\"left\",\"right\"]"), registered.path("bulkResults"));
                    server.get("complete?key=" + key, key + "-complete-C");
                    expect(expected, key + "-complete-C", leaf("completion"));
                    afterCompletion(expected, kind, key, side);
                }
                for (String side : List.of("A", "B")) server.get("ready?key=" + prefix + "-" + side, null);
                assertFragment(server, server.get("snapshots", null).path("snapshot"), expected);
                Map<String, Set<String>> captured = copy(expected);
                server.get("checkpoint/begin", null); // Snapshot captured, persistence deliberately held.
                Map<String, Set<String>> late = new TreeMap<>();
                for (String side : List.of("A", "B")) {
                    String key = prefix + "-" + side;
                    server.get("finish?key=" + key, null);
                    expect(expected, key, leaf("late" + side)); expect(late, key, leaf("late" + side));
                }
                Set<Long> initialWorkers = new HashSet<>();
                for (String side : List.of("A", "B")) {
                    String key = prefix + "-" + side;
                    JsonNode result = server.get("result?key=" + key, null);
                    verifyResult(kind, key, result, owners.get(side), false, false);
                    assertTrue(initialWorkers.add(result.path("events").get(0).path("thread").asLong()), "barrier requires distinct concurrent workers");
                }
                JsonNode during = server.get("snapshots", null);
                assertFragment(server, during.path("snapshot"), expected);
                if (previousDisk.isEmpty()) assertTrue(during.path("output").isNull(), "reading snapshot must not flush");
                else assertFragment(server, during.path("output"), previousDisk);
                server.get("checkpoint/finish", null);
                JsonNode after = server.get("snapshots", null);
                assertFragment(server, after.path("output"), captured);
                assertFragment(server, after.path("memory"), late);
                assertFragment(server, after.path("snapshot"), expected);
                assertClean(server);
                server.get("checkpoint", null);
                after = server.get("snapshots", null);
                assertFragment(server, after.path("output"), expected);
                assertFragment(server, after.path("memory"), Map.of());

                // Headerless work executes between attributed A and B requests on reused pools.
                for (String side : List.of("A", "H", "B")) {
                    String key = kind + "-sequential-" + side;
                    completeOne(server, expected, kind, key, side.equals("H") ? null : key, side.equals("B") ? "B" : "A", false, false, false);
                }
                // Compact sequential evidence before the next checkpoint-in-flight experiment.
                server.get("checkpoint", null);
            }
            if (kinds.contains("cf")) {
                Long sameWorker = null;
                for (String side : List.of("A", "H", "B", "A")) {
                    String key = "reuse-" + expected.size() + "-" + side;
                    JsonNode result = completeOne(server, expected, "cf", key, side.equals("H") ? null : key,
                            side.equals("B") ? "B" : "A", true, false, false);
                    long worker = result.path("events").get(0).path("thread").asLong();
                    if (sameWorker == null) sameWorker = worker; else assertEquals(sameWorker.longValue(), worker, "same physical worker reused");
                }
                completeOne(server, expected, "cf", "failure-A", "failure-A", "A", true, true, false);
                completeOne(server, expected, "cf", "cancel-A", "cancel-A", "A", true, false, true);
                completeOne(server, expected, "cf", "after-failure-H", null, "A", true, false, false);
                completeOne(server, expected, "cf", "after-failure-B", "after-failure-B", "B", true, false, false);
            }
            assertFragment(server, server.get("snapshots", null).path("snapshot"), expected);
            assertClean(server); server.close();
            assertFragment(server, JSON.readTree(server.output.toFile()), expected);
        }
    }

    static JsonNode completeOne(ServerRun server, Map<String, Set<String>> expected, String kind, String key,
            String owner, String side, boolean single, boolean failure, boolean cancelled) throws Exception {
        JsonNode start = server.get("start?key=" + key + "&kind=" + kind + "&side=" + side + "&single=" + single + "&fail=" + failure, owner);
        server.get("release?key=" + key, null); server.get("registered?key=" + key, null);
        beforeCompletion(expected, kind, owner, side);
        if (cancelled) assertTrue(server.get("cancel?key=" + key, null).path("cancelled").asBoolean());
        server.get("complete?key=" + key, key + "-complete-C"); expect(expected, key + "-complete-C", leaf("completion"));
        if (!cancelled) {
            expect(expected, owner, leaf("callback" + side));
            if (failure) expect(expected, owner, leaf("failed" + side));
            else {
                afterCompletion(expected, kind, owner, side);
                server.get("ready?key=" + key, null); server.get("finish?key=" + key, null);
                expect(expected, owner, leaf("late" + side));
            }
        }
        JsonNode result = server.get("result?key=" + key, null);
        verifyResult(kind, key, result, start.path("owner"), failure, cancelled);
        assertClean(server); return result;
    }
    static void verifyResult(String kind, String key, JsonNode result, JsonNode owner, boolean failure, boolean cancelled) {
        if (failure) {
            assertEquals("java.util.concurrent.ExecutionException", result.path("failure").asText(), result.toString());
            assertEquals("java.lang.IllegalStateException", result.path("cause").asText());
        } else if (cancelled) assertEquals("java.util.concurrent.CancellationException", result.path("failure").asText());
        else {
            assertFalse(result.has("failure"), result.toString());
            assertEquals(kind.equals("bulk") ? 2 : 1, result.path("values").size());
            for (JsonNode value : result.path("values")) assertEquals((kind.equals("cf") ? "scheduled-" : "done-") + key, value.asText());
        }
        Map<String, Integer> steps = new TreeMap<>();
        steps.put(initialStep(kind), 1);
        if (kind.equals("fork")) steps.put("nested", 1);
        if (kind.equals("bulk")) steps.put("bulk", 2);
        if (!cancelled) {
            steps.put("callback", kind.equals("bulk") ? 2 : 1);
            if (failure) steps.put("failed", 1);
            else { if (kind.equals("cf")) steps.put("scheduled", 1); steps.put("late", kind.equals("bulk") ? 2 : 1); }
        }
        Map<String, Integer> actual = new TreeMap<>();
        for (JsonNode event : result.path("events")) {
            for (String field : List.of("identity", "extra", "trace")) assertEquals(owner.path(field), event.path(field), key + " " + field + " " + event);
            assertNotEquals(owner.path("thread"), event.path("thread"), key + " actual async transition");
            actual.merge(event.path("step").asText(), 1, Integer::sum);
        }
        assertEquals(steps, actual, "actual application execution is verified even when headerless observations are absent");
        if (kind.equals("virtual")) assertTrue(result.path("events").get(0).path("virtual").asBoolean());
    }
    static void assertClean(ServerRun server) throws Exception {
        JsonNode probe = server.get("probe", null);
        for (JsonNode pool : probe) {
            assertFalse(pool.isEmpty(), "cleanup probes exercised each pool");
            for (JsonNode thread : pool) {
                assertEquals("null", thread.path("identity").asText(), "raw afterExecute identity");
                assertTrue(thread.path("extra").isNull(), "raw afterExecute extra Context");
            }
        }
    }
    static void assertFragment(ServerRun server, JsonNode json, Map<String, Set<String>> expected) {
        assertEquals(2, json.path("schemaVersion").asInt());
        assertEquals("chain-service", json.path("source").path("serviceId").asText());
        assertEquals(server.instance, json.path("source").path("instanceId").asText());
        assertEquals("chain-revision", json.path("source").path("revision").asText());
        Map<String, Set<String>> actual = new TreeMap<>();
        for (JsonNode request : json.path("requests")) {
            String id = request.path("requestId").asText();
            assertEquals("chain-suite", request.path("testSuiteId").asText()); assertEquals(testId(id), request.path("testId").asText());
            Set<String> methods = new TreeSet<>(); request.path("methods").forEach(m -> assertTrue(methods.add(m.asText())));
            assertNull(actual.put(id, methods));
        }
        assertEquals(expected, actual, "exact positive/forbidden request-method relations, not counts");
    }
    static final class ServerRun implements AutoCloseable {
        final String instance = "chain-jvm-" + UUID.randomUUID();
        final Path output; final Process process; final URI root; boolean closed;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ServerRun() throws Exception {
            Path evidence = Files.createTempDirectory(Path.of("build"), "combined-chains-");
            output = evidence.resolve("observations.json").toAbsolutePath(); Path log = evidence.resolve("server.log");
            process = new ProcessBuilder(System.getenv().getOrDefault("STP_CHAIN_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                    "-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none",
                    "-Djava.util.concurrent.ForkJoinPool.common.parallelism=2", "-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                    "-javaagent:" + System.getProperty("stp.otel.agent.jar"),
                    "-javaagent:" + System.getProperty("stp.remote.agent.jar") + "=output=" + output
                            + ";includes=example.chains.;serviceId=chain-service;instanceId=" + instance + ";revision=chain-revision;flushIntervalSeconds=3600",
                    "-cp", System.getProperty("java.class.path"), ChainServer.class.getName())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            String port = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (port == null && process.isAlive() && System.nanoTime() < deadline) {
                for (String line : Files.readAllLines(log)) if (line.startsWith("READY:")) port = line.substring(6).trim();
                if (port == null) Thread.sleep(25); // Readiness only. Scenario coordination is latch/barrier-based.
            }
            if (port == null) { process.destroyForcibly(); throw new AssertionError(Files.readString(log)); }
            root = URI.create("http://127.0.0.1:" + port + "/");
            System.out.println("Combined chain JVM=" + process.pid() + " evidence=" + evidence.toAbsolutePath());
        }
        JsonNode get(String path, String owner) throws Exception {
            var request = HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(30));
            if (owner != null) request.header("baggage", "stp.test.suite.id=chain-suite,stp.test.id=" + testId(owner) + ",stp.request.id=" + owner);
            var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path + ": " + response.body()); return JSON.readTree(response.body());
        }
        @Override public void close() throws Exception {
            if (closed) return; closed = true; process.destroy();
            if (!process.waitFor(25, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("chain fixture did not finalize " + output); }
            assertTrue(Files.isRegularFile(output), "missing final fragment " + output);
        }
    }
}
