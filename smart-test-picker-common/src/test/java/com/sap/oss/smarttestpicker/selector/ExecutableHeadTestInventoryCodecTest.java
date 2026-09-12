// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.nio.file.Files;
import java.util.List;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ExecutableHeadTestInventoryCodecTest
{
	@TempDir java.nio.file.Path directory;

	@Test void versionedRevisionBoundInventoryIsDeterministicAndRejectsLegacyArray() throws Exception
	{
		ExecutableTestIdentity a = ExecutableTestIdentity.parse("maven:a::com.example.Test#a");
		ExecutableTestIdentity z = ExecutableTestIdentity.parse("maven:z::com.example.Test#z");
		ExecutableHeadTestInventory inventory = ExecutableHeadTestInventory.atRevision("abc123", List.of(z, a));
		var file = directory.resolve("inventory.json").toFile(); var codec = new ExecutableHeadTestInventoryCodec();
		codec.write(file, inventory); String json = Files.readString(file.toPath());
		assertTrue(json.contains("\"version\":1")); assertTrue(json.contains("\"revision\":\"abc123\""));
		assertTrue(json.indexOf(a.toString()) < json.indexOf(z.toString())); assertEquals(inventory, codec.read(file));
		Files.writeString(file.toPath(), "[\"maven:a::com.example.Test#a\"]");
		assertThrows(java.io.IOException.class, () -> codec.read(file));
	}
}
