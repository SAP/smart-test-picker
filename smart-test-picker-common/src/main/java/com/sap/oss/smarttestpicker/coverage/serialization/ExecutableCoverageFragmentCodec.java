// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidationException;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableCoverageMapValidator;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCategory;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCode;

/** Deterministic strict schema-v3 fragment codec. */
public final class ExecutableCoverageFragmentCodec
{
	public byte[] serialize(ExecutableCoverageFragment fragment)
	{
		var validation = ExecutableCoverageMapValidator.validate(fragment);
		if (!validation.isValid()) throw new CoverageMapValidationException(validation.errors().get(0));
		JsonObject root = new JsonObject(); root.addProperty("schemaVersion", fragment.schemaVersion());
		root.addProperty("revision", fragment.revision().value()); root.addProperty("shardId", fragment.shardId().value());
		root.add("tests", ExecutableCoverageJson.tests(fragment.tests()));
		root.add("unmapped", ExecutableCoverageJson.unmapped(fragment.unmapped()));
		root.add("setupScopes", ExecutableCoverageJson.scopes(fragment.setupScopes()));
		JsonObject collection = new JsonObject(); collection.addProperty("completed", fragment.collectionCompleted());
		root.add("collection", collection); return new Gson().toJson(root).getBytes(StandardCharsets.UTF_8);
	}

	public ExecutableCoverageFragment deserialize(byte[] bytes)
	{
		try
		{
			String json = new String(bytes, StandardCharsets.UTF_8); ExecutableCoverageJson.rejectDuplicateTestProperties(json);
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			int version = root.get("schemaVersion").getAsInt();
			if (version != CoverageMapContract.SCHEMA_V3) throw incompatible(version, "fragment");
			ExecutableCoverageFragment result = new ExecutableCoverageFragment(version,
					new CoverageMapRevision(ExecutableCoverageJson.required(root, "revision")),
					new ShardId(ExecutableCoverageJson.required(root, "shardId")),
					ExecutableCoverageJson.tests(root.getAsJsonObject("tests")),
					ExecutableCoverageJson.unmapped(root.getAsJsonArray("unmapped")),
					ExecutableCoverageJson.scopes(root.getAsJsonArray("setupScopes")),
					root.getAsJsonObject("collection").get("completed").getAsBoolean());
			var validation = ExecutableCoverageMapValidator.validate(result);
			if (!validation.isValid()) throw new CoverageMapValidationException(validation.errors().get(0));
			return result;
		}
		catch (CoverageMapValidationException e) { throw e; }
		catch (RuntimeException e) { throw new IllegalArgumentException("Malformed executable coverage fragment", e); }
	}

	private static CoverageMapValidationException incompatible(int version, String artifact)
	{
		return new CoverageMapValidationException(ExecutableCoverageMapValidator.error(
				ValidationCategory.INCOMPATIBLE_SCHEMA,
				version > CoverageMapContract.SCHEMA_V3 ? ValidationCode.HIGHER_SCHEMA_VERSION : ValidationCode.SCHEMA_VERSION_MISMATCH,
				"Unsupported executable coverage-" + artifact + " schema version: " + version));
	}
}
