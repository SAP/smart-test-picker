// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.cli;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;


class RefreshedReportCommandTest
{

	@TempDir
	Path tempDir;

	private ByteArrayOutputStream errContent;
	private PrintStream originalErr;

	@BeforeEach
	void setUp()
	{
		originalErr = System.err;
		errContent = new ByteArrayOutputStream();
		System.setErr(new PrintStream(errContent));
	}

	@AfterEach
	void tearDown()
	{
		System.setErr(originalErr);
	}

	@Test
	void returnsErrorWhenMapFileMissing()
	{
		int exitCode = new CommandLine(new RefreshedReportCommand()).execute(
				"--map", "/nonexistent/map.json",
				"--platform-home", tempDir.toFile().getAbsolutePath());

		assertEquals(1, exitCode);
		assertTrue(errContent.toString().contains("Coverage map not found"));
	}

	@Test
	void returnsErrorWhenPlatformHomeInvalid() throws IOException
	{
		File mapFile = tempDir.resolve("map.json").toFile();
		try (FileWriter w = new FileWriter(mapFile))
		{
			w.write("{}");
		}

		int exitCode = new CommandLine(new RefreshedReportCommand()).execute(
				"--map", mapFile.getAbsolutePath(),
				"--platform-home", "/nonexistent/platform");

		assertEquals(1, exitCode);
		assertTrue(errContent.toString().contains("Platform home not found"));
	}

	@Test
	void formatForAnt_groupsByClassWithMethods()
	{
		SelectionOutput selection = new SelectionOutput(
				"SELECTED", "3 tests", List.of("A#m1", "A#m2", "B#m3"), Map.of());

		RefreshedReportCommand cmd = new RefreshedReportCommand();
		String result = cmd.formatForAnt(selection);

		assertEquals("A#m1+m2,B#m3", result);
	}

	@Test
	void formatForAnt_classWithoutMethods()
	{
		SelectionOutput selection = new SelectionOutput(
				"SELECTED", "1 test", List.of("A"), Map.of());

		RefreshedReportCommand cmd = new RefreshedReportCommand();
		String result = cmd.formatForAnt(selection);

		assertEquals("A", result);
	}

	@Test
	void formatForAnt_mixedClassAndMethodTests()
	{
		SelectionOutput selection = new SelectionOutput(
				"SELECTED", "3 tests", List.of("A#m1", "B", "A#m2"), Map.of());

		RefreshedReportCommand cmd = new RefreshedReportCommand();
		String result = cmd.formatForAnt(selection);

		assertEquals("A#m1+m2,B", result);
	}
}
