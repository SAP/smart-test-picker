// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCompleteness;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.MethodCoverageReference;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidationException;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableCoverageMapValidator;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCategory;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCode;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationResult;

/** Deterministic strict schema-v3 coverage-map codec with executable identity keys. */
public final class ExecutableCoverageMapCodec
{
	private static final Gson GSON = new Gson();

	public byte[] serialize(ExecutableCoverageMap map)
	{
		JsonObject root = toJson(map); root.addProperty("checksum", checksum(bytes(root))); return bytes(root);
	}

	public ExecutableCoverageMap deserialize(byte[] bytes)
	{
		JsonObject root;
		try
		{
			String json = new String(bytes, StandardCharsets.UTF_8); ExecutableCoverageJson.rejectDuplicateTestProperties(json);
			root = JsonParser.parseString(json).getAsJsonObject();
		}
		catch (RuntimeException e) { throw invalid(ValidationCategory.CORRUPT_MAP, ValidationCode.CHECKSUM_MISMATCH, "Malformed executable coverage-map JSON"); }
		int version = root.get("schemaVersion").getAsInt();
		if (version != CoverageMapContract.SCHEMA_V3)
			throw invalid(ValidationCategory.INCOMPATIBLE_SCHEMA,
					version > CoverageMapContract.SCHEMA_V3 ? ValidationCode.HIGHER_SCHEMA_VERSION : ValidationCode.SCHEMA_VERSION_MISMATCH,
					"Unsupported executable coverage-map schema version: " + version);
		String supplied = ExecutableCoverageJson.required(root, "checksum");
		JsonObject canonical = root.deepCopy(); canonical.remove("checksum");
		if (!checksum(bytes(canonical)).equals(supplied))
			throw invalid(ValidationCategory.CORRUPT_MAP, ValidationCode.CHECKSUM_MISMATCH, "Executable coverage-map checksum mismatch");
		try
		{
			ExecutableCoverageMap map = fromJson(root); ValidationResult validation = validate(map);
			validation.errors().stream().filter(error -> error.code() != ValidationCode.LIFECYCLE_NOT_PUBLISHED)
					.findFirst().ifPresent(error -> { throw new CoverageMapValidationException(error); });
			return map;
		}
		catch (CoverageMapValidationException e) { throw e; }
		catch (RuntimeException e) { throw invalid(ValidationCategory.INVALID_STRUCTURE, ValidationCode.MALFORMED_TEST_IDENTITY, e.getMessage()); }
	}

	public ValidationResult validate(ExecutableCoverageMap map) { return ExecutableCoverageMapValidator.validate(map); }

	private JsonObject toJson(ExecutableCoverageMap map)
	{
		JsonObject root = new JsonObject(); root.addProperty("schemaVersion", map.schemaVersion());
		root.addProperty("revision", map.revision().value()); root.addProperty("generatedAt", map.generatedAt().toString());
		root.add("generator", GSON.toJsonTree(map.generator())); root.add("tests", ExecutableCoverageJson.tests(map.tests()));
		root.add("unmapped", ExecutableCoverageJson.unmapped(map.unmapped()));
		root.add("setupScopes", ExecutableCoverageJson.scopes(map.setupScopes()));
		root.add("completeness", map.completeness() == null ? JsonNull.INSTANCE : completeness(map.completeness()));
		root.add("statistics", GSON.toJsonTree(map.statistics())); root.addProperty("lifecycleState", map.lifecycleState().name());
		if (map.methodCoverageReference() != null) root.add("methodCoverage", GSON.toJsonTree(map.methodCoverageReference()));
		return root;
	}

	private ExecutableCoverageMap fromJson(JsonObject root)
	{
		ExecutableCompleteness completeness = root.has("completeness") && !root.get("completeness").isJsonNull()
				? completeness(root.getAsJsonObject("completeness")) : null;
		return new ExecutableCoverageMap(root.get("schemaVersion").getAsInt(),
				new CoverageMapRevision(ExecutableCoverageJson.required(root, "revision")),
				Instant.parse(ExecutableCoverageJson.required(root, "generatedAt")),
				GSON.fromJson(root.get("generator"), GeneratorProvenance.class),
				ExecutableCoverageJson.tests(root.getAsJsonObject("tests")),
				ExecutableCoverageJson.unmapped(root.getAsJsonArray("unmapped")),
				ExecutableCoverageJson.scopes(root.getAsJsonArray("setupScopes")), completeness,
				GSON.fromJson(root.get("statistics"), MapStatistics.class),
				CoverageMapLifecycleState.valueOf(ExecutableCoverageJson.required(root, "lifecycleState")),
				root.has("methodCoverage") ? GSON.fromJson(root.get("methodCoverage"), MethodCoverageReference.class) : null);
	}

	private JsonObject completeness(ExecutableCompleteness value)
	{
		JsonObject result = new JsonObject(); result.add("expectedTests", ExecutableCoverageJson.identities(value.expectedTests()));
		result.add("reportedTests", ExecutableCoverageJson.identities(value.reportedTests()));
		result.add("expectedShards", ExecutableCoverageJson.shards(value.expectedShards()));
		result.add("completedShards", ExecutableCoverageJson.shards(value.completedShards()));
		result.add("missingTests", ExecutableCoverageJson.identities(value.missingTests()));
		result.add("unexpectedTests", ExecutableCoverageJson.identities(value.unexpectedTests()));
		result.add("missingShards", ExecutableCoverageJson.shards(value.missingShards()));
		result.add("duplicateShards", ExecutableCoverageJson.shards(value.duplicateShards()));
		result.add("duplicateTests", ExecutableCoverageJson.identities(value.duplicateTests())); return result;
	}

	private ExecutableCompleteness completeness(JsonObject value)
	{
		return new ExecutableCompleteness(ExecutableCoverageJson.identities(value, "expectedTests"),
				ExecutableCoverageJson.identities(value, "reportedTests"), ExecutableCoverageJson.shards(value, "expectedShards"),
				ExecutableCoverageJson.shards(value, "completedShards"), ExecutableCoverageJson.identities(value, "missingTests"),
				ExecutableCoverageJson.identities(value, "unexpectedTests"), ExecutableCoverageJson.shards(value, "missingShards"),
				ExecutableCoverageJson.shards(value, "duplicateShards"), ExecutableCoverageJson.identities(value, "duplicateTests"));
	}

	private static byte[] bytes(JsonObject value) { return GSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
	private static String checksum(byte[] bytes)
	{
		try
		{
			StringBuilder result = new StringBuilder("sha256:");
			for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value));
			return result.toString();
		}
		catch (Exception e) { throw new IllegalStateException(e); }
	}
	private static CoverageMapValidationException invalid(ValidationCategory category, ValidationCode code, String message)
	{
		return new CoverageMapValidationException(ExecutableCoverageMapValidator.error(category, code, message));
	}
}
