// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.mapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


class IndexedCoverageMapTest
{

	@TempDir
	Path tempDir;

	@Test
	void constructorSetsAllFields()
	{
		CoverageMapMetadata metadata = new CoverageMapMetadata();
		metadata.setBaseBranch("main");
		metadata.setCommitId("abc123");

		List<String> classIndex = List.of("com.example.Foo", "com.example.Bar");
		List<String> methodIndex = List.of("com.example.Foo#doWork", "com.example.Bar#init");
		Map<String, IndexedCoverageMap.TestCoverageRef> mappings = Map.of(
				"MyTest#test1", new IndexedCoverageMap.TestCoverageRef(List.of(0, 1), List.of(0, 1))
		);

		IndexedCoverageMap map = new IndexedCoverageMap(metadata, classIndex, methodIndex, mappings);

		assertEquals("main", map.getMetadata().getBaseBranch());
		assertEquals("abc123", map.getMetadata().getCommitId());
		assertEquals(2, map.getClassIndex().size());
		assertEquals(2, map.getMethodIndex().size());
		assertEquals(1, map.getTestMappings().size());
	}

	@Test
	void defaultConstructorLeavesFieldsNull()
	{
		IndexedCoverageMap map = new IndexedCoverageMap();

		assertNull(map.getMetadata());
		assertNull(map.getClassIndex());
		assertNull(map.getMethodIndex());
		assertNull(map.getTestMappings());
		assertNull(map.getClassMetrics());
	}

	@Test
	void testCoverageRefConstructorAndGetters()
	{
		IndexedCoverageMap.TestCoverageRef ref = new IndexedCoverageMap.TestCoverageRef(
				List.of(0, 2, 4), List.of(1, 3));

		assertEquals(List.of(0, 2, 4), ref.getClasses());
		assertEquals(List.of(1, 3), ref.getMethods());
	}

	@Test
	void roundTripThroughGson()
	{
		CoverageMapMetadata metadata = new CoverageMapMetadata();
		metadata.setBaseBranch("develop");
		metadata.setCommitId("def456");
		metadata.setTimestamp("2026-04-28T10:00:00Z");

		IndexedCoverageMap original = new IndexedCoverageMap(
				metadata,
				List.of("com.example.Service", "com.example.Repo"),
				List.of("com.example.Service#save", "com.example.Repo#find"),
				Map.of("SvcTest#testSave", new IndexedCoverageMap.TestCoverageRef(List.of(0), List.of(0)))
		);

		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		String json = gson.toJson(original);
		IndexedCoverageMap deserialized = gson.fromJson(json, IndexedCoverageMap.class);

		assertEquals("develop", deserialized.getMetadata().getBaseBranch());
		assertEquals("def456", deserialized.getMetadata().getCommitId());
		assertEquals(List.of("com.example.Service", "com.example.Repo"), deserialized.getClassIndex());
		assertEquals(List.of("com.example.Service#save", "com.example.Repo#find"), deserialized.getMethodIndex());
		assertEquals(1, deserialized.getTestMappings().size());
		assertEquals(List.of(0), deserialized.getTestMappings().get("SvcTest#testSave").getClasses());
	}

	@Test
	void indexedFormatLoadedViaCoverageMapReader() throws IOException
	{
		String json = """
				{
				  "metadata": {"baseBranch": "main", "commitId": "aaa111", "timestamp": "2026-01-01T00:00:00Z"},
				  "classIndex": ["com.example.Alpha", "com.example.Beta"],
				  "methodIndex": ["com.example.Alpha#run", "com.example.Beta#stop"],
				  "testMappings": {
				    "AlphaTest#testRun": {"classes": [0], "methods": [0]},
				    "BetaTest#testStop": {"classes": [1], "methods": [1]}
				  }
				}
				""";

		File mapFile = tempDir.resolve("test-coverage-map.json").toFile();
		Files.writeString(mapFile.toPath(), json);

		CoverageMap map = CoverageMapReader.load(mapFile);

		assertNotNull(map);
		assertEquals("main", map.getMetadata().getBaseBranch());
		assertEquals("aaa111", map.getMetadata().getCommitId());
		assertEquals(2, map.getTestMappings().size());

		Map<String, List<String>> alphaTest = map.getTestMappings().get("AlphaTest#testRun");
		assertNotNull(alphaTest);
		assertEquals(List.of("com.example.Alpha"), alphaTest.get("classes"));
		assertEquals(List.of("com.example.Alpha#run"), alphaTest.get("methods"));

		Map<String, List<String>> betaTest = map.getTestMappings().get("BetaTest#testStop");
		assertNotNull(betaTest);
		assertEquals(List.of("com.example.Beta"), betaTest.get("classes"));
		assertEquals(List.of("com.example.Beta#stop"), betaTest.get("methods"));
	}

	@Test
	void classMetricsPreservedInRoundTrip() throws IOException
	{
		String json = """
				{
				  "metadata": {"baseBranch": "main", "commitId": "bbb222", "timestamp": "2026-01-01T00:00:00Z"},
				  "classIndex": ["com.example.Svc"],
				  "methodIndex": ["com.example.Svc#go"],
				  "testMappings": {
				    "SvcTest#test": {"classes": [0], "methods": [0]}
				  },
				  "classMetrics": {
				    "com.example.Svc": {"lineCovered": 10, "lineMissed": 2, "branchCovered": 5, "branchMissed": 1}
				  }
				}
				""";

		File mapFile = tempDir.resolve("test-coverage-map.json").toFile();
		Files.writeString(mapFile.toPath(), json);

		CoverageMap map = CoverageMapReader.load(mapFile);

		assertNotNull(map.getClassMetrics());
		ClassCoverageMetrics metrics = map.getClassMetrics().get("com.example.Svc");
		assertNotNull(metrics);
		assertEquals(10, metrics.getLineCovered());
		assertEquals(2, metrics.getLineMissed());
		assertEquals(5, metrics.getBranchCovered());
		assertEquals(1, metrics.getBranchMissed());
	}
}
