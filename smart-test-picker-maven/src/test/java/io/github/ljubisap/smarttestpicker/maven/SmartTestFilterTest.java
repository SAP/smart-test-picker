// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;

import static org.junit.jupiter.api.Assertions.*;


class SmartTestFilterTest
{

	@TempDir
	Path tempDir;

	@Test
	void writesDefaultPatternsForFullSuite() throws IOException
	{
		File includesFile = tempDir.resolve("target/includes.txt").toFile();
		SelectionOutput output = new SelectionOutput();
		output.setStatus("FULL_SUITE");

		SmartTestFilter.writeIncludesFile(output, includesFile, false);

		assertTrue(includesFile.exists());
		List<String> lines = Files.readAllLines(includesFile.toPath());
		assertTrue(lines.contains("**/*Test.java"));
		assertTrue(lines.contains("**/*Tests.java"));
		assertTrue(lines.contains("**/*TestCase.java"));
	}

	@Test
	void writesDefaultPatternsForNullOutput() throws IOException
	{
		File includesFile = tempDir.resolve("target/includes.txt").toFile();

		SmartTestFilter.writeIncludesFile(null, includesFile, false);

		assertTrue(includesFile.exists());
		List<String> lines = Files.readAllLines(includesFile.toPath());
		assertTrue(lines.contains("**/*Test.java"));
	}

	@Test
	void writesSentinelForNoneWithoutUnmapped() throws IOException
	{
		File includesFile = tempDir.resolve("target/includes.txt").toFile();
		SelectionOutput output = new SelectionOutput();
		output.setStatus("NONE");

		SmartTestFilter.writeIncludesFile(output, includesFile, false);

		List<String> lines = Files.readAllLines(includesFile.toPath());
		assertEquals(1, lines.size());
		assertEquals("__no_tests_to_run__", lines.get(0));
	}

	@Test
	void writesUnmappedFqnPathsForNoneWithUnmapped() throws IOException
	{
		File includesFile = tempDir.resolve("target/includes.txt").toFile();
		SelectionOutput output = new SelectionOutput();
		output.setStatus("NONE");

		Map<String, String> unmapped = new LinkedHashMap<>();
		unmapped.put("com.example.NewTest", "Not in coverage map");
		output.setUnmappedTests(unmapped);

		SmartTestFilter.writeIncludesFile(output, includesFile, false);

		List<String> lines = Files.readAllLines(includesFile.toPath());
		assertTrue(lines.contains("com/example/NewTest.java"));
	}

	@Test
	void writesSelectedClassPatterns() throws IOException
	{
		File includesFile = tempDir.resolve("target/includes.txt").toFile();
		SelectionOutput output = new SelectionOutput();
		output.setStatus("SELECTED");
		output.setSelectedTests(List.of(
				"com.example.FooTest#testA",
				"com.example.FooTest#testB",
				"com.example.BarTest#testC"));

		SmartTestFilter.writeIncludesFile(output, includesFile, false);

		List<String> lines = Files.readAllLines(includesFile.toPath());
		assertTrue(lines.contains("com.example.FooTest"));
		assertTrue(lines.contains("com.example.BarTest"));
		assertEquals(2, lines.size(), "Duplicate classes should be deduplicated");
	}

	@Test
	void includesUnmappedTestsInSelected() throws IOException
	{
		File includesFile = tempDir.resolve("target/includes.txt").toFile();
		SelectionOutput output = new SelectionOutput();
		output.setStatus("SELECTED");
		output.setSelectedTests(List.of("com.example.FooTest#testA"));

		Map<String, String> unmapped = new LinkedHashMap<>();
		unmapped.put("com.example.NewTest", "Not in coverage map");
		output.setUnmappedTests(unmapped);

		SmartTestFilter.writeIncludesFile(output, includesFile, false);

		List<String> lines = Files.readAllLines(includesFile.toPath());
		assertTrue(lines.contains("com.example.FooTest"));
		assertTrue(lines.contains("com/example/NewTest.java"));
	}
}
