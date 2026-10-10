// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import com.fasterxml.jackson.databind.*;
import example.threadsupport.ThreadOwnershipServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RemoteThreadOwnershipIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String APP = "example.threadapp.ThreadOperations";
    static final String CREATE = APP + "#create(Ljava/lang/String;Ljava/lang/Runnable;)Ljava/lang/Thread;";
    static final String START = APP + "#start(Ljava/lang/Thread;)V";
    static final List<String> PLATFORM = List.of("plain", "named", "group", "group-name", "stack", "inherit-false",
            "override", "override-target", "inherited", "delegating");
    static final List<String> BUILDERS = List.of("platform-start", "virtual-start", "virtual-static",
            "platform-unstarted", "virtual-unstarted", "platform-factory", "virtual-factory");
    static String leaf(String method) { return APP + "#" + method + "()V"; }
    static boolean immediate(String shape) { return List.of("platform-start", "virtual-start", "virtual-static").contains(shape); }

    @Test void platformThreadsHaveStartOwnership() throws Exception { verify(PLATFORM); }
    @Test void jdk21BuildersAndVirtualThreadsHaveStartOwnership() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("STP_THREAD_JAVA") == null,
                "JDK17 child run selects only the platform fixture; builders are exercised in JDK21 regression");
        verify(BUILDERS);
    }
    @Test void otelOnlyDoesNotEstablishDirectThreadStartOwnership() throws Exception {
        try (ServerRun server = new ServerRun(true)) {
            Map<String, Set<String>> expected = new TreeMap<>();
            List<String> shapes = new ArrayList<>(PLATFORM);
            if (System.getenv("STP_THREAD_JAVA") == null) shapes.addAll(BUILDERS);
            for (String shape : shapes) {
                server.get("create?key=" + shape + "&shape=" + shape, shape + "-A");
                creation(expected, shape + "-A", shape);
                if (!immediate(shape)) {
                    server.get("start?key=" + shape, shape + "-B");
                    expect(expected, shape + "-B", START);
                }
                server.get("release?key=" + shape, null);
                JsonNode result = server.get("result?key=" + shape, null);
                assertEquals("null", result.path("executions").get(0).path("identity").asText(), shape);
            }
            server.close(); server.assertFragment(expected);
        }
    }

    @Test void excludedRunConstructorAndStartBoundariesRemainExplicitGaps() throws Exception {
        try (ServerRun server = new ServerRun(false)) {
            Map<String, Set<String>> expected = new TreeMap<>();
            for (String shape : List.of("outside-run", "outside-construction", "plain")) {
                String key = "excluded-" + shape;
                server.get("create?key=" + key + "&shape=" + shape, key + "-A");
                creation(expected, key + "-A", shape);
                server.get("start?key=" + key + "&type=" + (shape.equals("plain") ? "outside" : "thread"), key + "-B");
                if (!shape.equals("plain")) expect(expected, key + "-B", START);
                server.get("release?key=" + key, null);
                JsonNode actual = server.get("result?key=" + key, null).path("executions").get(0);
                assertEquals("null", actual.path("identity").asText(), shape + " must not retain construction A");
            }
            server.close(); server.assertFragment(expected);
        }
    }

    private static void verify(List<String> shapes) throws Exception {
        Map<String, Set<String>> expected = new TreeMap<>();
        try (ServerRun server = new ServerRun(false)) {
            for (String shape : shapes) {
                List<String> cases = immediate(shape) ? List.of("A", "headerless", "exception", "error", "interrupt", "nested")
                        : List.of("A-to-B", "A-to-headerless", "headerless-to-B", "exception", "error", "interrupt", "nested", "direct");
                for (String scenario : cases) {
                    String key = shape + "-" + scenario;
                    String creator = scenario.equals("headerless-to-B") || scenario.equals("headerless") ? null : key + "-A";
                    String starter = scenario.equals("A-to-headerless") ? null : key + "-B";
                    boolean immediate = immediate(shape);
                    boolean direct = scenario.equals("direct");
                    boolean directRuns = direct && !shape.startsWith("virtual");
                    String options = "&failure=" + scenario + "&nested=" + scenario.equals("nested");
                    JsonNode created = server.get("create?key=" + key + "&shape=" + shape + options, creator);
                    creation(expected, creator, shape);
                    if (direct) {
                        JsonNode call = server.get("direct?key=" + key, key + "-direct");
                        assertEquals(call.path("before"), call.path("after"));
                        expect(expected, key + "-direct", APP + "#direct(Ljava/lang/Thread;)V");
                        assertEquals(directRuns ? 1 : 0, call.path("executions").size(), key);
                        if (directRuns) executed(expected, key + "-direct", shape, false, "a");
                    }
                    JsonNode before;
                    String owner;
                    if (immediate) { before = created.path("construction"); owner = creator; }
                    else {
                        String type = shape.equals("inherited") ? "inherited" : shape.startsWith("override") ? "concrete" : "thread";
                        JsonNode started = server.get("start?key=" + key + "&type=" + type, starter);
                        assertTrue(started.path("started").asBoolean(), key);
                        assertEquals(started.path("before"), started.path("after"), key + " request restored");
                        before = started.path("before"); owner = starter;
                        startMethod(expected, starter, type);
                    }
                    // A second start while the original is blocked must not replace its pending Context.
                    JsonNode second = server.get("restart?key=" + key, key + "-restart");
                    assertEquals("java.lang.IllegalThreadStateException", second.path("failure").asText(), key);
                    expect(expected, key + "-restart", START);
                    if (scenario.equals("interrupt")) server.get("interrupt?key=" + key, null);
                    else server.get("release?key=" + key, null);
                    JsonNode result = server.get("result?key=" + key, null);
                    assertTrue(result.path("terminated").asBoolean());
                    JsonNode executions = result.path("executions");
                    assertEquals((directRuns ? 2 : 1) + (scenario.equals("nested") ? 1 : 0), executions.size(), key);
                    JsonNode actual = executions.get(directRuns ? 1 : 0);
                    assertContext(before, actual, key);
                    assertNotEquals(before.path("thread"), actual.path("thread"), key);
                    assertEquals(shape.startsWith("virtual"), actual.path("virtual").asBoolean(), key);
                    if (shape.contains("name") || shape.equals("stack") || shape.equals("inherit-false") || shape.startsWith("override")
                            || shape.equals("inherited") || shape.equals("delegating") || BUILDERS.contains(shape) && !shape.equals("virtual-static"))
                        assertEquals("supplied-name", actual.path("name").asText(), key);
                    if (List.of("group", "group-name", "stack", "inherit-false").contains(shape)) assertEquals("supplied-group", actual.path("group").asText(), key);
                    assertEquals(scenario.equals("interrupt"), actual.path("interrupted").asBoolean(), key);
                    executed(expected, owner, shape, scenario.equals("exception") || scenario.equals("error"), "a");
                    if (scenario.equals("nested")) {
                        assertContext(before, executions.get(1), key + " child");
                        expect(expected, owner, APP + "#nested(Ljava/lang/Runnable;)V", leaf("b"));
                    }
                    if (scenario.equals("exception") || scenario.equals("error")) {
                        assertEquals(scenario.equals("exception") ? "java.lang.IllegalStateException" : "java.lang.AssertionError", result.path("failureType").asText(), key);
                        assertEquals("null", result.path("failureContext").asText(), key + " uncaught handler after scope closed");
                        assertTrue(result.path("failureExtra").isNull(), key);
                    } else assertTrue(result.path("failureType").isNull(), key + result);
                    assertEquals("java.lang.IllegalThreadStateException", server.get("restart?key=" + key, key + "-restart").path("failure").asText(), key);
                }
                concurrent(server, expected, shape);
            }
            executorReuse(server, expected);
            server.close(); server.assertFragment(expected);
        }
    }
    private static void concurrent(ServerRun server, Map<String, Set<String>> expected, String shape) throws Exception {
        for (String side : List.of("A", "B")) {
            String key = shape + "-parallel-" + side;
            server.get("create?key=" + key + "&shape=" + shape + "&leaf=" + side.toLowerCase() + "&barrier=" + shape, key);
            creation(expected, key, shape);
            if (!immediate(shape)) { server.get("start?key=" + key, key); expect(expected, key, START); }
            executed(expected, key, shape, false, side.toLowerCase());
        }
        for (String side : List.of("A", "B")) server.get("release?key=" + shape + "-parallel-" + side, null);
        for (String side : List.of("A", "B")) {
            String key = shape + "-parallel-" + side;
            JsonNode result = server.get("result?key=" + key, null);
            assertEquals(1, result.path("executions").size());
            assertEquals(identity(key), result.path("executions").get(0).path("identity").asText());
            assertTrue(result.path("failureType").isNull(), result.toString());
        }
    }
    private static void executorReuse(ServerRun server, Map<String, Set<String>> expected) throws Exception {
        long worker = -1;
        for (String key : List.of("executor-A", "executor-B", "executor-headerless")) {
            String owner = key.endsWith("headerless") ? null : key;
            JsonNode result = server.get("executor?key=" + key, owner);
            JsonNode actual = result.path("executions").get(0);
            assertContext(result.path("before"), actual, key);
            if (worker == -1) worker = actual.path("thread").asLong();
            else assertEquals(worker, actual.path("thread").asLong());
            expect(expected, owner, leaf("a"));
            if (key.equals("executor-A")) expect(expected, owner, CREATE);
        }
    }
    static void assertContext(JsonNode before, JsonNode actual, String key) {
        for (String field : List.of("identity", "extra", "trace")) assertEquals(before.path(field), actual.path(field), key + " " + field);
    }
    static String identity(String key) { return "RemoteRequestIdentity[testSuiteId=suite-" + key + ", testId=test-" + key + ", requestId=" + key + "]"; }
    static void expect(Map<String, Set<String>> expected, String owner, String... methods) {
        if (owner != null) expected.computeIfAbsent(owner, ignored -> new TreeSet<>()).addAll(List.of(methods));
    }
    static void creation(Map<String, Set<String>> expected, String owner, String shape) {
        expect(expected, owner, CREATE);
        if (BUILDERS.contains(shape)) expect(expected, owner, "example.threadapp.ThreadBuilders#create(Ljava/lang/String;Ljava/lang/Runnable;)Ljava/lang/Thread;");
        if (shape.startsWith("override") || shape.equals("inherited")) expect(expected, owner, APP + "$OverrideThread#<init>(Ljava/lang/Runnable;Z)V");
        if (shape.equals("inherited")) expect(expected, owner, APP + "$InheritedThread#<init>(Ljava/lang/Runnable;)V");
        if (shape.equals("delegating")) expect(expected, owner, APP + "$DelegatingThread#<init>(Ljava/lang/Runnable;)V");
    }
    static void startMethod(Map<String, Set<String>> expected, String owner, String type) {
        expect(expected, owner, switch (type) {
            case "concrete" -> APP + "#concreteStart(Lexample/threadapp/ThreadOperations$OverrideThread;)V";
            case "inherited" -> APP + "#inheritedStart(Lexample/threadapp/ThreadOperations$InheritedThread;)V";
            default -> START;
        });
    }
    static void executed(Map<String, Set<String>> expected, String owner, String shape, boolean failed, String leaf) {
        expect(expected, owner, leaf(leaf));
        if (shape.startsWith("override") || shape.equals("inherited")) expect(expected, owner, APP + "$OverrideThread#run()V");
        if (shape.equals("delegating")) {
            expect(expected, owner, APP + "$DelegatingThread#run()V");
            if (!failed) expect(expected, owner, leaf("afterRun"));
        }
    }

    static final class ServerRun implements AutoCloseable {
        final String instance = "thread-jvm-" + UUID.randomUUID();
        final Path output;
        final Process process;
        final URI root;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        boolean closed;
        ServerRun(boolean otelOnly) throws Exception {
            Path evidence = Files.createTempDirectory(Path.of("build"), otelOnly ? "thread-otel-only-" : "thread-start-");
            output = evidence.resolve("observations.json").toAbsolutePath();
            Path log = evidence.resolve("server.log");
            process = new ProcessBuilder(System.getenv().getOrDefault("STP_THREAD_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                    "-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none",
                    "-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                    "-Dstp.remote.otel.contextOnly=" + otelOnly,
                    "-javaagent:" + System.getProperty("stp.otel.agent.jar"),
                    "-javaagent:" + System.getProperty("stp.remote.agent.jar") + "=output=" + output
                            + ";includes=example.threadapp.;serviceId=thread-service;instanceId=" + instance + ";revision=thread-revision",
                    "-cp", System.getProperty("java.class.path"), ThreadOwnershipServer.class.getName())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            String port = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (port == null && process.isAlive() && System.nanoTime() < deadline) {
                for (String line : Files.readAllLines(log)) if (line.startsWith("READY:")) port = line.substring(6).trim();
                if (port == null) Thread.sleep(25); // Readiness only; all scenarios use latches/barriers/join.
            }
            if (port == null) { process.destroyForcibly(); throw new AssertionError(Files.readString(log)); }
            root = URI.create("http://127.0.0.1:" + port + "/");
            System.out.println("Thread child JVM=" + process.pid() + " evidence=" + evidence.toAbsolutePath());
        }
        JsonNode get(String path, String owner) throws Exception {
            var request = HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(20));
            if (owner != null) request.header("baggage", "stp.test.suite.id=suite-" + owner + ",stp.test.id=test-" + owner + ",stp.request.id=" + owner);
            var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path + ": " + response.body());
            return JSON.readTree(response.body());
        }
        void assertFragment(Map<String, Set<String>> expected) throws Exception {
            JsonNode json = JSON.readTree(output.toFile());
            assertEquals(2, json.path("schemaVersion").asInt());
            assertEquals("thread-service", json.path("source").path("serviceId").asText());
            assertEquals(instance, json.path("source").path("instanceId").asText());
            assertEquals("thread-revision", json.path("source").path("revision").asText());
            Map<String, Set<String>> actual = new TreeMap<>();
            for (JsonNode request : json.path("requests")) {
                String id = request.path("requestId").asText();
                assertEquals("suite-" + id, request.path("testSuiteId").asText());
                assertEquals("test-" + id, request.path("testId").asText());
                Set<String> methods = new TreeSet<>();
                request.path("methods").forEach(m -> assertTrue(methods.add(m.asText())));
                assertNull(actual.put(id, methods));
            }
            assertEquals(expected, actual, "Exact positive and negative descriptor-aware relations");
        }
        @Override public void close() throws Exception {
            if (closed) return; closed = true; process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("thread fixture did not stop"); }
            assertTrue(Files.isRegularFile(output), output.toString());
        }
    }
}
