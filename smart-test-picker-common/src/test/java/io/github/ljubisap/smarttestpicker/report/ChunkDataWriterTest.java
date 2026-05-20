// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.report;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


class ChunkDataWriterTest
{

	@TempDir
	Path tempDir;

	private final ChunkDataWriter writer = new ChunkDataWriter();

	@Test
	void writesCorrectNumberOfChunkFiles() throws IOException
	{
		Map<String, Map<String, List<String>>> mappings = buildMappings(120);

		ChunkDataWriter.ChunkWriteResult result = writer.writeChunks(
				tempDir.toFile(), mappings, Set.of(), null, null);

		assertEquals(120, result.getTotalTests());
		assertEquals(3, result.getChunkCount());
		assertTrue(tempDir.resolve("chunk-0.js").toFile().exists());
		assertTrue(tempDir.resolve("chunk-1.js").toFile().exists());
		assertTrue(tempDir.resolve("chunk-2.js").toFile().exists());
		assertFalse(tempDir.resolve("chunk-3.js").toFile().exists());
	}

	@Test
	void singleChunkForSmallDataset() throws IOException
	{
		Map<String, Map<String, List<String>>> mappings = buildMappings(10);

		ChunkDataWriter.ChunkWriteResult result = writer.writeChunks(
				tempDir.toFile(), mappings, Set.of(), null, null);

		assertEquals(10, result.getTotalTests());
		assertEquals(1, result.getChunkCount());
		assertTrue(tempDir.resolve("chunk-0.js").toFile().exists());
	}

	@Test
	void chunkFilesContainValidJsWithGlobalAssignment() throws IOException
	{
		Map<String, Map<String, List<String>>> mappings = buildMappings(3);

		writer.writeChunks(tempDir.toFile(), mappings, Set.of(), null, null);

		String content = Files.readString(tempDir.resolve("chunk-0.js"));
		assertTrue(content.startsWith("window.__STP_CHUNKS=window.__STP_CHUNKS||{};window.__STP_CHUNKS[0]="));
		assertTrue(content.endsWith(";\n"));

		// Extract JSON array and verify it's valid
		String json = content.substring(content.indexOf('=', content.indexOf("[0]")) + 1);
		json = json.substring(0, json.length() - 2); // remove ";\n"
		List<Map<String, Object>> entries = new Gson().fromJson(json,
				new TypeToken<List<Map<String, Object>>>(){}.getType());
		assertEquals(3, entries.size());
	}

	@Test
	void searchIndexContainsAllTests() throws IOException
	{
		Map<String, Map<String, List<String>>> mappings = buildMappings(75);

		writer.writeChunks(tempDir.toFile(), mappings, Set.of(), null, null);

		String content = Files.readString(tempDir.resolve("search-index.js"));
		assertTrue(content.startsWith("window.__STP_SEARCH_INDEX="));

		String json = content.substring("window.__STP_SEARCH_INDEX=".length());
		json = json.substring(0, json.length() - 2); // remove ";\n"
		List<Map<String, Object>> index = new Gson().fromJson(json,
				new TypeToken<List<Map<String, Object>>>(){}.getType());
		assertEquals(75, index.size());
	}

	@Test
	void searchIndexHasCorrectChunkReferences() throws IOException
	{
		Map<String, Map<String, List<String>>> mappings = buildMappings(120);

		writer.writeChunks(tempDir.toFile(), mappings, Set.of(), null, null);

		List<Map<String, Object>> index = parseSearchIndex();

		for (Map<String, Object> entry : index)
		{
			int k = ((Number) entry.get("k")).intValue();
			assertTrue(k >= 0 && k < 3, "chunk index should be 0-2 but was " + k);
		}

		// First 50 should be chunk 0, next 50 chunk 1, last 20 chunk 2
		long chunk0Count = index.stream().filter(e -> ((Number) e.get("k")).intValue() == 0).count();
		long chunk1Count = index.stream().filter(e -> ((Number) e.get("k")).intValue() == 1).count();
		long chunk2Count = index.stream().filter(e -> ((Number) e.get("k")).intValue() == 2).count();
		assertEquals(50, chunk0Count);
		assertEquals(50, chunk1Count);
		assertEquals(20, chunk2Count);
	}

	@Test
	void selectedFlagIsCorrect() throws IOException
	{
		Map<String, Map<String, List<String>>> mappings = buildMappings(5);
		Set<String> selected = new LinkedHashSet<>();
		selected.add("Test001#testMethod");
		selected.add("Test003#testMethod");

		writer.writeChunks(tempDir.toFile(), mappings, selected, null, null);

		List<Map<String, Object>> index = parseSearchIndex();
		for (Map<String, Object> entry : index)
		{
			String test = (String) entry.get("t");
			boolean s = (Boolean) entry.get("s");
			if ("Test001#testMethod".equals(test) || "Test003#testMethod".equals(test))
			{
				assertTrue(s, test + " should be selected");
			}
			else
			{
				assertFalse(s, test + " should not be selected");
			}
		}
	}

	@Test
	void searchIndexContainsShortNames() throws IOException
	{
		Map<String, Map<String, List<String>>> mappings = Map.of(
				"FooTest#testBar", Map.of(
						"classes", List.of("org.example.service.FooService", "org.example.model.Bar"),
						"methods", List.of("org.example.service.FooService#doFoo", "org.example.model.Bar#getName"))
		);

		writer.writeChunks(tempDir.toFile(), mappings, Set.of(), null, null);

		List<Map<String, Object>> index = parseSearchIndex();
		assertEquals(1, index.size());
		Map<String, Object> entry = index.get(0);
		String c = (String) entry.get("c");
		String m = (String) entry.get("m");
		assertTrue(c.contains("FooService"));
		assertTrue(c.contains("Bar"));
		assertFalse(c.contains("org.example"));
		assertTrue(m.contains("doFoo"));
		assertTrue(m.contains("getName"));
		assertFalse(m.contains("org.example"));
	}


	private Map<String, Map<String, List<String>>> buildMappings(int count)
	{
		Map<String, Map<String, List<String>>> mappings = new HashMap<>();
		for (int i = 0; i < count; i++)
		{
			String testName = String.format("Test%03d#testMethod", i);
			mappings.put(testName, Map.of(
					"classes", List.of("org.example.Class" + i),
					"methods", List.of("org.example.Class" + i + "#method" + i)));
		}
		return mappings;
	}

	private List<Map<String, Object>> parseSearchIndex() throws IOException
	{
		String content = Files.readString(tempDir.resolve("search-index.js"));
		String json = content.substring("window.__STP_SEARCH_INDEX=".length());
		json = json.substring(0, json.length() - 2);
		return new Gson().fromJson(json, new TypeToken<List<Map<String, Object>>>(){}.getType());
	}
}
