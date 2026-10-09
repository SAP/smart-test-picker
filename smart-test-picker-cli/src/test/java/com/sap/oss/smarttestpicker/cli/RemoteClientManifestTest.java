// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class RemoteClientManifestTest {
    @TempDir Path temp;

    @Test void selectsExactKeysAndExcludesPreviousRunsWithoutMergingRetries() throws Exception {
        Path client = manifest(request("test", "retry-2"), request("test", "retry-3"));
        Path server = server("pod", "rev", remote("suite", "test", "retry-1", "old"),
                remote("suite", "test", "retry-2", "second"), remote("suite", "test", "retry-3", "third"),
                remote("other-suite", "test", "retry-2", "other-suite"), remote("suite", "other-test", "retry-3", "other-test"));
        var result = select(client, server);
        JsonArray observations = json(result.map()).getAsJsonArray("observations");
        assertEquals(2, observations.size());
        assertEquals("second", observations.get(0).getAsJsonObject().getAsJsonArray("methods").get(0).getAsString());
        assertEquals("third", observations.get(1).getAsJsonObject().getAsJsonArray("methods").get(0).getAsString());
        assertEquals(3, result.excluded());
        assertEquals(0, result.missing());
    }

    @Test void duplicateClientAndServerRecordsDeduplicateAndUnionMethods() throws Exception {
        Path client = manifest(request("test", "r"), request("test", "r"));
        Path server = server("pod", "rev", remote("suite", "test", "r", "a", "a"), remote("suite", "test", "r", "b"));
        var result = select(client, server, server);
        JsonArray observations = json(result.map()).getAsJsonArray("observations");
        assertEquals(1, observations.size());
        assertEquals("[\"a\",\"b\"]", observations.get(0).getAsJsonObject().get("methods").toString());
        assertEquals(1, json(result.report()).get("duplicateClientRecords").getAsInt());
        assertEquals(1, result.matched());
    }

    @Test void preservesSourcesAndRevisionsWhileCountingMatchedRequestsOnce() throws Exception {
        Path client = manifest(request("test", "r"));
        var result = select(client, server("pod1", "rev1", remote("suite", "test", "r", "a")),
                server("pod2", "rev1", remote("suite", "test", "r", "b")),
                server("pod1", "rev2", remote("suite", "test", "r", "c")));
        assertEquals(1, result.matched());
        assertEquals(3, json(result.map()).getAsJsonArray("observations").size());
        assertEquals(3, json(result.report()).getAsJsonArray("requests").get(0).getAsJsonObject().get("observationCount").getAsInt());
    }

    @Test void reportsMissingRequestsWithoutInventingObservationsAndSupportsStrictExit() throws Exception {
        Path client = manifest(request("test", "matched"), request("test", "external"));
        Path server = server("pod", "rev", remote("suite", "test", "matched", "a"));
        Path output = temp.resolve("map.json"), report = temp.resolve("report.json");
        assertEquals(2, cli(client, output, server, "--report", report.toString(), "--require-all-matched"));
        assertEquals(1, read(output).getAsJsonArray("observations").size());
        assertEquals(1, read(report).get("unmatchedClientRequests").getAsInt());
        JsonObject missing = read(report).getAsJsonArray("requests").get(0).getAsJsonObject();
        assertEquals("UNMATCHED", missing.get("status").getAsString());
        assertEquals("classpath:orders.feature", missing.get("featurePath").getAsString());
        assertEquals("run", read(report).getAsJsonObject("clientSource").get("runId").getAsString());
        assertEquals(0, cli(client, output, server));
    }

    @Test void rejectsConflictingDuplicateMetadataOrRequestOwnership() throws Exception {
        JsonObject changed = request("test", "r"); changed.addProperty("httpMethod", "POST");
        Path server = server("pod", "rev", remote("suite", "test", "r", "a"));
        for (Path client : List.of(manifest(request("test", "r"), changed), manifest(request("test", "r"), request("another-test", "r")))) {
            var error = assertThrows(java.io.IOException.class, () -> select(client, server));
            assertTrue(error.getMessage().contains(client.toString()));
            assertTrue(error.getMessage().contains("conflicting"));
        }
    }

    @Test void rejectsInvalidManifestWithoutWritingMap() throws Exception {
        Path server = server("pod", "rev", remote("suite", "test", "r", "a"));
        Path output = temp.resolve("untouched.json"); Files.writeString(output, "original");
        Path valid = manifest(request("test", "r"));
        for (String content : List.of("{broken", "{}", "[]", Files.readString(valid).replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                Files.readString(valid).replace("\"runId\":\"run\"", "\"runId\":\" \""),
                Files.readString(valid).replace("\"requestId\":\"r\"", "\"requestId\":null"))) {
            Path bad = file(content);
            assertEquals(1, cli(bad, output, server));
            assertEquals("original", Files.readString(output));
        }
        assertEquals(1, cli(temp.resolve("absent.json"), output, server));
    }

    @Test void validatesEvenExcludedRemoteRecords() throws Exception {
        Path client = manifest(request("test", "r"));
        JsonObject invalid = remote("suite", "test", "old", "a"); invalid.remove("requestId");
        Path server = server("pod", "rev", invalid);
        assertEquals(1, cli(client, temp.resolve("map.json"), server));
    }

    @Test void orderingAndDuplicateInputsDoNotChangeMapOrReport() throws Exception {
        JsonObject a = request("test", "a"), b = request("test", "b");
        Path firstClient = manifest(a, b), secondClient = manifest(b, a);
        Path x = server("x", "rev", remote("suite", "test", "a", "z", "a"));
        Path y = server("y", "rev", remote("suite", "test", "b", "b"));
        var first = select(firstClient, x, y); var second = select(secondClient, y, x, x);
        assertEquals(first.map(), second.map()); assertEquals(first.report(), second.report());
    }

    @Test void emptyManifestSelectsNothingAndStrictCompleteJoinSucceeds() throws Exception {
        Path client = manifest();
        Path server = server("pod", "rev", remote("suite", "test", "old", "a"));
        var result = select(client, server);
        assertEquals(0, result.matched()); assertEquals(0, result.missing()); assertEquals(1, result.excluded());
        assertEquals(0, cli(manifest(request("test", "old")), temp.resolve("complete.json"), server, "--require-all-matched"));
    }

    @Test void protectsInputsFromOutputAndReportAliases() throws Exception {
        Path client = manifest(request("test", "r"));
        Path server = server("pod", "rev", remote("suite", "test", "r", "a"));
        String original = Files.readString(client);
        assertEquals(1, cli(client, client, server));
        assertEquals(1, cli(client, temp.resolve("map.json"), server, "--report", client.toString()));
        Path alias = temp.resolve("alias.json"); Files.createLink(alias, client);
        assertEquals(1, cli(client, alias, server));
        assertEquals(original, Files.readString(client));
        assertEquals(1, cli(client, temp.resolve("map.json"), server, "--report", temp.resolve("map.json").toString()));
    }

    @Test void requiresManifestForCorrelationOptions() throws Exception {
        Path server = server("pod", "rev", remote("suite", "test", "r", "a"));
        for (String option : List.of("--require-all-matched", "--report")) {
            var args = new java.util.ArrayList<>(List.of("--input", server.toString(), "--output", temp.resolve("map.json").toString(), option));
            if (option.equals("--report")) args.add(temp.resolve("report.json").toString());
            assertEquals(1, new CommandLine(new JoinRemoteCommand()).execute(args.toArray(String[]::new)));
        }
    }

    private RemoteClientManifest.Selection select(Path client, Path... servers) throws Exception {
        return RemoteClientManifest.select(client, RemoteFragmentJoiner.join(List.of(servers)));
    }
    private int cli(Path client, Path output, Path server, String... extra) {
        var args = new java.util.ArrayList<>(List.of("--input", server.toString(), "--client-manifest", client.toString(), "--output", output.toString()));
        args.addAll(List.of(extra));
        return new CommandLine(new JoinRemoteCommand()).execute(args.toArray(String[]::new));
    }
    private Path file(String content) throws Exception {
        Path p = Files.createTempFile(temp, "input-", ".json"); Files.writeString(p, content); return p;
    }
    private Path manifest(JsonObject... requests) throws Exception {
        JsonObject root = json("{\"schemaVersion\":1,\"source\":{\"suiteId\":\"suite\",\"runId\":\"run\"}}");
        JsonArray rows = new JsonArray(); for (var r : requests) rows.add(r); root.add("requests", rows); return file(root.toString());
    }
    private JsonObject request(String test, String request) {
        JsonObject r = json("{\"featurePath\":\"classpath:orders.feature\",\"sectionIndex\":0,\"scenarioLine\":12,\"exampleIndex\":-1,\"httpMethod\":\"GET\",\"requestUri\":\"https://example.test/orders\"}");
        r.addProperty("testId", test); r.addProperty("requestId", request); return r;
    }
    private JsonObject remote(String suite, String test, String request, String... methods) {
        JsonObject r = new JsonObject(); r.addProperty("testSuiteId", suite); r.addProperty("testId", test); r.addProperty("requestId", request);
        JsonArray m = new JsonArray(); for (String method : methods) m.add(method); r.add("methods", m); return r;
    }
    private Path server(String pod, String revision, JsonObject... requests) throws Exception {
        JsonObject root = json("{\"schemaVersion\":2,\"source\":{\"serviceId\":\"commerce\"}}");
        root.getAsJsonObject("source").addProperty("instanceId", pod); root.getAsJsonObject("source").addProperty("revision", revision);
        JsonArray rows = new JsonArray(); for (var r : requests) rows.add(r); root.add("requests", rows); return file(root.toString());
    }
    private JsonObject read(Path path) throws Exception { return json(Files.readString(path)); }
    private JsonObject json(String content) { return JsonParser.parseString(content).getAsJsonObject(); }
}
