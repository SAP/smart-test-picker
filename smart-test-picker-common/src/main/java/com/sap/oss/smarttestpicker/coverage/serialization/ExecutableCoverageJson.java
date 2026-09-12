// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.MethodIdentity;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.SetupScopeType;
import com.sap.oss.smarttestpicker.coverage.model.TestContainer;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;

final class ExecutableCoverageJson
{
	private static final Gson GSON = new Gson();
	private ExecutableCoverageJson() {}

	static void rejectDuplicateTestProperties(String json)
	{
		try
		{
			JsonReader reader = new JsonReader(new StringReader(json)); reader.beginObject();
			while (reader.hasNext())
			{
				String name = reader.nextName();
				if (!"tests".equals(name)) { reader.skipValue(); continue; }
				Set<String> identities = new java.util.HashSet<>(); reader.beginObject();
				while (reader.hasNext())
				{
					String identity = reader.nextName();
					if (!identities.add(identity)) throw new IllegalArgumentException(
							"Duplicate executable test identity: " + identity);
					reader.skipValue();
				}
				reader.endObject();
			}
			reader.endObject();
		}
		catch (java.io.IOException e) { throw new IllegalArgumentException("Malformed executable artifact", e); }
	}

	static JsonObject tests(Map<ExecutableTestIdentity, TestCoverage> tests)
	{
		JsonObject result = new JsonObject();
		tests.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			JsonObject value = new JsonObject();
			value.add("classes", GSON.toJsonTree(entry.getValue().coveredClasses().stream().sorted().toList()));
			value.add("methods", GSON.toJsonTree(entry.getValue().coveredMethods().stream().sorted()
					.map(MethodIdentity::toString).toList()));
			value.addProperty("outcome", entry.getValue().outcome().name());
			value.addProperty("collectionStatus", entry.getValue().collectionStatus().name());
			result.add(entry.getKey().toString(), value);
		});
		return result;
	}

	static Map<ExecutableTestIdentity, TestCoverage> tests(JsonObject object)
	{
		Map<ExecutableTestIdentity, TestCoverage> result = new java.util.TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : object.entrySet())
		{
			ExecutableTestIdentity identity = ExecutableTestIdentity.parse(entry.getKey());
			JsonObject value = entry.getValue().getAsJsonObject();
			if (result.put(identity, new TestCoverage(strings(value, "classes"), methods(value, "methods"),
					TestOutcome.valueOf(required(value, "outcome")),
					CollectionStatus.valueOf(required(value, "collectionStatus")))) != null)
				throw new IllegalArgumentException("Duplicate executable test identity: " + identity);
		}
		return result;
	}

	static JsonArray unmapped(List<ExecutableUnmappedTest> values)
	{
		JsonArray result = new JsonArray(); values.stream().sorted((a, b) -> a.test().compareTo(b.test())).forEach(entry -> {
			JsonObject value = new JsonObject(); value.addProperty("test", entry.test().toString());
			value.addProperty("reason", entry.reason().name()); result.add(value);
		}); return result;
	}

	static List<ExecutableUnmappedTest> unmapped(JsonArray values)
	{
		List<ExecutableUnmappedTest> result = new ArrayList<>();
		for (JsonElement element : values)
		{
			JsonObject value = element.getAsJsonObject();
			result.add(new ExecutableUnmappedTest(ExecutableTestIdentity.parse(required(value, "test")),
					UnmappedReason.valueOf(required(value, "reason"))));
		}
		return result;
	}

	static JsonArray scopes(List<SetupScope> values)
	{
		JsonArray result = new JsonArray(); values.stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(scope -> {
			JsonObject value = new JsonObject(); value.addProperty("id", scope.id()); value.addProperty("type", scope.type().name());
			value.add("coveredClasses", GSON.toJsonTree(scope.coveredClasses().stream().sorted().toList()));
			value.add("affectedContainers", GSON.toJsonTree(scope.affectedContainers().stream()
					.map(TestContainer::binaryName).sorted().toList())); result.add(value);
		}); return result;
	}

	static List<SetupScope> scopes(JsonArray values)
	{
		List<SetupScope> result = new ArrayList<>();
		for (JsonElement element : values)
		{
			JsonObject value = element.getAsJsonObject(); Set<TestContainer> containers = new TreeSet<>();
			strings(value, "affectedContainers").forEach(name -> containers.add(new TestContainer(name)));
			result.add(new SetupScope(required(value, "id"), SetupScopeType.valueOf(required(value, "type")),
					strings(value, "coveredClasses"), containers));
		}
		return result;
	}

	static JsonArray identities(Set<ExecutableTestIdentity> values)
	{
		JsonArray result = new JsonArray(); values.stream().sorted().map(ExecutableTestIdentity::toString).forEach(result::add); return result;
	}
	static Set<ExecutableTestIdentity> identities(JsonObject object, String name)
	{
		TreeSet<ExecutableTestIdentity> result = new TreeSet<>();
		object.getAsJsonArray(name).forEach(value -> {
			ExecutableTestIdentity identity = ExecutableTestIdentity.parse(value.getAsString());
			if (!result.add(identity)) throw new IllegalArgumentException("Duplicate executable identity in " + name + ": " + identity);
		}); return result;
	}
	static JsonArray shards(Set<com.sap.oss.smarttestpicker.coverage.model.ShardId> values)
	{
		JsonArray result = new JsonArray(); values.stream().sorted().forEach(value -> result.add(value.value())); return result;
	}
	static Set<com.sap.oss.smarttestpicker.coverage.model.ShardId> shards(JsonObject object, String name)
	{
		TreeSet<com.sap.oss.smarttestpicker.coverage.model.ShardId> result = new TreeSet<>();
		object.getAsJsonArray(name).forEach(value -> result.add(new com.sap.oss.smarttestpicker.coverage.model.ShardId(value.getAsString()))); return result;
	}
	static String required(JsonObject object, String name)
	{
		if (!object.has(name) || object.get(name).isJsonNull()) throw new IllegalArgumentException(name + " is required");
		return object.get(name).getAsString();
	}
	private static Set<String> strings(JsonObject object, String name)
	{
		TreeSet<String> result = new TreeSet<>(); object.getAsJsonArray(name).forEach(value -> result.add(value.getAsString())); return result;
	}
	private static Set<MethodIdentity> methods(JsonObject object, String name)
	{
		TreeSet<MethodIdentity> result = new TreeSet<>(); object.getAsJsonArray(name).forEach(value -> {
			MethodIdentity method = MethodIdentity.parse(value.getAsString());
			if (!result.add(method)) throw new IllegalArgumentException("Duplicate covered method: " + method);
		}); return result;
	}
}
