// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.mapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.sap.oss.smarttestpicker.execution.ExecutionIdentityMetadata;
import com.sap.oss.smarttestpicker.execution.ExecutionShape;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionIdentityCompatibilityTest
{
	@TempDir Path temp;

	@Test void oldMapWithoutMetadataStillDeserializes() throws Exception
	{
		Path map = temp.resolve("old.json");
		Files.writeString(map, "{\"metadata\":{\"commitId\":\"abc\"},\"testMappings\":{\"T#x\":{\"classes\":[],\"methods\":[]}}}");
		CoverageMap read = CoverageMapReader.load(map.toFile());
		assertNull(read.getExecutionIdentities());
		assertTrue(read.getTestMappings().containsKey("T#x"));
	}

	@Test void optionalMetadataRoundTripsWithoutChangingLegacyKey() throws Exception
	{
		CoverageMap source = new CoverageMap(null, Map.of("T#x_hash", Map.of("classes", java.util.List.of(), "methods", java.util.List.of())));
		source.setExecutionIdentities(Map.of("T#x_hash", new ExecutionIdentityMetadata("m", "a.T", "x_hash", "T#x_hash", "junit-vintage", ExecutionShape.ORDINARY)));
		Path map = temp.resolve("new.json"); Files.writeString(map, new Gson().toJson(source));
		CoverageMap read = CoverageMapReader.load(map.toFile());
		assertEquals("x_hash", read.getExecutionIdentities().get("T#x_hash").getLogicalMethodName());
		assertTrue(read.getTestMappings().containsKey("T#x_hash"));
	}
}
