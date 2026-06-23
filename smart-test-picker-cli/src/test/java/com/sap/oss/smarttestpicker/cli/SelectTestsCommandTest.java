// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.cli;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapMetadata;
import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;


class SelectTestsCommandTest
{

	@TempDir
	Path tempDir;

	private File projectDir;
	private File mapFile;

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

		String commitId = getHeadCommit();

		Files.writeString(srcDir.resolve("Foo.java"), "package org.example; public class Foo { int x; }");
		run("git", "add", ".");
		run("git", "commit", "-m", "modify Foo");

		mapFile = writeMapWithCoverage(commitId);
	}

	@Test
	void writesJsonOutput() throws IOException
	{
		File output = tempDir.resolve("output.json").toFile();
		int exitCode = runCommand("--format", "json", "--output", output.getAbsolutePath());

		assertEquals(0, exitCode);
		assertTrue(output.exists());

		String json = Files.readString(output.toPath());
		SelectionOutput result = new Gson().fromJson(json, SelectionOutput.class);
		assertEquals("SELECTED", result.getStatus());
		assertNotNull(result.getSelectedTests());
		assertTrue(result.getSelectedTests().contains("FooTest#testFoo"));
	}

	@Test
	void writesTxtOutput() throws IOException
	{
		File output = tempDir.resolve("output.txt").toFile();
		int exitCode = runCommand("--format", "txt", "--output", output.getAbsolutePath());

		assertEquals(0, exitCode);
		assertTrue(output.exists());

		List<String> lines = Files.readAllLines(output.toPath());
		assertFalse(lines.isEmpty());
		assertTrue(lines.contains("FooTest#testFoo"));
	}

	@Test
	void writesAntOutput() throws IOException
	{
		File output = tempDir.resolve("output.ant").toFile();
		int exitCode = runCommand("--format", "ant", "--output", output.getAbsolutePath());

		assertEquals(0, exitCode);
		assertTrue(output.exists());

		String content = Files.readString(output.toPath()).trim();
		assertTrue(content.contains("FooTest"));
		assertTrue(content.contains("testFoo"));
	}

	@Test
	void antFormatGroupsByClass() throws Exception
	{
		String commitId = getHeadCommit();

		Path srcDir = projectDir.toPath().resolve("src/main/java/org/example");
		Files.writeString(srcDir.resolve("Foo.java"), "package org.example; public class Foo { int x; int y; }");
		run("git", "add", ".");
		run("git", "commit", "-m", "modify Foo again");

		CoverageMapMetadata metadata = new CoverageMapMetadata("main", commitId, "2026-04-11T10:00:00Z");
		Map<String, Map<String, List<String>>> mappings = Map.of(
				"FooTest#testA", Map.of(
						"classes", List.of("org.example.Foo"),
						"methods", List.of("org.example.Foo#methodA")),
				"FooTest#testB", Map.of(
						"classes", List.of("org.example.Foo"),
						"methods", List.of("org.example.Foo#methodB"))
		);
		File multiMap = tempDir.resolve("multi-map.json").toFile();
		try (FileWriter w = new FileWriter(multiMap))
		{
			new GsonBuilder().setPrettyPrinting().create().toJson(new CoverageMap(metadata, mappings), w);
		}

		File output = tempDir.resolve("ant-multi.ant").toFile();
		int exitCode = new CommandLine(new SelectTestsCommand()).execute(
				"--map", multiMap.getAbsolutePath(),
				"--project-dir", projectDir.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--format", "ant");

		assertEquals(0, exitCode);
		String content = Files.readString(output.toPath()).trim();
		assertTrue(content.contains("FooTest#"));
		assertTrue(content.contains("+") || content.contains("testA"));
	}

	@Test
	void returnsErrorWhenMapNotFound()
	{
		File output = tempDir.resolve("output.json").toFile();
		int exitCode = new CommandLine(new SelectTestsCommand()).execute(
				"--map", "/nonexistent/map.json",
				"--project-dir", projectDir.getAbsolutePath(),
				"--output", output.getAbsolutePath());

		assertEquals(1, exitCode);
	}

	@Test
	void writesNoneStatusWhenNoChanges() throws Exception
	{
		String commitId = getHeadCommit();
		File noChangeMap = writeMap(commitId);

		File output = tempDir.resolve("none-output.json").toFile();
		int exitCode = new CommandLine(new SelectTestsCommand()).execute(
				"--map", noChangeMap.getAbsolutePath(),
				"--project-dir", projectDir.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--format", "json");

		assertEquals(0, exitCode);
		String json = Files.readString(output.toPath());
		SelectionOutput result = new Gson().fromJson(json, SelectionOutput.class);
		assertEquals("NONE", result.getStatus());
	}

	@Test
	void fullSuiteTriggerForcesFullSuite() throws Exception
	{
		String commitId = getHeadCommit();

		Files.writeString(projectDir.toPath().resolve("build.gradle"), "apply plugin: 'java'");
		run("git", "add", ".");
		run("git", "commit", "-m", "add build file");

		File triggerMap = writeMap(commitId);
		File output = tempDir.resolve("trigger-output.json").toFile();
		int exitCode = new CommandLine(new SelectTestsCommand()).execute(
				"--map", triggerMap.getAbsolutePath(),
				"--project-dir", projectDir.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--format", "json",
				"--full-suite-trigger", "build.gradle");

		assertEquals(0, exitCode);
		String json = Files.readString(output.toPath());
		SelectionOutput result = new Gson().fromJson(json, SelectionOutput.class);
		assertEquals("FULL_SUITE", result.getStatus());
	}


	private int runCommand(String... extraArgs)
	{
		String[] baseArgs = new String[]{
				"--map", mapFile.getAbsolutePath(),
				"--project-dir", projectDir.getAbsolutePath()
		};
		String[] allArgs = new String[baseArgs.length + extraArgs.length];
		System.arraycopy(baseArgs, 0, allArgs, 0, baseArgs.length);
		System.arraycopy(extraArgs, 0, allArgs, baseArgs.length, extraArgs.length);
		return new CommandLine(new SelectTestsCommand()).execute(allArgs);
	}

	private File writeMapWithCoverage(String commitId) throws IOException
	{
		CoverageMapMetadata metadata = new CoverageMapMetadata("main", commitId, "2026-04-11T10:00:00Z");
		Map<String, Map<String, List<String>>> mappings = Map.of(
				"FooTest#testFoo", Map.of(
						"classes", List.of("org.example.Foo"),
						"methods", List.of("org.example.Foo#doSomething")),
				"BarTest#testBar", Map.of(
						"classes", List.of("org.example.Bar"),
						"methods", List.of("org.example.Bar#process"))
		);
		return writeMapToFile(new CoverageMap(metadata, mappings));
	}

	private File writeMap(String commitId) throws IOException
	{
		CoverageMapMetadata metadata = new CoverageMapMetadata("main", commitId, "2026-04-11T10:00:00Z");
		return writeMapToFile(new CoverageMap(metadata, Map.of()));
	}

	private File writeMapToFile(CoverageMap map) throws IOException
	{
		File file = tempDir.resolve("test-map-" + System.nanoTime() + ".json").toFile();
		try (FileWriter writer = new FileWriter(file))
		{
			new GsonBuilder().setPrettyPrinting().create().toJson(map, writer);
		}
		return file;
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
}
