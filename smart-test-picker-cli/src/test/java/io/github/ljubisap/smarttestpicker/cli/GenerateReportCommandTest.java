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

import com.google.gson.GsonBuilder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapMetadata;
import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;


class GenerateReportCommandTest
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
		mapFile = writeMap(commitId);
	}

	@Test
	void generatesHtmlReport() throws IOException
	{
		File selectedFile = writeSelectedTests("SELECTED", "1 test selected",
				List.of("FooTest#testFoo"), Map.of());
		File output = tempDir.resolve("report/index.html").toFile();

		int exitCode = new CommandLine(new GenerateReportCommand()).execute(
				"--map", mapFile.getAbsolutePath(),
				"--selected-tests", selectedFile.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--project-dir", projectDir.getAbsolutePath());

		assertEquals(0, exitCode);
		assertTrue(output.exists());
		String html = Files.readString(output.toPath());
		assertTrue(html.contains("<!DOCTYPE html>"));
		assertTrue(html.contains("Smart Test Picker"));
		assertTrue(html.contains("SELECTED"));
	}

	@Test
	void generatesReportWithNoneStatus() throws IOException
	{
		File selectedFile = writeSelectedTests("NONE", "No production code changes detected",
				List.of(), Map.of());
		File output = tempDir.resolve("report-none/index.html").toFile();

		int exitCode = new CommandLine(new GenerateReportCommand()).execute(
				"--map", mapFile.getAbsolutePath(),
				"--selected-tests", selectedFile.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--project-dir", projectDir.getAbsolutePath());

		assertEquals(0, exitCode);
		String html = Files.readString(output.toPath());
		assertTrue(html.contains("No Changes Detected"));
	}

	@Test
	void generatesReportWithFullSuiteStatus() throws IOException
	{
		File selectedFile = writeSelectedTests("FULL_SUITE", "Coverage map too stale",
				List.of(), Map.of());
		File output = tempDir.resolve("report-full/index.html").toFile();

		int exitCode = new CommandLine(new GenerateReportCommand()).execute(
				"--map", mapFile.getAbsolutePath(),
				"--selected-tests", selectedFile.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--project-dir", projectDir.getAbsolutePath());

		assertEquals(0, exitCode);
		String html = Files.readString(output.toPath());
		assertTrue(html.contains("Full Suite Required"));
	}

	@Test
	void returnsErrorWhenMapNotFound()
	{
		File selectedFile = tempDir.resolve("selected.json").toFile();
		try { writeSelectedTests(selectedFile, "NONE", "test", List.of(), Map.of()); } catch (IOException e) {}

		File output = tempDir.resolve("report.html").toFile();
		int exitCode = new CommandLine(new GenerateReportCommand()).execute(
				"--map", "/nonexistent/map.json",
				"--selected-tests", selectedFile.getAbsolutePath(),
				"--output", output.getAbsolutePath());

		assertEquals(1, exitCode);
	}

	@Test
	void returnsErrorWhenSelectedTestsNotFound()
	{
		File output = tempDir.resolve("report.html").toFile();
		int exitCode = new CommandLine(new GenerateReportCommand()).execute(
				"--map", mapFile.getAbsolutePath(),
				"--selected-tests", "/nonexistent/selected.json",
				"--output", output.getAbsolutePath());

		assertEquals(1, exitCode);
	}


	private File writeMap(String commitId) throws IOException
	{
		CoverageMapMetadata metadata = new CoverageMapMetadata("main", commitId, "2026-04-11T10:00:00Z");
		Map<String, Map<String, List<String>>> mappings = Map.of(
				"FooTest#testFoo", Map.of(
						"classes", List.of("org.example.Foo"),
						"methods", List.of("org.example.Foo#doSomething"))
		);
		File file = tempDir.resolve("coverage-map.json").toFile();
		try (FileWriter writer = new FileWriter(file))
		{
			new GsonBuilder().setPrettyPrinting().create().toJson(new CoverageMap(metadata, mappings), writer);
		}
		return file;
	}

	private File writeSelectedTests(String status, String reason,
			List<String> selected, Map<String, String> unmapped) throws IOException
	{
		File file = tempDir.resolve("selected-tests.json").toFile();
		writeSelectedTests(file, status, reason, selected, unmapped);
		return file;
	}

	private void writeSelectedTests(File file, String status, String reason,
			List<String> selected, Map<String, String> unmapped) throws IOException
	{
		SelectionOutput output = new SelectionOutput(status, reason, selected, unmapped);
		try (FileWriter writer = new FileWriter(file))
		{
			new GsonBuilder().setPrettyPrinting().create().toJson(output, writer);
		}
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
