// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import com.google.gson.Gson;
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
		String json = new Gson().toJson(identities) + "\n";
		Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
	}

	public HeadTestInventory read(File file) throws IOException
	{
		if (file == null || !file.isFile())
			throw new IOException("Head test inventory not found");
		try
		{
			String[] identities = new Gson().fromJson(Files.readString(file.toPath()), String[].class);
			if (identities == null) throw new IOException("Head test inventory must be a JSON array");
			return HeadTestInventory.from(Arrays.stream(identities).map(TestIdentity::parse).toList());
		}
		catch (RuntimeException e)
		{
			throw new IOException("Malformed head test inventory: " + e.getMessage(), e);
		}
	}
}
