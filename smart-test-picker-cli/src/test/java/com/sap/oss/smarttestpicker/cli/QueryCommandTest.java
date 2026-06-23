// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;


class QueryCommandTest
{

	@TempDir
	Path tempDir;

	private File mapFile;
	private ByteArrayOutputStream outContent;
	private ByteArrayOutputStream errContent;
	private PrintStream originalOut;
	private PrintStream originalErr;

	@BeforeEach
	void setUp() throws IOException
	{
		mapFile = tempDir.resolve("coverage-map.json").toFile();
		writeFixtureMap(mapFile);

		originalOut = System.out;
		originalErr = System.err;
		outContent = new ByteArrayOutputStream();
		errContent = new ByteArrayOutputStream();
		System.setOut(new PrintStream(outContent));
		System.setErr(new PrintStream(errContent));
	}

	@AfterEach
	void tearDown()
	{
		System.setOut(originalOut);
		System.setErr(originalErr);
	}

	@Test
	void statsShowsTestCount()
	{
		int exitCode = runQuery("--stats");

		assertEquals(0, exitCode);
		String output = outContent.toString();
		assertTrue(output.contains("Total tests:   3"));
		assertTrue(output.contains("Unique classes:"));
		assertTrue(output.contains("Unique methods:"));
	}

	@Test
	void queryByTestFindsExactMatch()
	{
		int exitCode = runQuery("--test", "OwnerControllerTests#testShowOwner");

		assertEquals(0, exitCode);
		String output = outContent.toString();
		assertTrue(output.contains("Test: OwnerControllerTests#testShowOwner"));
		assertTrue(output.contains("org.example.controller.OwnerController"));
		assertTrue(output.contains("org.example.controller.OwnerController#showOwner"));
	}

	@Test
	void queryByTestSuggestsPartialMatch()
	{
		int exitCode = runQuery("--test", "OwnerController");

		assertEquals(1, exitCode);
		String output = outContent.toString();
		assertTrue(output.contains("OwnerControllerTests#testShowOwner"));
	}

	@Test
	void queryByClassFindsTests()
	{
		int exitCode = runQuery("--class", "org.example.controller.OwnerController");

		assertEquals(0, exitCode);
		String output = outContent.toString();
		assertTrue(output.contains("OwnerControllerTests#testShowOwner"));
		assertFalse(output.contains("PetControllerTests"));
	}

	@Test
	void queryByMethodFindsTests()
	{
		int exitCode = runQuery("--method", "org.example.controller.PetController#createPet");

		assertEquals(0, exitCode);
		String output = outContent.toString();
		assertTrue(output.contains("PetControllerTests#testCreatePet"));
		assertFalse(output.contains("OwnerControllerTests"));
	}

	@Test
	void grepMatchesAcrossCategories()
	{
		int exitCode = runQuery("--grep", "Owner");

		assertEquals(0, exitCode);
		String output = outContent.toString();
		assertTrue(output.contains("Matching tests"));
		assertTrue(output.contains("OwnerControllerTests#testShowOwner"));
		assertTrue(output.contains("Matching classes"));
		assertTrue(output.contains("org.example.controller.OwnerController"));
	}

	@Test
	void returnsErrorForNonexistentMap()
	{
		File missing = new File(tempDir.toFile(), "nope.json");
		int exitCode = new CommandLine(new QueryCommand()).execute(
				"--map", missing.getAbsolutePath(), "--stats");

		assertEquals(1, exitCode);
	}

	@Test
	void queryByClassSuggestsPartialMatch()
	{
		int exitCode = runQuery("--class", "OwnerController");

		assertEquals(1, exitCode);
		String output = outContent.toString();
		assertTrue(output.contains("org.example.controller.OwnerController"));
	}


	private int runQuery(String... args)
	{
		String[] fullArgs = new String[args.length + 2];
		fullArgs[0] = "--map";
		fullArgs[1] = mapFile.getAbsolutePath();
		System.arraycopy(args, 0, fullArgs, 2, args.length);
		return new CommandLine(new QueryCommand()).execute(fullArgs);
	}

	private void writeFixtureMap(File file) throws IOException
	{
		String json = "{\n"
				+ "  \"metadata\": { \"baseBranch\": \"main\", \"commitId\": \"abc1234\", \"timestamp\": \"2026-04-11T10:00:00Z\" },\n"
				+ "  \"testMappings\": {\n"
				+ "    \"OwnerControllerTests#testShowOwner\": {\n"
				+ "      \"classes\": [\"org.example.controller.OwnerController\", \"org.example.model.Owner\"],\n"
				+ "      \"methods\": [\"org.example.controller.OwnerController#showOwner\", \"org.example.model.Owner#getName\"]\n"
				+ "    },\n"
				+ "    \"PetControllerTests#testCreatePet\": {\n"
				+ "      \"classes\": [\"org.example.controller.PetController\", \"org.example.model.Pet\"],\n"
				+ "      \"methods\": [\"org.example.controller.PetController#createPet\", \"org.example.model.Pet#setName\"]\n"
				+ "    },\n"
				+ "    \"VetControllerTests#testShowVets\": {\n"
				+ "      \"classes\": [\"org.example.controller.VetController\", \"org.example.model.Vet\"],\n"
				+ "      \"methods\": [\"org.example.controller.VetController#showVetList\", \"org.example.model.Vet#getSpecialties\"]\n"
				+ "    }\n"
				+ "  }\n"
				+ "}";
		try (FileWriter writer = new FileWriter(file))
		{
			writer.write(json);
		}
	}
}
