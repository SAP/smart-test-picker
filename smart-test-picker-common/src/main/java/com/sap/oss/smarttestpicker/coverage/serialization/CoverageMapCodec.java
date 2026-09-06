// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.Completeness;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.MethodCoverageReference;
import com.sap.oss.smarttestpicker.coverage.model.MethodIdentity;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
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
import com.sap.oss.smarttestpicker.coverage.validation.ValidationResult;

/** Deterministic schema-v2 indexed serializer/deserializer. */
public final class CoverageMapCodec
{
	private static final Gson GSON = new Gson();

	public byte[] serialize(CoverageMap map)
	{
		JsonObject canonical = toJson(map);
		String checksum = checksum(bytes(canonical));
		canonical.addProperty("checksum", checksum);
		return bytes(canonical);
	}

	public CoverageMap deserialize(byte[] bytes)
	{
		JsonObject root;
		try { root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
		catch (RuntimeException e) { throw invalid(ValidationCategory.CORRUPT_MAP, ValidationCode.CHECKSUM_MISMATCH, "Malformed coverage-map JSON"); }

		int version = requiredInt(root, "schemaVersion");
		if (version != CoverageMapContract.SCHEMA_VERSION)
			throw invalid(ValidationCategory.INCOMPATIBLE_SCHEMA,
					version > CoverageMapContract.SCHEMA_VERSION ? ValidationCode.HIGHER_SCHEMA_VERSION : ValidationCode.SCHEMA_VERSION_MISMATCH,
					"Unsupported coverage-map schema version: " + version);

		String suppliedChecksum = requiredString(root, "checksum");
		JsonObject withoutChecksum = root.deepCopy(); withoutChecksum.remove("checksum");
		String actualChecksum = checksum(bytes(withoutChecksum));
		if (!actualChecksum.equals(suppliedChecksum))
			throw invalid(ValidationCategory.CORRUPT_MAP, ValidationCode.CHECKSUM_MISMATCH,
					"Coverage-map checksum mismatch");

		try
		{
			CoverageMap map = fromJson(root);
			ValidationResult validation = CoverageMapValidator.validate(map);
			validation.errors().stream()
					.filter(error -> error.code() != ValidationCode.LIFECYCLE_NOT_PUBLISHED)
					.findFirst().ifPresent(error -> { throw new CoverageMapValidationException(error); });
			return map;
		}
		catch (CoverageMapValidationException e) { throw e; }
		catch (IllegalArgumentException | IndexOutOfBoundsException e)
		{
			throw invalid(ValidationCategory.INVALID_STRUCTURE, ValidationCode.MALFORMED_TEST_IDENTITY, e.getMessage());
		}
	}

	public ValidationResult validate(CoverageMap map) { return CoverageMapValidator.validate(map); }

	private JsonObject toJson(CoverageMap map)
	{
		TreeSet<String> classes = new TreeSet<>(); TreeSet<MethodIdentity> methods = new TreeSet<>();
		TreeSet<String> containers = new TreeSet<>(); TreeSet<TestIdentity> identities = new TreeSet<>();
		identities.addAll(map.tests().keySet());
		map.unmapped().forEach(entry -> identities.add(entry.test()));
		if (map.completeness() != null) { identities.addAll(map.completeness().expectedTests()); identities.addAll(map.completeness().reportedTests()); }
		map.tests().values().forEach(coverage -> { classes.addAll(coverage.coveredClasses()); methods.addAll(coverage.coveredMethods()); });
		map.setupScopes().forEach(scope -> { classes.addAll(scope.coveredClasses()); scope.affectedContainers().forEach(c -> containers.add(c.binaryName())); });

		List<String> classTable = List.copyOf(classes), containerTable = List.copyOf(containers);
		List<MethodIdentity> methodTable = List.copyOf(methods);
		List<TestIdentity> testTable = List.copyOf(identities);
		Map<String, Integer> classIds = ids(classTable), containerIds = ids(containerTable);
		Map<MethodIdentity, Integer> methodIds = ids(methodTable);
		Map<TestIdentity, Integer> testIds = new LinkedHashMap<>(); for (int i = 0; i < testTable.size(); i++) testIds.put(testTable.get(i), i);

		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", map.schemaVersion()); root.addProperty("revision", map.revision().value());
		root.addProperty("generatedAt", map.generatedAt().toString()); root.add("generator", GSON.toJsonTree(map.generator()));
		root.add("classIndex", strings(classTable)); root.add("methodIndex", strings(methodTable.stream().map(MethodIdentity::toString).toList()));
		root.add("containerIndex", strings(containerTable)); root.add("testIndex", strings(testTable.stream().map(TestIdentity::toString).toList()));

		JsonArray tests = new JsonArray();
		map.tests().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			JsonObject value = new JsonObject(); value.addProperty("test", testIds.get(entry.getKey()));
			value.add("classes", indexes(entry.getValue().coveredClasses(), classIds));
			value.add("methods", methodIndexes(entry.getValue().coveredMethods(), methodIds));
			value.addProperty("outcome", entry.getValue().outcome().name());
			value.addProperty("collectionStatus", entry.getValue().collectionStatus().name()); tests.add(value);
		}); root.add("tests", tests);

