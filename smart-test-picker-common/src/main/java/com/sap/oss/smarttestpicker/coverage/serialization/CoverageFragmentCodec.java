// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;
import java.io.StringReader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.MethodIdentity;
import com.sap.oss.smarttestpicker.coverage.model.SetupScopeType;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestContainer;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidationException;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidator;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCategory;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCode;

/** PROVISIONAL schema-v2 fragment codec. Setup scopes use the same semantic shape as maps. */
public final class CoverageFragmentCodec
{
	private static final Gson GSON = new Gson();

	public byte[] serialize(CoverageFragment fragment)
	{
		var validation = CoverageMapValidator.validate(fragment);
		if (!validation.isValid()) throw new CoverageMapValidationException(validation.errors().get(0));
		JsonObject root = new JsonObject(); root.addProperty("schemaVersion", fragment.schemaVersion());
		root.addProperty("revision", fragment.revision().value()); root.addProperty("shardId", fragment.shardId().value());
		JsonObject tests = new JsonObject();
		new TreeMap<>(fragment.tests()).forEach((identity, coverage) -> {
			JsonObject value = new JsonObject(); value.add("classes", GSON.toJsonTree(coverage.coveredClasses().stream().sorted().toList()));
			value.add("methods", GSON.toJsonTree(coverage.coveredMethods().stream().sorted().map(MethodIdentity::toString).toList()));
			value.addProperty("outcome", coverage.outcome().name());
			value.addProperty("collectionStatus", coverage.collectionStatus().name()); tests.add(identity.toString(), value);
		}); root.add("tests", tests);
		JsonArray unmapped = new JsonArray(); fragment.unmapped().stream().sorted((a, b) -> a.test().compareTo(b.test())).forEach(entry -> { JsonObject value = new JsonObject(); value.addProperty("test", entry.test().toString()); value.addProperty("reason", entry.reason().name()); unmapped.add(value); }); root.add("unmapped", unmapped);
		JsonArray scopes = new JsonArray();
		fragment.setupScopes().stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(scope -> {
			JsonObject value = new JsonObject(); value.addProperty("id", scope.id()); value.addProperty("type", scope.type().name());
			value.add("coveredClasses", GSON.toJsonTree(scope.coveredClasses().stream().sorted().toList()));
			value.add("affectedContainers", GSON.toJsonTree(scope.affectedContainers().stream().map(TestContainer::binaryName).sorted().toList())); scopes.add(value);
		}); root.add("setupScopes", scopes);
		JsonObject collection = new JsonObject(); collection.addProperty("completed", fragment.collectionCompleted()); root.add("collection", collection);
		return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
	}

	public CoverageFragment deserialize(byte[] bytes)
	{
		String json = new String(bytes, StandardCharsets.UTF_8);
		rejectDuplicateTestProperties(json);
		JsonObject root = JsonParser.parseString(json).getAsJsonObject(); int version = root.get("schemaVersion").getAsInt();
		if (version != CoverageMapContract.SCHEMA_VERSION) throw new CoverageMapValidationException(CoverageMapValidator.error(ValidationCategory.INCOMPATIBLE_SCHEMA, version > CoverageMapContract.SCHEMA_VERSION ? ValidationCode.HIGHER_SCHEMA_VERSION : ValidationCode.SCHEMA_VERSION_MISMATCH, "Unsupported fragment schema version: " + version));
		Map<TestIdentity, TestCoverage> tests = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("tests").entrySet())
		{
			JsonObject value = entry.getValue().getAsJsonObject();
			tests.put(TestIdentity.parse(entry.getKey()), new TestCoverage(strings(value, "classes"), methods(value, "methods"),
					TestOutcome.valueOf(value.get("outcome").getAsString()),
					CollectionStatus.valueOf(value.get("collectionStatus").getAsString())));
		}
		List<UnmappedTest> unmapped = new ArrayList<>(); for (JsonElement element : root.getAsJsonArray("unmapped")) { JsonObject value = element.getAsJsonObject(); unmapped.add(new UnmappedTest(TestIdentity.parse(value.get("test").getAsString()), UnmappedReason.valueOf(value.get("reason").getAsString()))); }
		List<SetupScope> scopes = new ArrayList<>();
		for (JsonElement element : root.getAsJsonArray("setupScopes"))
		{
			JsonObject value = element.getAsJsonObject(); java.util.Set<TestContainer> containers = new java.util.TreeSet<>();
			strings(value, "affectedContainers").forEach(name -> containers.add(new TestContainer(name)));
			scopes.add(new SetupScope(value.get("id").getAsString(), SetupScopeType.valueOf(value.get("type").getAsString()), strings(value, "coveredClasses"), containers));
		}
		CoverageFragment fragment = new CoverageFragment(version, new CoverageMapRevision(root.get("revision").getAsString()), new ShardId(root.get("shardId").getAsString()), tests, unmapped, scopes, root.getAsJsonObject("collection").get("completed").getAsBoolean());
		var validation = CoverageMapValidator.validate(fragment);
		if (!validation.isValid()) throw new CoverageMapValidationException(validation.errors().get(0));
		return fragment;
	}

	private static void rejectDuplicateTestProperties(String json)
	{
		try
		{
			JsonReader reader = new JsonReader(new StringReader(json)); reader.beginObject();
			while (reader.hasNext())
			{
				String name = reader.nextName();
				if (!"tests".equals(name)) { reader.skipValue(); continue; }
				HashSet<String> identities = new HashSet<>(); reader.beginObject();
				while (reader.hasNext())
				{
					String identity = reader.nextName();
					if (!identities.add(identity)) throw new CoverageMapValidationException(CoverageMapValidator.error(
							ValidationCategory.INVALID_STRUCTURE, ValidationCode.DUPLICATE_TEST_IDENTITY,
							"Duplicate test identity: " + identity));
					reader.skipValue();
				}
				reader.endObject();
			}
			reader.endObject();
		}
		catch (CoverageMapValidationException failure) { throw failure; }
		catch (java.io.IOException malformed) { throw new IllegalArgumentException("Malformed coverage fragment", malformed); }
	}

	private static java.util.Set<String> strings(JsonObject object, String name) { java.util.TreeSet<String> result = new java.util.TreeSet<>(); object.getAsJsonArray(name).forEach(value -> result.add(value.getAsString())); return result; }
	private static java.util.Set<MethodIdentity> methods(JsonObject object, String name) {
		java.util.TreeSet<MethodIdentity> result = new java.util.TreeSet<>();
		object.getAsJsonArray(name).forEach(value -> {
			MethodIdentity identity = MethodIdentity.parse(value.getAsString());
			if (!result.add(identity)) throw new CoverageMapValidationException(CoverageMapValidator.error(
					ValidationCategory.INVALID_STRUCTURE, ValidationCode.DUPLICATE_METHOD_IDENTITY,
					"Duplicate covered method: " + identity));
		});
		return result;
	}
}
