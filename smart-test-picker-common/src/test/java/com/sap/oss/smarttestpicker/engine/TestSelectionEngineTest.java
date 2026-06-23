// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.engine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapMetadata;
import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;

import static org.junit.jupiter.api.Assertions.*;


class TestSelectionEngineTest
{

	@TempDir
	Path tempDir;

	private File projectDir;
	private EngineLogger logger;
	private TestSelectionEngine engine;

	@BeforeEach
	void setUp() throws Exception
	{
		projectDir = tempDir.resolve("project").toFile();
		projectDir.mkdirs();

		run("git", "init");
		run("git", "config", "user.email", "test@test.com");
		run("git", "config", "user.name", "Test");

		Path srcDir = projectDir.toPath().resolve("src/main/java/org/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "package org.example; public class Foo {}");
		run("git", "add", ".");
		run("git", "commit", "-m", "initial");

		logger = new NoOpLogger();
		engine = new TestSelectionEngine();
	}

	@Test
	void returnsFullSuiteWhenMapNotFound()
	{
		File missingMap = new File(tempDir.toFile(), "nonexistent.json");
		File testClassesDir = tempDir.resolve("test-classes").toFile();

		SelectionOutput result = engine.select(missingMap, testClassesDir, projectDir, 500, List.of(), logger);

		assertEquals("FULL_SUITE", result.getStatus());
		assertTrue(result.getReason().contains("not found"));
	}

	@Test
	void returnsFullSuiteWhenNoMetadata() throws IOException
	{
		File mapFile = tempDir.resolve("no-meta.json").toFile();
		try (FileWriter w = new FileWriter(mapFile))
		{
			w.write("{\"testMappings\": {}}");
		}
		File testClassesDir = tempDir.resolve("test-classes").toFile();

		SelectionOutput result = engine.select(mapFile, testClassesDir, projectDir, 500, List.of(), logger);

		assertEquals("FULL_SUITE", result.getStatus());
		assertTrue(result.getReason().toLowerCase().contains("metadata"));
	}

	@Test
	void returnsFullSuiteWhenInvalidCommitId() throws IOException
	{
		File mapFile = writeMap("0000000000000000000000000000000000000000");
		File testClassesDir = tempDir.resolve("test-classes").toFile();

		SelectionOutput result = engine.select(mapFile, testClassesDir, projectDir, 500, List.of(), logger);

		assertEquals("FULL_SUITE", result.getStatus());
		assertTrue(result.getReason().contains("not a valid git commit"));
	}

	@Test
	void returnsNoneWhenNoChanges() throws Exception
	{
		String commitId = getHeadCommit();
		File mapFile = writeMap(commitId);
		File testClassesDir = tempDir.resolve("test-classes").toFile();

		SelectionOutput result = engine.select(mapFile, testClassesDir, projectDir, 500, List.of(), logger);

		assertEquals("NONE", result.getStatus());
		assertTrue(result.getReason().contains("No production code changes"));
	}

	@Test
	void returnsSelectedWhenClassChanged() throws Exception
	{
		String commitId = getHeadCommit();
		File mapFile = writeMapWithCoverage(commitId);

		Path fooFile = projectDir.toPath().resolve("src/main/java/org/example/Foo.java");
		Files.writeString(fooFile, "package org.example; public class Foo { int x; }");
		run("git", "add", ".");
		run("git", "commit", "-m", "modify Foo");

		File testClassesDir = tempDir.resolve("test-classes").toFile();

		SelectionOutput result = engine.select(mapFile, testClassesDir, projectDir, 500, List.of(), logger);

		assertEquals("SELECTED", result.getStatus());
		assertFalse(result.getSelectedTests().isEmpty());
		assertTrue(result.getSelectedTests().contains("FooTest#testFoo"));
	}

	@Test
	void returnsFullSuiteWhenCommitDistanceExceeded() throws Exception
	{
		String commitId = getHeadCommit();

		Files.writeString(projectDir.toPath().resolve("file1.txt"), "a");
		run("git", "add", ".");
		run("git", "commit", "-m", "commit 1");

		Files.writeString(projectDir.toPath().resolve("file2.txt"), "b");
		run("git", "add", ".");
		run("git", "commit", "-m", "commit 2");

		File mapFile = writeMap(commitId);
		File testClassesDir = tempDir.resolve("test-classes").toFile();

		SelectionOutput result = engine.select(mapFile, testClassesDir, projectDir, 1, List.of(), logger);

		assertEquals("FULL_SUITE", result.getStatus());
		assertTrue(result.getReason().contains("exceeds max"));
	}

	@Test
	void returnsFullSuiteWhenTriggerMatches() throws Exception
	{
		String commitId = getHeadCommit();

		Files.writeString(projectDir.toPath().resolve("build.gradle"), "apply plugin: 'java'");
		run("git", "add", ".");
		run("git", "commit", "-m", "add build.gradle");

		File mapFile = writeMap(commitId);
		File testClassesDir = tempDir.resolve("test-classes").toFile();

		SelectionOutput result = engine.select(mapFile, testClassesDir, projectDir, 500,
				List.of("build.gradle"), logger);

		assertEquals("FULL_SUITE", result.getStatus());
		assertTrue(result.getReason().contains("fullSuiteTrigger"));
	}


	private File writeMap(String commitId) throws IOException
	{
		CoverageMapMetadata metadata = new CoverageMapMetadata("main", commitId, "2026-04-11T10:00:00Z");
		CoverageMap map = new CoverageMap(metadata, Map.of());
		return writeMapToFile(map);
	}

	private File writeMapWithCoverage(String commitId) throws IOException
	{
		CoverageMapMetadata metadata = new CoverageMapMetadata("main", commitId, "2026-04-11T10:00:00Z");
		Map<String, Map<String, List<String>>> mappings = Map.of(
				"FooTest#testFoo", Map.of(
						"classes", List.of("org.example.Foo"),
						"methods", List.of("org.example.Foo#doSomething")
				)
		);
		CoverageMap map = new CoverageMap(metadata, mappings);
		return writeMapToFile(map);
	}

	private File writeMapToFile(CoverageMap map) throws IOException
	{
		File mapFile = tempDir.resolve("coverage-map.json").toFile();
		try (FileWriter writer = new FileWriter(mapFile))
		{
			new GsonBuilder().setPrettyPrinting().create().toJson(map, writer);
		}
		return mapFile;
	}

	private String getHeadCommit() throws IOException, InterruptedException
	{
		ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
		pb.directory(projectDir);
		Process p = pb.start();
		String sha = new String(p.getInputStream().readAllBytes()).trim();
		p.waitFor();
		return sha;
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

	@Test
	void selectWithMultipleTestClassDirs() throws Exception
	{
		String commitId = getHeadCommit();
		File mapFile = writeMap(commitId);

		File dir1 = tempDir.resolve("classes1").toFile();
		File dir2 = tempDir.resolve("classes2").toFile();
		Path classFile1 = dir1.toPath().resolve("com/a/ATest.class");
		Path classFile2 = dir2.toPath().resolve("com/b/BTest.class");
		Files.createDirectories(classFile1.getParent());
		Files.createDirectories(classFile2.getParent());
		Files.write(classFile1, new byte[]{0});
		Files.write(classFile2, new byte[]{0});

		SelectionOutput result = engine.select(mapFile, List.of(dir1, dir2),
				projectDir, 500, List.of(), logger);

		assertNotNull(result.getUnmappedTests());
		assertTrue(result.getUnmappedTests().containsKey("com.a.ATest"));
		assertTrue(result.getUnmappedTests().containsKey("com.b.BTest"));
	}

	@Test
	void selectWithTestSourceDirs_filtersAbstractTests() throws Exception
	{
		String commitId = getHeadCommit();
		File mapFile = writeMap(commitId);

		File testClassesDir = tempDir.resolve("test-classes").toFile();
		Path classFile = testClassesDir.toPath().resolve("com/example/AbstractBaseTest.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, new byte[]{0});

		File srcDir = tempDir.resolve("test-src").toFile();
		Path javaFile = srcDir.toPath().resolve("com/example/AbstractBaseTest.java");
		Files.createDirectories(javaFile.getParent());
		Files.writeString(javaFile, "package com.example;\npublic abstract class AbstractBaseTest {}");

		SelectionOutput result = engine.select(mapFile, List.of(testClassesDir),
				List.of(srcDir), projectDir, 500, List.of(), logger);

		assertFalse(result.getUnmappedTests().containsKey("com.example.AbstractBaseTest"),
				"Abstract class should be filtered when testSourceDirs are provided");
	}
}
