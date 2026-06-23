// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.mapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests for {@link CoverageMapperJaxb} — verifies that per-test JaCoCo XML reports
 * are correctly parsed into a test-to-coverage mapping (classes and methods).
 */
class CoverageMapperJaxbTest
{

	@Test
	void parsesXmlAndGeneratesCorrectMapping()
	{
		File reportsDir = new File(getClass().getClassLoader().getResource("session_MyTest#testSomething.xml").getFile())
				.getParentFile();

		CoverageMapperJaxb mapper = new CoverageMapperJaxb(reportsDir);
		Map<String, Map<String, List<String>>> result = mapper.generateTestCoverageMapping();

		assertEquals(1, result.size());
		assertTrue(result.containsKey("MyTest#testSomething"));

		Map<String, List<String>> coverage = result.get("MyTest#testSomething");
		List<String> classes = coverage.get("classes");
		List<String> methods = coverage.get("methods");

		// Both classes should be covered
		assertTrue(classes.contains("org.example.service.UserService"));
		assertTrue(classes.contains("org.example.repository.UserRepository"));

		// Covered methods
		assertTrue(methods.contains("org.example.service.UserService#updateAddress"));
		assertTrue(methods.contains("org.example.repository.UserRepository#save"));
		assertTrue(methods.contains("org.example.repository.UserRepository#findById"));

		// Uncovered method should NOT be in the map
		assertFalse(methods.contains("org.example.service.UserService#deleteUser"));
	}

	@Test
	void emptyDirectoryReturnsEmptyMap(@TempDir Path tempDir)
	{
		CoverageMapperJaxb mapper = new CoverageMapperJaxb(tempDir.toFile());
		Map<String, Map<String, List<String>>> result = mapper.generateTestCoverageMapping();

		assertTrue(result.isEmpty());
	}

	@Test
	void emptyXmlFileIsSkipped(@TempDir Path tempDir) throws IOException
	{
		Files.createFile(tempDir.resolve("session_Empty#test.xml"));

		CoverageMapperJaxb mapper = new CoverageMapperJaxb(tempDir.toFile());
		Map<String, Map<String, List<String>>> result = mapper.generateTestCoverageMapping();

		assertTrue(result.isEmpty());
	}

	@Test
	void nonSessionFilesAreIgnored(@TempDir Path tempDir) throws IOException
	{
		Files.writeString(tempDir.resolve("other_report.xml"), "<report/>");

		CoverageMapperJaxb mapper = new CoverageMapperJaxb(tempDir.toFile());
		Map<String, Map<String, List<String>>> result = mapper.generateTestCoverageMapping();

		assertTrue(result.isEmpty());
	}
}
