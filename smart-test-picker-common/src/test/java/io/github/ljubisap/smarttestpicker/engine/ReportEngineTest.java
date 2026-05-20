// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;

import static org.junit.jupiter.api.Assertions.*;


class ReportEngineTest
{

	@TempDir
	Path tempDir;

	private File projectDir;
	private EngineLogger logger;
	private ReportEngine engine;

	@BeforeEach
	void setUp() throws Exception
	{
		projectDir = tempDir.resolve("project").toFile();
		projectDir.mkdirs();

		run("git", "init");
		run("git", "config", "user.email", "test@test.com");
		run("git", "config", "user.name", "Test");
		Files.writeString(projectDir.toPath().resolve("dummy.txt"), "init");
		run("git", "add", ".");
		run("git", "commit", "-m", "initial");

		logger = new NoOpLogger();
		engine = new ReportEngine();
	}

	@Test
	void generatesHtmlFromValidInputs() throws Exception
	{
		String commitId = getHeadCommit();
		File mapFile = writeCoverageMap(commitId);
		File selectedFile = writeSelectedTests("SELECTED", "1 test selected", List.of("FooTest#testFoo"));
		File outputFile = tempDir.resolve("report/index.html").toFile();

		engine.generate(mapFile, selectedFile, outputFile, (File) null, (File) null,
				projectDir, 500, false, logger);

		assertTrue(outputFile.exists());
		String html = Files.readString(outputFile.toPath());
		assertTrue(html.contains("<html"));
		assertTrue(html.contains("Smart Test Picker"));
		assertTrue(html.contains("FooTest"));
	}

	@Test
	void handlesEmptyCoverageMapGracefully() throws IOException
	{
		File mapFile = tempDir.resolve("bad-map.json").toFile();
		Files.writeString(mapFile.toPath(), "{\"metadata\": null, \"testMappings\": null}");
		File selectedFile = writeSelectedTests("SELECTED", "reason", List.of("Test#m"));
		File outputFile = tempDir.resolve("report2/index.html").toFile();

		engine.generate(mapFile, selectedFile, outputFile, (File) null, (File) null,
				projectDir, 500, false, logger);

		assertTrue(outputFile.exists());
		String html = Files.readString(outputFile.toPath());
		assertTrue(html.contains("<html"));
	}

	@Test
	void handlesMissingSelectedTestsGracefully() throws Exception
	{
		String commitId = getHeadCommit();
		File mapFile = writeCoverageMap(commitId);
		File missingFile = tempDir.resolve("nonexistent-selected.json").toFile();
		File outputFile = tempDir.resolve("report3/index.html").toFile();

		engine.generate(mapFile, missingFile, outputFile, (File) null, (File) null,
				projectDir, 500, false, logger);

		assertTrue(outputFile.exists());
	}

	@Test
	void chunkModeActivatesForLargeMaps() throws Exception
	{
		String commitId = getHeadCommit();
		File mapFile = writeLargeCoverageMap(commitId, 60);
		File selectedFile = writeSelectedTests("SELECTED", "60 tests", List.of("Test00#m"));
		File outputFile = tempDir.resolve("report4/index.html").toFile();

		engine.generate(mapFile, selectedFile, outputFile, (File) null, (File) null,
				projectDir, 500, false, logger);

		assertTrue(outputFile.exists());
		File dataDir = new File(outputFile.getParentFile(), "data");
		assertTrue(dataDir.exists(), "data/ directory should be created for large maps");
		assertTrue(new File(dataDir, "chunk-0.js").exists());
		assertTrue(new File(dataDir, "search-index.js").exists());
	}

	private File writeCoverageMap(String commitId) throws IOException
	{
		String json = """
				{
				  "metadata": {"baseBranch": "main", "commitId": "%s", "timestamp": "2026-01-01T00:00:00Z"},
				  "testMappings": {
				    "FooTest#testFoo": {"classes": ["org.example.Foo"], "methods": ["org.example.Foo#doIt"]}
				  }
				}
				""".formatted(commitId);
		File file = tempDir.resolve("test-coverage-map.json").toFile();
		Files.writeString(file.toPath(), json);
		return file;
	}

	private File writeLargeCoverageMap(String commitId, int testCount) throws IOException
	{
		StringBuilder sb = new StringBuilder();
		sb.append("{\"metadata\":{\"baseBranch\":\"main\",\"commitId\":\"").append(commitId)
				.append("\",\"timestamp\":\"2026-01-01T00:00:00Z\"},\"testMappings\":{");
		for (int i = 0; i < testCount; i++)
		{
			if (i > 0) sb.append(",");
			sb.append("\"Test").append(String.format("%02d", i)).append("#m\":")
					.append("{\"classes\":[\"org.example.C").append(i).append("\"],")
					.append("\"methods\":[\"org.example.C").append(i).append("#run\"]}");
		}
		sb.append("}}");
		File file = tempDir.resolve("large-coverage-map.json").toFile();
		Files.writeString(file.toPath(), sb.toString());
		return file;
	}

	private File writeSelectedTests(String status, String reason, List<String> tests) throws IOException
	{
		SelectionOutput output = new SelectionOutput(status, reason, tests, java.util.Map.of());
		String json = new GsonBuilder().setPrettyPrinting().create().toJson(output);
		File file = tempDir.resolve("selected-tests.json").toFile();
		Files.writeString(file.toPath(), json);
		return file;
	}

	private String getHeadCommit() throws IOException, InterruptedException
	{
		ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
		pb.directory(projectDir);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String output = new String(p.getInputStream().readAllBytes()).trim();
		p.waitFor();
		return output;
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
