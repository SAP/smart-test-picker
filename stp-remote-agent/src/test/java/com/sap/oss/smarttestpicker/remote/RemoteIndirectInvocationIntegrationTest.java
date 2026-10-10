// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import com.fasterxml.jackson.databind.*;
import example.indirectsupport.IndirectOwnershipServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact method relations, not merely span assertions, in real OTel + STP child JVMs. */
class RemoteIndirectInvocationIntegrationTest {
    static final List<String> ROUTES = List.of("reflection", "invoke", "exact", "arguments", "bound-invoke", "bound-exact", "bound-arguments");
    static final List<String> SHAPES = List.of("execute", "customExecute", "submitRunnable", "submitResult", "submitCallable",
            "all", "allTimed", "any", "anyTimed", "scheduleRunnable", "scheduleCallable", "fixedRate", "fixedDelay",
            "apply", "applyAsync", "combine", "start");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String CALL = "example.indirect.IncludedCalls#invoke(Ljava/lang/String;Ljava/lang/String;Ljava/lang/reflect/Method;Ljava/lang/invoke/MethodHandle;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
    static final String CREATE = "example.indirect.IndirectMethods#thread(Ljava/lang/Runnable;)Ljava/lang/Thread;";
    static String leaf(String name) { return "example.indirect.IndirectMethods#" + name + "()V"; }
    static boolean bulk(String shape) { return shape.startsWith("all") || shape.startsWith("any"); }
    static boolean cf(String shape) { return Set.of("apply", "applyAsync", "combine").contains(shape); }
    static boolean lost(String shape) { return shape.startsWith("fixed") || shape.equals("start"); }

    @Test void measuresOtelOnlyIndirectBoundaries() throws Exception { matrix(true); }
    @Test void indirectBoundariesWithRemoteAdapters() throws Exception { matrix(false); }

