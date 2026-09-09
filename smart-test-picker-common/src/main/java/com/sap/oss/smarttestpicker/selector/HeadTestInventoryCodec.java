// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

/** Codec for an exact, structured head inventory represented as TestIdentity strings. */
public final class HeadTestInventoryCodec
{
	public void write(File file, HeadTestInventory inventory) throws IOException
	{
		if (file == null) throw new IOException("Head test inventory output is null");
		if (inventory == null) throw new IOException("Head test inventory is null");
		File parent = file.getParentFile();
		if (parent != null) Files.createDirectories(parent.toPath());
		List<String> identities = inventory.runnableTests().stream().sorted()
				.map(TestIdentity::toString).toList();
		String json;
		if (inventory.revision() == null) json = new Gson().toJson(identities) + "\n";
		else
		{
			JsonObject structured = new JsonObject();
			structured.addProperty("version", 1);
			structured.addProperty("revision", inventory.revision());
			structured.add("tests", new Gson().toJsonTree(identities));
			json = new Gson().toJson(structured) + "\n";
		}
		Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
	}

	public HeadTestInventory read(File file) throws IOException
	{
		if (file == null || !file.isFile())
			throw new IOException("Head test inventory not found");
		try
		{
			JsonElement root = JsonParser.parseString(Files.readString(file.toPath()));
			if (root.isJsonArray()) return inventory(null, root.getAsJsonArray());
			if (!root.isJsonObject()) throw new IOException("Head test inventory must be a JSON object or legacy array");
			JsonObject object = root.getAsJsonObject();
			if (!object.has("version") || object.get("version").getAsInt() != 1)
				throw new IOException("Unsupported head test inventory version");
			if (!object.has("revision") || !object.get("revision").isJsonPrimitive())
				throw new IOException("Head test inventory revision is missing");
			if (!object.has("tests") || !object.get("tests").isJsonArray())
				throw new IOException("Head test inventory tests are missing");
			return inventory(object.get("revision").getAsString(), object.getAsJsonArray("tests"));
		}
		catch (RuntimeException e)
		{
			throw new IOException("Malformed head test inventory: " + e.getMessage(), e);
		}
	}

	private static HeadTestInventory inventory(String revision, JsonArray values)
	{
		List<TestIdentity> tests = new java.util.ArrayList<>();
		for (JsonElement value : values)
		{
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
				throw new IllegalArgumentException("Test identity must be a string");
			tests.add(TestIdentity.parse(value.getAsString()));
		}
		return revision == null ? HeadTestInventory.from(tests) : HeadTestInventory.atRevision(revision, tests);
	}
}
