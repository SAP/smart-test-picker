// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Selects exact client request identities from an already validated, deduplicated remote join. */
final class RemoteClientManifest {
    private RemoteClientManifest() { }

    record Selection(String map, String report, int matched, int missing, int excluded) { }

    static Selection select(Path manifest, String joined) throws IOException {
        try {
            return selectRoot(JsonParser.parseString(Files.readString(manifest)).getAsJsonObject(), joined);
        } catch (IOException | RuntimeException failure) {
            throw new IOException("invalid client manifest " + manifest + ": " + failure.getMessage(), failure);
        }
    }

    private static Selection selectRoot(JsonObject client, String joined) {
        JsonElement version = client.get("schemaVersion");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || new BigDecimal(version.getAsString()).compareTo(BigDecimal.ONE) != 0)
            throw new IllegalArgumentException("unsupported or missing schemaVersion (expected 1)");
        JsonObject source = client.getAsJsonObject("source");
        String suite = text(source, "suiteId", 256);
        String run = text(source, "runId", 256);
        Map<Key, JsonObject> requests = new TreeMap<>(Key.ORDER);
        Map<String, Key> requestOwners = new TreeMap<>();
        JsonArray rows = client.getAsJsonArray("requests");
        if (rows == null) throw new IllegalArgumentException("missing requests array");
        for (JsonElement element : rows) {
            JsonObject request = element.getAsJsonObject();
            Key key = new Key(suite, text(request, "testId", 256), text(request, "requestId", 256));
            JsonObject metadata = new JsonObject();
            metadata.addProperty("testSuiteId", suite);
            metadata.addProperty("testId", key.test());
            metadata.addProperty("requestId", key.request());
            for (String field : new String[]{"featurePath", "httpMethod", "requestUri"})
                metadata.addProperty(field, text(request, field, Integer.MAX_VALUE));
            for (String field : new String[]{"sectionIndex", "scenarioLine", "exampleIndex"}) {
                JsonElement value = request.get(field);
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("missing or invalid " + field);
                int number = new BigDecimal(value.getAsString()).intValueExact();
                if (number < (field.equals("exampleIndex") ? -1 : 0))
                    throw new IllegalArgumentException("invalid " + field);
                metadata.addProperty(field, number);
            }
            if (request.has("scenarioName")) metadata.addProperty("scenarioName", text(request, "scenarioName", Integer.MAX_VALUE));
            Key owner = requestOwners.putIfAbsent(key.request(), key);
            if (owner != null && !owner.equals(key))
                throw new IllegalArgumentException("conflicting TestID for RequestID " + key.request());
            JsonObject previous = requests.putIfAbsent(key, metadata);
            if (previous != null && !previous.equals(metadata))
                throw new IllegalArgumentException("conflicting metadata for RequestID " + key.request());
        }
        JsonObject map = JsonParser.parseString(joined).getAsJsonObject();
        JsonArray selected = new JsonArray();
        Map<Key, Integer> matches = new TreeMap<>(Key.ORDER);
        TreeSet<Key> excluded = new TreeSet<>(Key.ORDER);
        for (JsonElement element : map.getAsJsonArray("observations")) {
            JsonObject row = element.getAsJsonObject();
            Key key = new Key(row.get("testSuiteId").getAsString(), row.get("testId").getAsString(), row.get("requestId").getAsString());
            if (requests.containsKey(key)) {
                selected.add(row);
                matches.merge(key, 1, Integer::sum);
            } else excluded.add(key);
        }
        map.add("observations", selected);
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        JsonObject reportSource = new JsonObject();
        reportSource.addProperty("suiteId", suite);
        reportSource.addProperty("runId", run);
        report.add("clientSource", reportSource);
        report.addProperty("clientRequestRecords", rows.size());
        report.addProperty("uniqueClientRequests", requests.size());
        report.addProperty("duplicateClientRecords", rows.size() - requests.size());
        report.addProperty("matchedClientRequests", matches.size());
        report.addProperty("unmatchedClientRequests", requests.size() - matches.size());
        report.addProperty("excludedRemoteRequests", excluded.size());
        report.addProperty("selectedObservations", selected.size());
        JsonArray details = new JsonArray();
        requests.forEach((key, metadata) -> {
            metadata.addProperty("observationCount", matches.getOrDefault(key, 0));
            metadata.addProperty("status", matches.containsKey(key) ? "MATCHED" : "UNMATCHED");
            details.add(metadata);
        });
        report.add("requests", details);
        return new Selection(json(map), json(report), matches.size(), requests.size() - matches.size(), excluded.size());
    }

    private static String text(JsonObject object, String name, int maxLength) {
        JsonElement value = object == null ? null : object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("missing or invalid " + name);
        String text = value.getAsString();
        if (text.isBlank() || text.length() > maxLength || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid " + name);
        return text;
    }

    private static String json(JsonObject value) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value) + "\n";
    }

    private record Key(String suite, String test, String request) {
        static final Comparator<Key> ORDER = Comparator.comparing(Key::suite).thenComparing(Key::test).thenComparing(Key::request);
    }
}