    @Test void concurrentOwnershipFailuresCancellationAndInvocationSemantics() throws Exception {
        Map<String, Set<String>> expected = new TreeMap<>();
        try (ServerRun server = new ServerRun(false)) {
            for (String scope : List.of("included", "outside")) for (String route : ROUTES) {
                for (String shape : List.of("customExecute", "submitCallable", "allTimed", "applyAsync", "combine")) {
                    String prefix = "parallel-" + scope + "-" + route + "-" + shape;
                    List<CompletableFuture<HttpResponse<String>>> submissions = new ArrayList<>();
                    for (String side : List.of("a", "b")) {
                        String key = prefix + side;
                        server.get("prepare?key=" + key + "&scope=" + scope + "&route=" + route + "&shape=" + shape
                                + "&leaf=" + side + "&barrier=" + prefix, key + "-creator");
                        submissions.add(server.async("submit?key=" + key, key));
                        if (scope.equals("included")) expect(expected, key, CALL);
                    }
                    if (!bulk(shape)) for (var submission : submissions) server.response(submission.get(20, TimeUnit.SECONDS));
                    var releaseA = server.async("release?key=" + prefix + "a", prefix + "a-completion");
                    var releaseB = server.async("release?key=" + prefix + "b", prefix + "b-completion");
                    JsonNode[] completions = {server.response(releaseA.get(20, TimeUnit.SECONDS)), server.response(releaseB.get(20, TimeUnit.SECONDS))};
                    if (bulk(shape)) for (var submission : submissions) server.response(submission.get(20, TimeUnit.SECONDS));
                    Set<Long> threads = new HashSet<>();
                    for (int i = 0; i < 2; i++) {
                        String side = i == 0 ? "a" : "b", key = prefix + side;
                        JsonNode result = server.get("result?key=" + key, null);
                        assertFalse(result.has("failure"), key + result);
                        assertEquals(1, result.path("executions").size(), key);
                        boolean gap = cf(shape) && !(scope.equals("included") && route.equals("reflection"));
                        JsonNode context = gap ? completions[i].path("completion") : result.path("owner");
                        assertContext(context, result.path("executions").get(0), key);
                        assertTrue(threads.add(result.path("executions").get(0).path("thread").asLong()), "barrier proves two simultaneous callback threads");
                        expect(expected, key + "-completion", leaf("after"));
                        expect(expected, gap ? key + "-completion" : key, leaf(side));
                    }
                }
                for (String shape : List.of("submitCallable", "all", "any", "scheduleCallable", "apply", "applyAsync", "combine", "start")) {
                    String key = "failure-" + scope + "-" + route + "-" + shape;
                    server.get("prepare?key=" + key + "&scope=" + scope + "&route=" + route + "&shape=" + shape + "&fail=true", key + "-creator");
                    if (shape.equals("start")) expect(expected, key + "-creator", CREATE);
                    var submit = server.async("submit?key=" + key, key);
                    if (!bulk(shape)) server.response(submit.get(20, TimeUnit.SECONDS));
                    JsonNode complete = server.get("release?key=" + key, key + "-completion");
                    JsonNode submitted = server.response(submit.get(20, TimeUnit.SECONDS));
                    JsonNode result = server.get("result?key=" + key, null);
                    if (scope.equals("included")) expect(expected, key, CALL);
                    expect(expected, key + "-completion", leaf("after"));
                    boolean gap = cf(shape) && !(scope.equals("included") && route.equals("reflection"));
                    if (!shape.equals("start")) {
                        expect(expected, gap ? key + "-completion" : key, leaf("a"));
                        assertContext(gap ? complete.path("completion") : submitted.path("owner"), result.path("executions").get(0), key);
                        assertEquals("java.lang.IllegalStateException", result.path("rootFailure").asText(), key + result);
                        if (shape.equals("any")) {
                            assertEquals(route.equals("reflection") ? "java.lang.reflect.InvocationTargetException" : "java.util.concurrent.ExecutionException", result.path("failure").asText(), key);
                        } else assertEquals("java.util.concurrent.ExecutionException", result.path("failure").asText(), key);
                    } else {
                        assertEquals("java.lang.IllegalStateException", result.path("uncaught").asText());
                        assertEquals("null", result.path("uncaughtIdentity").asText());
                        JsonNode restart = server.get("restart?key=" + key, key);
                        assertEquals(route.equals("reflection") ? "java.lang.reflect.InvocationTargetException" : "java.lang.IllegalThreadStateException", restart.path("failure").asText(), key);
                        assertEquals("java.lang.IllegalThreadStateException", restart.path("rootFailure").asText());
                    }
                    assertClean(server, key);
                }
                for (String shape : List.of("submitCallable", "scheduleRunnable", "apply", "applyAsync", "combine")) {
                    String key = "cancel-" + scope + "-" + route + "-" + shape;
                    if (!cf(shape)) server.get("block?key=" + key + "&pool=" + (shape.startsWith("schedule") ? "timer" : "worker"), key + "-blocker-B");
                    server.get("prepare?key=" + key + "&scope=" + scope + "&route=" + route + "&shape=" + shape, key + "-creator");
                    server.get("submit?key=" + key, key);
                    if (scope.equals("included")) expect(expected, key, CALL);
                    assertTrue(server.get("cancel?key=" + key, null).path("cancelled").asBoolean());
                    if (!cf(shape)) server.get("unblock?key=" + key, null);
                    server.get("release?key=" + key, key + "-completion");
                    expect(expected, key + "-completion", leaf("after"));
                    JsonNode result = server.get("result?key=" + key, null);
                    assertEquals("java.util.concurrent.CancellationException", result.path("failure").asText());
                    assertEquals(0, result.path("executions").size(), "cancel before callback starts: no manufactured coverage");
                    assertClean(server, key);
                }
                // Invalid invocation is allowed to fail exactly at reflection/handle argument checking.
                String key = "invalid-" + scope + "-" + route;
                server.get("prepare?key=" + key + "&scope=" + scope + "&route=" + route + "&shape=apply", key);
                JsonNode invalid = server.get("invalid?key=" + key, key);
                assertEquals(route.equals("reflection") ? "java.lang.IllegalArgumentException" : "java.lang.ClassCastException", invalid.path("failure").asText());
                assertEquals("java.lang.invoke.WrongMethodTypeException", server.get("wrongType?key=" + key, key).path("failure").asText());
                if (scope.equals("included")) expect(expected, key, CALL);
                assertClean(server, key);
            }
            sharedFuture(server, expected);
            server.close(); assertFragment(server, expected);
        }
    }

    private static void sharedFuture(ServerRun server, Map<String, Set<String>> expected) throws Exception {
        for (String route : ROUTES) {
            String source = "shared-" + route;
            for (String side : List.of("a", "b")) {
                String key = source + side;
                server.get("prepare?key=" + key + "&scope=included&route=" + route + "&shape=applyAsync&source=" + source + "&leaf=" + side, key + "-creator");
                server.get("submit?key=" + key, key);
                expect(expected, key, CALL);
            }
            // Both callbacks share one predecessor but have independent registration ownership.
            server.get("release?key=" + source + "a", source + "-C");
            server.get("release?key=" + source + "b", source + "-C");
            expect(expected, source + "-C", leaf("after"));
            for (String side : List.of("a", "b")) {
                String key = source + side;
                JsonNode result = server.get("result?key=" + key, null);
                String owner = route.equals("reflection") ? key : source + "-C";
                assertEquals("RemoteRequestIdentity[testSuiteId=suite-" + owner + ", testId=test-" + owner + ", requestId=" + owner + "]",
                        result.path("executions").get(0).path("identity").asText());
                expect(expected, owner, leaf(side));
            }
        }
    }

