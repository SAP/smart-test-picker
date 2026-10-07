// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Validates and deterministically joins schema-v2 Remote STP fragments. */
final class RemoteFragmentJoiner {
	private RemoteFragmentJoiner() { }

	static String join(List<Path> files) throws IOException {
		if (files == null || files.isEmpty()) throw new IllegalArgumentException("at least one --input file is required");
		Map<ObservationKey, TreeSet<String>> joined = new TreeMap<>(ObservationKey.ORDER);
		for (Path file : files) {
			JsonObject root = readRoot(file);
			if (!root.has("schemaVersion") || !root.get("schemaVersion").isJsonPrimitive()
					|| !root.get("schemaVersion").getAsJsonPrimitive().isNumber()
					|| new java.math.BigDecimal(root.get("schemaVersion").getAsString()).compareTo(java.math.BigDecimal.valueOf(2)) != 0)
				throw invalid(file, "unsupported or missing schemaVersion (expected 2)");
			JsonObject source = object(file, root, "source");
			String serviceId = identity(file, source, "serviceId");
			String instanceId = identity(file, source, "instanceId");
			String revision = identity(file, source, "revision");
			JsonArray requests = array(file, root, "requests");
			for (JsonElement item : requests) {
				if (!item.isJsonObject()) throw invalid(file, "requests must contain objects");
				JsonObject request = item.getAsJsonObject();
				String suite = identity(file, request, "testSuiteId");
				String test = identity(file, request, "testId");
				String requestId = identity(file, request, "requestId");
				JsonArray methods = array(file, request, "methods");
				ObservationKey key = new ObservationKey(suite, test, requestId, serviceId, instanceId, revision);
				TreeSet<String> methodSet = joined.computeIfAbsent(key, ignored -> new TreeSet<>());
				for (JsonElement method : methods) {
					if (!method.isJsonPrimitive() || !method.getAsJsonPrimitive().isString()
							|| method.getAsString().isBlank() || containsControl(method.getAsString()))
						throw invalid(file, "request methods must be nonblank strings without control characters");
					methodSet.add(method.getAsString());
				}
			}
		}
		JsonObject output = new JsonObject();
		output.addProperty("schemaVersion", 1);
		JsonArray observations = new JsonArray();
		for (Map.Entry<ObservationKey, TreeSet<String>> entry : joined.entrySet()) {
			ObservationKey key = entry.getKey();
			JsonObject observation = new JsonObject();
			observation.addProperty("testSuiteId", key.testSuiteId());
			observation.addProperty("testId", key.testId());
			observation.addProperty("requestId", key.requestId());
			JsonObject source = new JsonObject();
			source.addProperty("serviceId", key.serviceId());
			source.addProperty("instanceId", key.instanceId());
			source.addProperty("revision", key.revision());
			observation.add("source", source);
			JsonArray methods = new JsonArray();
			entry.getValue().forEach(methods::add);
			observation.add("methods", methods);
			observations.add(observation);
		}
		output.add("observations", observations);
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(output) + System.lineSeparator();
	}

	private static JsonObject readRoot(Path file) throws IOException {
		try {
			if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw invalid(file, "input is not a readable regular file");
			JsonElement parsed = JsonParser.parseString(Files.readString(file));
			if (!parsed.isJsonObject()) throw invalid(file, "top-level JSON value must be an object");
			return parsed.getAsJsonObject();
		} catch (IOException failure) {
			throw new IOException("cannot read Remote STP fragment " + file + ": " + failure.getMessage(), failure);
		} catch (RuntimeException failure) {
			if (failure instanceof IllegalArgumentException && failure.getMessage() != null && failure.getMessage().contains(file.toString()))
				throw failure;
			throw invalid(file, "malformed JSON: " + failure.getMessage(), failure);
		}
	}

	private static JsonObject object(Path file, JsonObject parent, String name) {
		if (!parent.has(name) || !parent.get(name).isJsonObject()) throw invalid(file, "missing or invalid " + name + " object");
		return parent.getAsJsonObject(name);
	}

	private static JsonArray array(Path file, JsonObject parent, String name) {
		if (!parent.has(name) || !parent.get(name).isJsonArray()) throw invalid(file, "missing or invalid " + name + " array");
		return parent.getAsJsonArray(name);
	}

	private static String identity(Path file, JsonObject parent, String name) {
		if (!parent.has(name) || !parent.get(name).isJsonPrimitive() || !parent.get(name).getAsJsonPrimitive().isString())
			throw invalid(file, "missing or invalid " + name + " identity");
		String value = parent.get(name).getAsString();
		if (value.isBlank() || value.length() > 256 || containsControl(value))
			throw invalid(file, "invalid " + name + " identity (must be nonblank, at most 256 characters, and contain no ISO control characters)");
		return value;
	}

	private static boolean containsControl(String value) {
		return value.chars().anyMatch(Character::isISOControl);
	}

	private static IllegalArgumentException invalid(Path file, String reason) {
		return new IllegalArgumentException("invalid Remote STP fragment " + file + ": " + reason);
	}

	private static IllegalArgumentException invalid(Path file, String reason, Throwable cause) {
		return new IllegalArgumentException("invalid Remote STP fragment " + file + ": " + reason, cause);
	}

	private record ObservationKey(String testSuiteId, String testId, String requestId,
			String serviceId, String instanceId, String revision) {
		private static final Comparator<ObservationKey> ORDER = Comparator.comparing(ObservationKey::testSuiteId)
				.thenComparing(ObservationKey::testId).thenComparing(ObservationKey::requestId)
				.thenComparing(ObservationKey::serviceId).thenComparing(ObservationKey::instanceId)
				.thenComparing(ObservationKey::revision);
	}
}
