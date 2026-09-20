// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.ExecutionPlanContract;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlanMode;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

/** Authoritative deterministic execution-plan v1 decoder and encoder. */
public final class ExecutionPlanCodec
{
	public ExecutionPlan deserialize(byte[] bytes)
	{
		try
		{
			JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) throw new IllegalArgumentException("Execution plan root must be an object");
			JsonObject root = parsed.getAsJsonObject();
			int version = requiredInt(root, "version");
			if (version != ExecutionPlanContract.VERSION)
				throw new IllegalArgumentException("Unsupported execution-plan version " + version
						+ "; supported version is " + ExecutionPlanContract.VERSION);
			ExecutionPlanMode mode;
			try { mode = ExecutionPlanMode.valueOf(requiredString(root, "mode")); }
			catch (RuntimeException malformed) { throw new IllegalArgumentException("Unsupported execution-plan mode", malformed); }
			JsonArray values = requiredArray(root, "tests");
			Set<ExecutableTestIdentity> tests = new TreeSet<>();
			for (JsonElement value : values)
			{
				if (!value.isJsonObject()) throw new IllegalArgumentException("Execution-plan test must be an object");
				JsonObject test = value.getAsJsonObject();
				String target = requiredString(test, "target");
				String className = requiredString(test, "class");
				ExecutableTestIdentity identity = new ExecutableTestIdentity(ExecutionTarget.parse(target),
						TestIdentity.parse(className + "#" + requiredString(test, "method")));
				if (!tests.add(identity)) throw new IllegalArgumentException("Duplicate execution-plan identity: " + identity);
			}
			CoverageMapRevision revision = root.has("revision") && !root.get("revision").isJsonNull()
					? new CoverageMapRevision(requiredString(root, "revision")) : null;
			return new ExecutionPlan(version, mode, tests, revision);
		}
		catch (IllegalArgumentException failure) { throw failure; }
		catch (RuntimeException failure) { throw new IllegalArgumentException("Malformed execution plan", failure); }
	}

	public byte[] serialize(ExecutionPlan plan)
	{
		if (plan.version() != ExecutionPlanContract.VERSION)
			throw new IllegalArgumentException("Unsupported execution-plan version: " + plan.version());
		JsonObject root = new JsonObject();
		root.addProperty("version", plan.version());
		root.addProperty("mode", plan.mode().name());
		plan.boundRevision().ifPresent(revision -> root.addProperty("revision", revision.value()));
		JsonArray tests = new JsonArray();
		for (ExecutableTestIdentity identity : plan.tests())
		{
			JsonObject value = new JsonObject();
			value.addProperty("target", identity.target().toString());
			value.addProperty("class", identity.test().className());
			String logical = identity.test().toString();
			value.addProperty("method", logical.substring(logical.indexOf('#') + 1));
			tests.add(value);
		}
		root.add("tests", tests);
		return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root)
				.concat("\n").getBytes(StandardCharsets.UTF_8);
	}

	private static String requiredString(JsonObject object, String name)
	{
		if (!object.has(name) || !object.get(name).isJsonPrimitive()
				|| !object.get(name).getAsJsonPrimitive().isString())
			throw new IllegalArgumentException("Execution-plan " + name + " must be a string");
		String value = object.get(name).getAsString();
		if (value.isBlank() || !value.equals(value.trim()))
			throw new IllegalArgumentException("Execution-plan " + name + " is blank or untrimmed");
		return value;
	}

	private static int requiredInt(JsonObject object, String name)
	{
		if (!object.has(name) || !object.get(name).isJsonPrimitive()
				|| !object.get(name).getAsJsonPrimitive().isNumber())
			throw new IllegalArgumentException("Execution-plan " + name + " must be an integer");
		if (!object.get(name).getAsString().matches("-?(0|[1-9][0-9]*)"))
			throw new IllegalArgumentException("Execution-plan " + name + " must be an integer");
		return object.get(name).getAsInt();
	}

	private static JsonArray requiredArray(JsonObject object, String name)
	{
		if (!object.has(name) || !object.get(name).isJsonArray())
			throw new IllegalArgumentException("Execution-plan " + name + " must be an array");
		return object.getAsJsonArray(name);
	}
}
