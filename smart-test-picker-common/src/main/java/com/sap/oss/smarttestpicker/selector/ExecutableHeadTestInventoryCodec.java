// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;

/** Strict version-1 artifact codec for revision-bound executable head inventories. */
public final class ExecutableHeadTestInventoryCodec
{
	public static final int ARTIFACT_VERSION = 1;

	public void write(File file, ExecutableHeadTestInventory inventory) throws IOException
	{
		if (file == null || inventory == null) throw new IOException("Executable head inventory and output are required");
		File parent = file.getParentFile(); if (parent != null) Files.createDirectories(parent.toPath());
		JsonObject root = new JsonObject(); root.addProperty("version", ARTIFACT_VERSION);
		root.addProperty("revision", inventory.revision());
		root.add("tests", new Gson().toJsonTree(inventory.runnableTests().stream().sorted()
				.map(ExecutableTestIdentity::toString).toList()));
		Files.writeString(file.toPath(), new Gson().toJson(root) + "\n", StandardCharsets.UTF_8);
	}

	public ExecutableHeadTestInventory read(File file) throws IOException
	{
		if (file == null || !file.isFile()) throw new IOException("Executable head inventory not found");
		try
		{
			JsonElement parsed = JsonParser.parseString(Files.readString(file.toPath()));
			if (!parsed.isJsonObject()) throw new IOException("Executable head inventory must be a versioned object");
			JsonObject root = parsed.getAsJsonObject();
			if (!root.has("version") || root.get("version").getAsInt() != ARTIFACT_VERSION)
				throw new IOException("Unsupported executable head inventory version");
			if (!root.has("revision") || root.get("revision").getAsString().isBlank())
				throw new IOException("Executable head inventory revision is missing");
			if (!root.has("tests") || !root.get("tests").isJsonArray())
				throw new IOException("Executable head inventory tests are missing");
			List<ExecutableTestIdentity> tests = new ArrayList<>();
			for (JsonElement value : root.getAsJsonArray("tests"))
			{
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
					throw new IllegalArgumentException("Executable identity must be a string");
				tests.add(ExecutableTestIdentity.parse(value.getAsString()));
			}
			return ExecutableHeadTestInventory.atRevision(root.get("revision").getAsString(), tests);
		}
		catch (IOException e) { throw e; }
		catch (RuntimeException e) { throw new IOException("Malformed executable head inventory: " + e.getMessage(), e); }
	}
}
