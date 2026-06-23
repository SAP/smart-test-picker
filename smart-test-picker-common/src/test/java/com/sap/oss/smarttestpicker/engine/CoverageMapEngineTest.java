// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.engine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.mapper.CoverageMap;
import com.sap.oss.smarttestpicker.mapper.CoverageMapReader;

import static org.junit.jupiter.api.Assertions.*;


class CoverageMapEngineTest
{

	@TempDir
	Path tempDir;

	private File reportsDir;
	private File projectDir;
	private EngineLogger logger;

	@BeforeEach
	void setUp() throws Exception
	{
		projectDir = tempDir.resolve("project").toFile();
		projectDir.mkdirs();

		run("git", "init");
		run("git", "config", "user.email", "test@test.com");
		run("git", "config", "user.name", "Test");
		Files.writeString(tempDir.resolve("project/dummy.txt"), "init");
		run("git", "add", ".");
		run("git", "commit", "-m", "initial");

		reportsDir = tempDir.resolve("reports").toFile();
		reportsDir.mkdirs();

		try (InputStream is = getClass().getClassLoader().getResourceAsStream("session_MyTest#testSomething.xml"))
		{
			Files.copy(is, reportsDir.toPath().resolve("session_MyTest#testSomething.xml"));
		}

		logger = new NoOpLogger();
	}

	@Test
	void generatePlainFormat() throws IOException
	{
		File output = tempDir.resolve("map.json").toFile();

		CoverageMapEngine engine = new CoverageMapEngine();
		engine.generate(reportsDir, output, "main", projectDir, logger, false, false);

		assertTrue(output.exists());
		CoverageMap map = CoverageMapReader.load(output);
		assertNotNull(map.getMetadata());
		assertEquals("main", map.getMetadata().getBaseBranch());
		assertNotNull(map.getMetadata().getCommitId());
		assertEquals(1, map.getTestMappings().size());
		assertTrue(map.getTestMappings().containsKey("MyTest#testSomething"));
	}

	@Test
	void generateIndexedFormat() throws IOException
	{
		File output = tempDir.resolve("map-indexed.json").toFile();

		CoverageMapEngine engine = new CoverageMapEngine();
		engine.generate(reportsDir, output, "main", projectDir, logger, true, false);

		assertTrue(output.exists());
		String json = Files.readString(output.toPath());
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		assertTrue(root.has("classIndex"));
		assertTrue(root.has("methodIndex"));
		assertTrue(root.has("testMappings"));

		CoverageMap map = CoverageMapReader.load(output);
		assertEquals(1, map.getTestMappings().size());
		assertTrue(map.getTestMappings().containsKey("MyTest#testSomething"));
	}

	@Test
	void generateGzipFormat() throws IOException
	{
		File output = tempDir.resolve("map.json.gz").toFile();

		CoverageMapEngine engine = new CoverageMapEngine();
		engine.generate(reportsDir, output, "main", projectDir, logger, false, true);

		assertTrue(output.exists());
		CoverageMap map = CoverageMapReader.load(output);
		assertEquals(1, map.getTestMappings().size());
	}

	@Test
	void generateIndexedGzipFormat() throws IOException
	{
		File output = tempDir.resolve("map-indexed.json.gz").toFile();

		CoverageMapEngine engine = new CoverageMapEngine();
		engine.generate(reportsDir, output, "main", projectDir, logger, true, true);

		assertTrue(output.exists());
		CoverageMap map = CoverageMapReader.load(output);
		assertEquals(1, map.getTestMappings().size());
		assertTrue(map.getTestMappings().containsKey("MyTest#testSomething"));
	}

	@Test
	void emptyReportsDirProducesEmptyMap() throws IOException
	{
		File emptyDir = tempDir.resolve("empty-reports").toFile();
		emptyDir.mkdirs();
		File output = tempDir.resolve("empty-map.json").toFile();

		CoverageMapEngine engine = new CoverageMapEngine();
		engine.generate(emptyDir, output, "main", projectDir, logger, false, false);

		assertTrue(output.exists());
		CoverageMap map = CoverageMapReader.load(output);
		assertTrue(map.getTestMappings().isEmpty());
	}

	@Test
	void gzipFlagAppendsGzExtension() throws IOException
	{
		File output = tempDir.resolve("map-no-ext.json").toFile();

		CoverageMapEngine engine = new CoverageMapEngine();
		engine.generate(reportsDir, output, "main", projectDir, logger, false, true);

		File actualFile = new File(output.getAbsolutePath() + ".gz");
		assertTrue(actualFile.exists(), "Expected .gz file to be created");
		assertFalse(output.exists(), "Original file without .gz should not exist");

		CoverageMap map = CoverageMapReader.load(actualFile);
		assertEquals(1, map.getTestMappings().size());
	}

	@Test
	void emptyXmlFileSkipped() throws IOException
	{
		File emptyXmlDir = tempDir.resolve("empty-xml-reports").toFile();
		emptyXmlDir.mkdirs();
		Files.writeString(emptyXmlDir.toPath().resolve("session_EmptyTest#test.xml"), "");

		File output = tempDir.resolve("empty-xml-map.json").toFile();

		CoverageMapEngine engine = new CoverageMapEngine();
		engine.generate(emptyXmlDir, output, "main", projectDir, logger, false, false);

		assertTrue(output.exists());
		CoverageMap map = CoverageMapReader.load(output);
		assertTrue(map.getTestMappings().isEmpty());
	}

	private void run(String... args) throws IOException, InterruptedException
	{
		ProcessBuilder pb = new ProcessBuilder(args);
		pb.directory(projectDir);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getInputStream().readAllBytes();
		int exit = p.waitFor();
		if (exit != 0)
			throw new RuntimeException("Command failed: " + String.join(" ", args));
	}

	private static class NoOpLogger implements EngineLogger
	{
		@Override
		public void info(String msg, Object... args) {}

		@Override
		public void warn(String msg, Object... args) {}
	}
}
