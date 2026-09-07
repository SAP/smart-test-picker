// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadTestInventoryCodecTest {
	@TempDir Path directory;
	@Test void roundTripsInDeterministicNaturalOrder() throws Exception {
		HeadTestInventory inventory = HeadTestInventory.from(List.of(
				new TestIdentity("com.example.ZTest", "z"), new TestIdentity("com.example.ATest", "a", "java.lang.String")));
		Path first = directory.resolve("first.json"), second = directory.resolve("second.json");
		HeadTestInventoryCodec codec = new HeadTestInventoryCodec();
		codec.write(first.toFile(), inventory); codec.write(second.toFile(), inventory);
		assertEquals(inventory, codec.read(first.toFile()));
		assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
		assertEquals("[\"com.example.ATest#a(java.lang.String)\",\"com.example.ZTest#z\"]\n",
				Files.readString(first, StandardCharsets.UTF_8));
	}
	@Test void rejectsInvalidRepresentations() throws Exception {
		assertRejected("{}", "object.json"); assertRejected("[null]", "null.json");
		assertRejected("[\"bad\"]", "bad.json");
		assertRejected("[\"com.example.Test#a\",\"com.example.Test#a\"]", "duplicate.json");
	}
	private void assertRejected(String json, String name) throws Exception {
		Path file = directory.resolve(name); Files.writeString(file, json);
		assertThrows(java.io.IOException.class, () -> new HeadTestInventoryCodec().read(file.toFile()));
	}
}