    private static void assertContext(JsonNode expected, JsonNode actual, String message) {
        for (String field : List.of("identity", "extra", "trace")) assertEquals(expected.path(field), actual.path(field), message + " " + field);
    }
    private static void assertClean(ServerRun server, String key) throws Exception {
        JsonNode probe = server.get("probe", null);
        for (String field : List.of("worker", "workerAfter", "timer", "timerAfter")) {
            assertEquals("null", probe.path(field).path("identity").asText(), key + " " + field);
            assertTrue(probe.path(field).path("extra").isNull(), key + " " + field);
        }
    }

    private void matrix(boolean otelOnly) throws Exception {
        Map<String, Set<String>> expected = new TreeMap<>();
        List<String> deviations = new ArrayList<>();
        try (ServerRun server = new ServerRun(otelOnly)) {
            Long worker = null;
            for (String scope : List.of("included", "outside")) for (String route : ROUTES) for (String shape : SHAPES) {
                for (String kind : cf(shape) || shape.equals("start") ? List.of("named") : List.of("named", "lambda")) {
                for (boolean headerless : List.of(false, true)) {
                    String key = scope + "-" + route + "-" + shape + "-" + kind + (headerless ? "-headerless" : "");
                    String creator = key + "-creator", owner = headerless ? null : key, completer = key + "-B";
                    JsonNode prepared = server.get("prepare?key=" + key + "&scope=" + scope + "&route=" + route + "&shape=" + shape + "&kind=" + kind, creator);
                    if (shape.equals("start")) expect(expected, creator, CREATE);
                    JsonNode submitted;
                    JsonNode released;
                    if (bulk(shape)) {
                        var submission = server.async("submit?key=" + key, owner);
                        released = server.get("release?key=" + key, completer);
                        submitted = server.response(submission.get(20, TimeUnit.SECONDS));
                    } else {
                        submitted = server.get("submit?key=" + key, owner);
                        // The submit HTTP response has ended before callbacks are released/completed.
                        released = server.get("release?key=" + key, completer);
                    }
                    if (scope.equals("included")) expect(expected, owner, CALL);
                    expect(expected, completer, leaf("after"));
                    assertEquals(prepared.path("handleType"), submitted.path("handleType"), key + " handle type unchanged");
                    assertFalse(submitted.has("failure"), key + submitted);
                    JsonNode result = server.get("result?key=" + key, null);
                    assertFalse(result.has("failure"), key + result);
                    int count = shape.startsWith("fixed") ? 2 : 1;
                    assertEquals(count, result.path("executions").size(), key + result);
                    boolean adapted = !otelOnly && scope.equals("included") && route.equals("reflection")
                            && (cf(shape) || shape.startsWith("fixed"));
                    boolean completionOwned = cf(shape) && !adapted;
                    boolean missing = lost(shape) && !adapted;
                    String callbackOwner = completionOwned ? completer : missing ? null : owner;
                    expect(expected, callbackOwner, leaf("a"));
                    for (JsonNode execution : result.path("executions")) {
                        JsonNode context = completionOwned ? released.path("completion") : submitted.path("owner");
                        String identity = missing ? "null" : context.path("identity").asText();
                        String extra = missing ? null : context.path("extra").asText(null);
                        if (!identity.equals(execution.path("identity").asText()) || !Objects.equals(extra, execution.path("extra").asText(null)))
                            deviations.add(key + " expected=" + identity + "/" + extra + " actual=" + execution);
                        if (!missing) assertEquals(context.path("trace"), execution.path("trace"), key + " full trace Context");
                        if (!cf(shape) || shape.equals("applyAsync")) assertNotEquals(submitted.path("owner").path("thread"), execution.path("thread"), key);
                        if (shape.equals("submitCallable")) {
                            long id = execution.path("thread").asLong();
                            if (worker == null) worker = id; else assertEquals(worker.longValue(), id, "physical worker reused");
                        }
                    }
                    switch (shape) {
                        case "submitCallable", "scheduleCallable", "any", "anyTimed" -> assertEquals("result", result.path("value").asText(), key);
                        case "submitResult" -> assertEquals("supplied-result", result.path("value").asText(), key);
                        case "all", "allTimed" -> assertEquals(JSON.readTree("[\"result\"]"), result.path("value"), key);
                        case "apply", "applyAsync" -> assertEquals("left-result", result.path("value").asText(), key);
                        case "combine" -> assertEquals("left:right-result", result.path("value").asText(), key);
                        case "fixedRate", "fixedDelay" -> assertTrue(result.path("cancelled").asBoolean(), key);
                        default -> { }
                    }
                    JsonNode probe = server.get("probe", null);
                    for (String field : List.of("worker", "workerAfter", "timer", "timerAfter")) {
                        assertEquals("null", probe.path(field).path("identity").asText(), key + " " + field);
                        assertTrue(probe.path(field).path("extra").isNull(), key + " " + field);
                    }
                }
                }
            }
            assertTrue(deviations.isEmpty(), String.join("\n", deviations));
            server.close(); assertFragment(server, expected);
        }
    }
    static void expect(Map<String, Set<String>> expected, String owner, String... methods) {
        if (owner != null) expected.computeIfAbsent(owner, ignored -> new TreeSet<>()).addAll(List.of(methods));
    }
    static void assertFragment(ServerRun server, Map<String, Set<String>> expected) throws Exception {
        JsonNode json = JSON.readTree(server.output.toFile());
        assertEquals(2, json.path("schemaVersion").asInt());
        assertEquals("indirect-service", json.path("source").path("serviceId").asText());
        assertEquals(server.instance, json.path("source").path("instanceId").asText());
        assertEquals("indirect-revision", json.path("source").path("revision").asText());
        Map<String, Set<String>> actual = new TreeMap<>();
        for (JsonNode request : json.path("requests")) {
            String id = request.path("requestId").asText();
            assertEquals("suite-" + id, request.path("testSuiteId").asText());
            assertEquals("test-" + id, request.path("testId").asText());
            Set<String> methods = new TreeSet<>(); request.path("methods").forEach(m -> assertTrue(methods.add(m.asText())));
            assertNull(actual.put(id, methods));
        }
        assertEquals(expected, actual, "Exact positive AND negative descriptor-aware method relations");
    }
    static final class ServerRun implements AutoCloseable {
        final String instance = "indirect-jvm-" + UUID.randomUUID();
        final Path output;
        final Process process;
        final URI root;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        boolean closed;
        ServerRun(boolean otelOnly) throws Exception {
            Path evidence = Files.createTempDirectory(Path.of("build"), otelOnly ? "indirect-otel-only-" : "indirect-adapters-");
            output = evidence.resolve("observations.json").toAbsolutePath(); Path log = evidence.resolve("server.log");
            process = new ProcessBuilder(System.getenv().getOrDefault("STP_INDIRECT_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                    "-Dotel.traces.exporter=none", "-Dotel.metrics.exporter=none", "-Dotel.logs.exporter=none",
                    "-Dstp.remote.otel.contextOnly=" + otelOnly,
                    "-javaagent:" + System.getProperty("stp.otel.agent.jar"),
                    "-javaagent:" + System.getProperty("stp.remote.agent.jar") + "=output=" + output
                            + ";includes=example.indirect.;serviceId=indirect-service;instanceId=" + instance + ";revision=indirect-revision",
                    "-cp", System.getProperty("java.class.path"), IndirectOwnershipServer.class.getName())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            String port = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (port == null && process.isAlive() && System.nanoTime() < deadline) {
                for (String line : Files.readAllLines(log)) if (line.startsWith("READY:")) port = line.substring(6).trim();
                if (port == null) Thread.sleep(25); // Readiness only; all scenario coordination uses gates/barriers.
            }
            if (port == null) { process.destroyForcibly(); throw new AssertionError(Files.readString(log)); }
            root = URI.create("http://127.0.0.1:" + port + "/");
            System.out.println("Indirect child JVM=" + process.pid() + " evidence=" + evidence.toAbsolutePath());
        }
        CompletableFuture<HttpResponse<String>> async(String path, String owner) {
            var request = HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(25));
            if (owner != null) request.header("baggage", "stp.test.suite.id=suite-" + owner + ",stp.test.id=test-" + owner + ",stp.request.id=" + owner);
            return client.sendAsync(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode response(HttpResponse<String> response) throws Exception {
            assertEquals(200, response.statusCode(), response.uri() + ": " + response.body());
            return JSON.readTree(response.body());
        }
        JsonNode get(String path, String owner) throws Exception { return response(async(path, owner).get(30, TimeUnit.SECONDS)); }
        @Override public void close() throws Exception {
            if (closed) return; closed = true; process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Indirect fixture did not finalize " + output); }
            assertTrue(Files.isRegularFile(output), "missing final fragment " + output);
        }
    }
}