		JsonArray unmapped = new JsonArray();
		map.unmapped().stream().sorted((a, b) -> a.test().compareTo(b.test())).forEach(entry -> {
			JsonObject value = new JsonObject(); value.addProperty("test", testIds.get(entry.test())); value.addProperty("reason", entry.reason().name()); unmapped.add(value);
		}); root.add("unmapped", unmapped);

		JsonArray scopes = new JsonArray();
		map.setupScopes().stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(scope -> {
			JsonObject value = new JsonObject(); value.addProperty("id", scope.id()); value.addProperty("type", scope.type().name());
			value.add("coveredClasses", indexes(scope.coveredClasses(), classIds));
			value.add("affectedContainers", indexes(scope.affectedContainers().stream().map(TestContainer::binaryName).collect(java.util.stream.Collectors.toSet()), containerIds));
			scopes.add(value);
		}); root.add("setupScopes", scopes);

		if (map.completeness() == null) root.add("completeness", com.google.gson.JsonNull.INSTANCE);
		else root.add("completeness", completenessToJson(map.completeness(), testIds));
		root.add("statistics", GSON.toJsonTree(map.statistics())); root.addProperty("lifecycleState", map.lifecycleState().name());
		if (map.methodCoverageReference() != null) root.add("methodCoverage", GSON.toJsonTree(map.methodCoverageReference()));
		return root;
	}

	private CoverageMap fromJson(JsonObject root)
	{
		List<String> classes = stringList(root, "classIndex"), containers = stringList(root, "containerIndex");
		List<MethodIdentity> methods = stringList(root, "methodIndex").stream().map(MethodIdentity::parse).toList();
		List<TestIdentity> identities = stringList(root, "testIndex").stream().map(TestIdentity::parse).toList();
		Map<TestIdentity, TestCoverage> tests = new LinkedHashMap<>();
		for (JsonElement element : root.getAsJsonArray("tests"))
		{
			JsonObject value = element.getAsJsonObject(); TestIdentity identity = identities.get(requiredInt(value, "test"));
			if (tests.containsKey(identity)) throw invalid(ValidationCategory.INVALID_STRUCTURE, ValidationCode.DUPLICATE_TEST_IDENTITY, "Duplicate test: " + identity);
			Set<String> coveredClasses = resolve(value.getAsJsonArray("classes"), classes);
			Set<MethodIdentity> coveredMethods = resolveTyped(value.getAsJsonArray("methods"), methods);
			tests.put(identity, new TestCoverage(coveredClasses, coveredMethods,
					TestOutcome.valueOf(requiredString(value, "outcome")),
					CollectionStatus.valueOf(requiredString(value, "collectionStatus"))));
		}
		List<UnmappedTest> unmapped = new ArrayList<>();
		for (JsonElement element : root.getAsJsonArray("unmapped"))
		{
			JsonObject value = element.getAsJsonObject();
			if (!value.has("reason")) throw invalid(ValidationCategory.INVALID_STRUCTURE, ValidationCode.UNMAPPED_REASON_MISSING, "Unmapped reason is required");
			unmapped.add(new UnmappedTest(identities.get(requiredInt(value, "test")), UnmappedReason.valueOf(requiredString(value, "reason"))));
		}
		List<SetupScope> scopes = new ArrayList<>(); Set<String> scopeIds = new HashSet<>();
		for (JsonElement element : root.getAsJsonArray("setupScopes"))
		{
			JsonObject value = element.getAsJsonObject(); String id = requiredString(value, "id");
			if (!scopeIds.add(id)) throw invalid(ValidationCategory.INVALID_STRUCTURE, ValidationCode.DUPLICATE_SETUP_SCOPE_ID, "Duplicate setup scope: " + id);
			Set<TestContainer> affected = new TreeSet<>(); for (String container : resolve(value.getAsJsonArray("affectedContainers"), containers)) affected.add(new TestContainer(container));
			if (affected.isEmpty()) throw invalid(ValidationCategory.INVALID_STRUCTURE, ValidationCode.EMPTY_AFFECTED_CONTAINERS, "Setup scope has no affected containers");
			scopes.add(new SetupScope(id, SetupScopeType.valueOf(requiredString(value, "type")), resolve(value.getAsJsonArray("coveredClasses"), classes), affected));
		}
		Completeness completeness = root.has("completeness") && !root.get("completeness").isJsonNull()
				? completenessFromJson(root.getAsJsonObject("completeness"), identities) : null;
		GeneratorProvenance generator = GSON.fromJson(root.get("generator"), GeneratorProvenance.class);
		MapStatistics statistics = GSON.fromJson(root.get("statistics"), MapStatistics.class);
		MethodCoverageReference reference = root.has("methodCoverage") ? GSON.fromJson(root.get("methodCoverage"), MethodCoverageReference.class) : null;
		return new CoverageMap(requiredInt(root, "schemaVersion"), new CoverageMapRevision(requiredString(root, "revision")),
				Instant.parse(requiredString(root, "generatedAt")), generator, tests, unmapped, scopes, completeness, statistics,
				CoverageMapLifecycleState.valueOf(requiredString(root, "lifecycleState")), reference);
	}

	private JsonObject completenessToJson(Completeness value, Map<TestIdentity, Integer> testIds)
	{
		JsonObject json = new JsonObject(); json.add("expectedTests", testIndexes(value.expectedTests(), testIds)); json.add("reportedTests", testIndexes(value.reportedTests(), testIds));
		json.add("expectedShards", strings(value.expectedShards().stream().map(ShardId::value).sorted().toList()));
		json.add("completedShards", strings(value.completedShards().stream().map(ShardId::value).sorted().toList()));
		json.add("missingTests", testIndexes(value.missingTests(), testIds)); json.add("unexpectedTests", testIndexes(value.unexpectedTests(), testIds));
		json.add("missingShards", strings(value.missingShards().stream().map(ShardId::value).sorted().toList()));
		json.add("duplicateShards", strings(value.duplicateShards().stream().map(ShardId::value).sorted().toList()));
		json.add("duplicateTests", testIndexes(value.duplicateTests(), testIds)); return json;
	}

	private Completeness completenessFromJson(JsonObject json, List<TestIdentity> identities)
	{
		return new Completeness(testSet(json, "expectedTests", identities), testSet(json, "reportedTests", identities),
				shards(json, "expectedShards"), shards(json, "completedShards"), testSet(json, "missingTests", identities),
				testSet(json, "unexpectedTests", identities), shards(json, "missingShards"), shards(json, "duplicateShards"),
				testSet(json, "duplicateTests", identities));
	}

	private static <T> Map<T, Integer> ids(List<T> values)
	{
		Map<T, Integer> result = new LinkedHashMap<>();
		for (int i = 0; i < values.size(); i++)
		{
			result.put(values.get(i), i);
		}
		return result;
	}
	private static JsonArray strings(List<String> values) { JsonArray result = new JsonArray(); values.forEach(result::add); return result; }
	private static JsonArray indexes(Set<String> values, Map<String, Integer> ids) { JsonArray result = new JsonArray(); values.stream().sorted().forEach(value -> result.add(ids.get(value))); return result; }
	private static JsonArray methodIndexes(Set<MethodIdentity> values, Map<MethodIdentity, Integer> ids) { JsonArray result = new JsonArray(); values.stream().sorted().forEach(value -> result.add(ids.get(value))); return result; }
	private static JsonArray testIndexes(Set<TestIdentity> values, Map<TestIdentity, Integer> ids) { JsonArray result = new JsonArray(); values.stream().sorted().forEach(value -> result.add(ids.get(value))); return result; }
	private static List<String> stringList(JsonObject object, String name) { List<String> result = new ArrayList<>(); object.getAsJsonArray(name).forEach(value -> result.add(value.getAsString())); return result; }
	private static Set<String> resolve(JsonArray indexes, List<String> table) { TreeSet<String> result = new TreeSet<>(); indexes.forEach(index -> result.add(table.get(index.getAsInt()))); return result; }
	private static <T extends Comparable<? super T>> Set<T> resolveTyped(JsonArray indexes, List<T> table) {
		TreeSet<T> result = new TreeSet<>();
		for (JsonElement index : indexes) {
			T value = table.get(index.getAsInt());
			if (!result.add(value)) throw invalid(ValidationCategory.INVALID_STRUCTURE,
					ValidationCode.DUPLICATE_METHOD_IDENTITY, "Duplicate covered method: " + value);
		}
		return result;
	}
	private static Set<TestIdentity> testSet(JsonObject object, String name, List<TestIdentity> table) { TreeSet<TestIdentity> result = new TreeSet<>(); object.getAsJsonArray(name).forEach(index -> result.add(table.get(index.getAsInt()))); return result; }
	private static Set<ShardId> shards(JsonObject object, String name) { TreeSet<ShardId> result = new TreeSet<>(); object.getAsJsonArray(name).forEach(value -> result.add(new ShardId(value.getAsString()))); return result; }
	private static int requiredInt(JsonObject object, String name) { return object.get(name).getAsInt(); }
	private static String requiredString(JsonObject object, String name) { if (!object.has(name) || object.get(name).isJsonNull()) throw new IllegalArgumentException(name + " is required"); return object.get(name).getAsString(); }
	private static byte[] bytes(JsonObject json) { return GSON.toJson(json).getBytes(StandardCharsets.UTF_8); }
	private static String checksum(byte[] bytes)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder result = new StringBuilder("sha256:");
			for (byte value : digest)
			{
				result.append(String.format("%02x", value));
			}
			return result.toString();
		}
		catch (Exception e) { throw new IllegalStateException(e); }
	}
	private static CoverageMapValidationException invalid(ValidationCategory category, ValidationCode code, String message) { return new CoverageMapValidationException(CoverageMapValidator.error(category, code, message)); }
}
