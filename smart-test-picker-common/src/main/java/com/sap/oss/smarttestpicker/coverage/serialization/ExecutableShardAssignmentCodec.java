// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;

/** Deterministic strict JSON codec for one executable shard assignment. */
public final class ExecutableShardAssignmentCodec
{
	public byte[] serialize(ExecutableShardAssignment assignment)
	{
		JsonObject root = new JsonObject(); root.addProperty("version", assignment.version());
		root.addProperty("revision", assignment.revision().value()); root.addProperty("shardId", assignment.shardId().value());
		JsonArray tests = new JsonArray(); assignment.tests().stream().sorted().map(ExecutableTestIdentity::toString).forEach(tests::add);
		root.add("tests", tests); return new Gson().toJson(root).getBytes(StandardCharsets.UTF_8);
	}

	public ExecutableShardAssignment deserialize(byte[] bytes)
	{
		try
		{
			JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) throw new IllegalArgumentException("Assignment must be a JSON object");
			JsonObject root = parsed.getAsJsonObject();
			if (!root.has("version")) throw new IllegalArgumentException("version is required");
			int version = root.get("version").getAsInt();
			if (version != ExecutableShardAssignment.CURRENT_VERSION)
				throw new IllegalArgumentException("Unsupported executable assignment version: " + version);
			String revision = ExecutableCoverageJson.required(root, "revision");
			String shardId = ExecutableCoverageJson.required(root, "shardId");
			if (!root.has("tests") || !root.get("tests").isJsonArray()) throw new IllegalArgumentException("tests is required");
			Set<String> wireValues = new HashSet<>(); TreeSet<ExecutableTestIdentity> tests = new TreeSet<>();
			for (JsonElement value : root.getAsJsonArray("tests"))
			{
				String wire = value.getAsString();
				if (!wireValues.add(wire)) throw new IllegalArgumentException("Duplicate executable identity: " + wire);
				if (!tests.add(ExecutableTestIdentity.parse(wire)))
					throw new IllegalArgumentException("Duplicate executable identity: " + wire);
			}
			return new ExecutableShardAssignment(version, new CoverageMapRevision(revision), new ShardId(shardId), tests);
		}
		catch (IllegalArgumentException e) { throw e; }
		catch (RuntimeException e) { throw new IllegalArgumentException("Malformed executable shard assignment", e); }
	}
}
