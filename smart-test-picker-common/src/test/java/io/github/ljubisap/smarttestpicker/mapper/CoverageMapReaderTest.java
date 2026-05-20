// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.mapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


class CoverageMapReaderTest
{

	@Test
	void loadsPlainJsonMap()throws IOException
	{
		File mapFile = getFixture("coverage-map-with-metadata.json");

		CoverageMap map = CoverageMapReader.load(mapFile);

		assertNotNull(map);
		assertNotNull(map.getMetadata());
		assertEquals("main", map.getMetadata().getBaseBranch());
		assertEquals("abc1234", map.getMetadata().getCommitId());
		assertEquals(3, map.getTestMappings().size());
		assertTrue(map.getTestMappings().containsKey("OwnerControllerTests#testShowOwner"));
	}

	@Test
	void loadsIndexedJsonMap(@TempDir Path tempDir) throws IOException
	{
		File mapFile = writeIndexedMap(tempDir.resolve("indexed.json").toFile(), false);

		CoverageMap map = CoverageMapReader.load(mapFile);

		assertNotNull(map);
		assertEquals(2, map.getTestMappings().size());

		Map<String, List<String>> coverage = map.getTestMappings().get("TestA#test1");
		assertNotNull(coverage);
		assertTrue(coverage.get("classes").contains("com.example.Foo"));
		assertTrue(coverage.get("methods").contains("com.example.Foo#doWork"));
	}

	@Test
	void loadsGzipMap(@TempDir Path tempDir) throws IOException
	{
		File mapFile = tempDir.resolve("map.json.gz").toFile();
		writePlainMapGzip(mapFile);

		CoverageMap map = CoverageMapReader.load(mapFile);

		assertNotNull(map);
		assertEquals("main", map.getMetadata().getBaseBranch());
		assertEquals(1, map.getTestMappings().size());
		assertTrue(map.getTestMappings().containsKey("SimpleTest#test1"));
	}

	@Test
	void loadsIndexedGzipMap(@TempDir Path tempDir) throws IOException
	{
		File mapFile = writeIndexedMap(tempDir.resolve("indexed.json.gz").toFile(), true);

		CoverageMap map = CoverageMapReader.load(mapFile);

		assertNotNull(map);
		assertEquals(2, map.getTestMappings().size());
		assertTrue(map.getTestMappings().containsKey("TestA#test1"));
		assertTrue(map.getTestMappings().containsKey("TestB#test2"));
	}

	@Test
	void throwsOnMissingFile()
	{
		File nonexistent = new File("/nonexistent/path/map.json");
		assertThrows(IOException.class, () -> CoverageMapReader.load(nonexistent));
	}

	@Test
	void throwsOnInvalidJson(@TempDir Path tempDir) throws IOException
	{
		File mapFile = tempDir.resolve("bad.json").toFile();
		try (FileWriter w = new FileWriter(mapFile))
		{
			w.write("not valid json at all");
		}
		assertThrows(Exception.class, () -> CoverageMapReader.load(mapFile));
	}

	@Test
	void resolvesIndexedReferencesCorrectly(@TempDir Path tempDir) throws IOException
	{
		File mapFile = writeIndexedMap(tempDir.resolve("indexed.json").toFile(), false);

		CoverageMap map = CoverageMapReader.load(mapFile);

		Map<String, List<String>> coverageA = map.getTestMappings().get("TestA#test1");
		assertEquals(List.of("com.example.Foo", "com.example.Bar"), coverageA.get("classes"));
		assertEquals(List.of("com.example.Foo#doWork"), coverageA.get("methods"));

		Map<String, List<String>> coverageB = map.getTestMappings().get("TestB#test2");
		assertEquals(List.of("com.example.Bar"), coverageB.get("classes"));
		assertEquals(List.of("com.example.Bar#init"), coverageB.get("methods"));
	}


	private File writeIndexedMap(File outputFile, boolean gzip) throws IOException
	{
		String json = "{\n"
				+ "  \"metadata\": { \"baseBranch\": \"main\", \"commitId\": \"abc123\", \"timestamp\": \"2026-04-11T10:00:00Z\" },\n"
				+ "  \"classIndex\": [\"com.example.Bar\", \"com.example.Foo\"],\n"
				+ "  \"methodIndex\": [\"com.example.Bar#init\", \"com.example.Foo#doWork\"],\n"
				+ "  \"testMappings\": {\n"
				+ "    \"TestA#test1\": { \"classes\": [1, 0], \"methods\": [1] },\n"
				+ "    \"TestB#test2\": { \"classes\": [0], \"methods\": [0] }\n"
				+ "  }\n"
				+ "}";

		try (Writer writer = gzip
				? new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(outputFile)), StandardCharsets.UTF_8)
				: new FileWriter(outputFile))
		{
			writer.write(json);
		}
		return outputFile;
	}

	private void writePlainMapGzip(File outputFile) throws IOException
	{
		CoverageMapMetadata metadata = new CoverageMapMetadata("main", "def456", "2026-04-11T10:00:00Z");
		Map<String, List<String>> coverage = Map.of(
				"classes", List.of("com.example.Foo"),
				"methods", List.of("com.example.Foo#doWork")
		);
		CoverageMap map = new CoverageMap(metadata, Map.of("SimpleTest#test1", coverage));

		try (Writer writer = new OutputStreamWriter(
				new GZIPOutputStream(new FileOutputStream(outputFile)), StandardCharsets.UTF_8))
		{
			new GsonBuilder().create().toJson(map, writer);
		}
	}

	private File getFixture(String name)
	{
		return new File(getClass().getClassLoader().getResource(name).getFile());
	}
}
