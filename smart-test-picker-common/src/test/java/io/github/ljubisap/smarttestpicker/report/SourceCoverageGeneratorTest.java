// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.report;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;


class SourceCoverageGeneratorTest
{

	@TempDir
	Path tempDir;

	@Test
	void findsSessionPrefixedXmlFile() throws IOException
	{
		File reportsDir = tempDir.resolve("xml").toFile();
		reportsDir.mkdirs();
		File sourceDir = tempDir.resolve("src").toFile();
		File outputDir = tempDir.resolve("out").toFile();

		writeXml(new File(reportsDir, "session_MyTest#testFoo.xml"), "com/example", "Foo.java");
		writeSourceFile(sourceDir, "com/example/Foo.java");

		Set<String> selected = new LinkedHashSet<>(List.of("MyTest#testFoo"));
		Map<String, Map<String, List<String>>> mappings = Map.of(
				"MyTest#testFoo", Map.of("classes", List.of("com.example.Foo"), "methods", List.of()));

		SourceCoverageGenerator gen = new SourceCoverageGenerator();
		SourceCoverageGenerator.SourceGenerationResult result =
				gen.generateSourcePages(reportsDir, sourceDir, outputDir, selected, mappings);

		assertFalse(result.getSourceLinks().isEmpty(), "Should find session_-prefixed XML");
		assertTrue(result.getSourceLinks().containsKey("com.example.Foo"));
	}

	@Test
	void fallsBackToBareName() throws IOException
	{
		File reportsDir = tempDir.resolve("xml").toFile();
		reportsDir.mkdirs();
		File sourceDir = tempDir.resolve("src").toFile();
		File outputDir = tempDir.resolve("out").toFile();

		writeXml(new File(reportsDir, "MyTest#testFoo.xml"), "com/example", "Foo.java");
		writeSourceFile(sourceDir, "com/example/Foo.java");

		Set<String> selected = new LinkedHashSet<>(List.of("MyTest#testFoo"));
		Map<String, Map<String, List<String>>> mappings = Map.of(
				"MyTest#testFoo", Map.of("classes", List.of("com.example.Foo"), "methods", List.of()));

		SourceCoverageGenerator gen = new SourceCoverageGenerator();
		SourceCoverageGenerator.SourceGenerationResult result =
				gen.generateSourcePages(reportsDir, sourceDir, outputDir, selected, mappings);

		assertFalse(result.getSourceLinks().isEmpty(), "Should fall back to bare name XML");
		assertTrue(result.getSourceLinks().containsKey("com.example.Foo"));
	}

	@Test
	void multiSourceDir_findsFileInSecondDir() throws IOException
	{
		File reportsDir = tempDir.resolve("xml").toFile();
		reportsDir.mkdirs();
		File sourceDir1 = tempDir.resolve("src1").toFile();
		File sourceDir2 = tempDir.resolve("src2").toFile();
		File outputDir = tempDir.resolve("out").toFile();

		writeXml(new File(reportsDir, "session_MyTest#testFoo.xml"), "com/example", "Foo.java");
		// Source file only in second directory
		writeSourceFile(sourceDir2, "com/example/Foo.java");

		Set<String> selected = new LinkedHashSet<>(List.of("MyTest#testFoo"));
		Map<String, Map<String, List<String>>> mappings = Map.of(
				"MyTest#testFoo", Map.of("classes", List.of("com.example.Foo"), "methods", List.of()));

		SourceCoverageGenerator gen = new SourceCoverageGenerator();
		SourceCoverageGenerator.SourceGenerationResult result =
				gen.generateSourcePages(reportsDir, List.of(sourceDir1, sourceDir2),
						outputDir, selected, mappings);

		assertFalse(result.getSourceLinks().isEmpty(), "Should find source in second dir");
	}

	@Test
	void returnsEmptyWhenNoReportsDir()
	{
		File sourceDir = tempDir.resolve("src").toFile();
		sourceDir.mkdirs();

		SourceCoverageGenerator gen = new SourceCoverageGenerator();
		SourceCoverageGenerator.SourceGenerationResult result =
				gen.generateSourcePages(null, sourceDir, tempDir.resolve("out").toFile(),
						Set.of("Test#t"), Map.of());

		assertTrue(result.getSourceLinks().isEmpty());
		assertTrue(result.getCoveragePercentages().isEmpty());
	}

	@Test
	void returnsEmptyWhenNoSourceDirs()
	{
		File reportsDir = tempDir.resolve("xml").toFile();
		reportsDir.mkdirs();

		SourceCoverageGenerator gen = new SourceCoverageGenerator();
		SourceCoverageGenerator.SourceGenerationResult result =
				gen.generateSourcePages(reportsDir, List.of(), tempDir.resolve("out").toFile(),
						Set.of("Test#t"), Map.of());

		assertTrue(result.getSourceLinks().isEmpty());
	}

	@Test
	void computesCoveragePercentage() throws IOException
	{
		File reportsDir = tempDir.resolve("xml").toFile();
		reportsDir.mkdirs();
		File sourceDir = tempDir.resolve("src").toFile();
		File outputDir = tempDir.resolve("out").toFile();

		// 2 lines covered (ci>0), 1 line missed (mi>0, ci=0) → 66.7%
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<report name=\"test\">\n"
				+ "  <package name=\"com/example\">\n"
				+ "    <sourcefile name=\"Foo.java\">\n"
				+ "      <line nr=\"10\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>\n"
				+ "      <line nr=\"11\" mi=\"0\" ci=\"2\" mb=\"0\" cb=\"0\"/>\n"
				+ "      <line nr=\"12\" mi=\"5\" ci=\"0\" mb=\"0\" cb=\"0\"/>\n"
				+ "    </sourcefile>\n"
				+ "  </package>\n"
				+ "</report>\n";
		writeFile(new File(reportsDir, "session_MyTest#testFoo.xml"), xml);
		writeSourceFile(sourceDir, "com/example/Foo.java");

		Set<String> selected = new LinkedHashSet<>(List.of("MyTest#testFoo"));
		Map<String, Map<String, List<String>>> mappings = Map.of(
				"MyTest#testFoo", Map.of("classes", List.of("com.example.Foo"), "methods", List.of()));

		SourceCoverageGenerator gen = new SourceCoverageGenerator();
		SourceCoverageGenerator.SourceGenerationResult result =
				gen.generateSourcePages(reportsDir, sourceDir, outputDir, selected, mappings);

		assertTrue(result.getCoveragePercentages().containsKey("com.example.Foo"));
		double pct = result.getCoveragePercentages().get("com.example.Foo");
		assertEquals(66.6, pct, 0.5, "2 of 3 lines covered = ~66.7%");
	}

	@Test
	void mergesLineCoverageAcrossTests() throws IOException
	{
		File reportsDir = tempDir.resolve("xml").toFile();
		reportsDir.mkdirs();
		File sourceDir = tempDir.resolve("src").toFile();
		File outputDir = tempDir.resolve("out").toFile();

		// Test1 covers line 10, misses line 11
		String xml1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<report name=\"test\">\n"
				+ "  <package name=\"com/example\">\n"
				+ "    <sourcefile name=\"Foo.java\">\n"
				+ "      <line nr=\"10\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>\n"
				+ "      <line nr=\"11\" mi=\"5\" ci=\"0\" mb=\"0\" cb=\"0\"/>\n"
				+ "    </sourcefile>\n"
				+ "  </package>\n"
				+ "</report>\n";
		writeFile(new File(reportsDir, "session_Test1#test.xml"), xml1);

		// Test2 misses line 10, covers line 11
		String xml2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<report name=\"test\">\n"
				+ "  <package name=\"com/example\">\n"
				+ "    <sourcefile name=\"Foo.java\">\n"
				+ "      <line nr=\"10\" mi=\"3\" ci=\"0\" mb=\"0\" cb=\"0\"/>\n"
				+ "      <line nr=\"11\" mi=\"0\" ci=\"4\" mb=\"0\" cb=\"0\"/>\n"
				+ "    </sourcefile>\n"
				+ "  </package>\n"
				+ "</report>\n";
		writeFile(new File(reportsDir, "session_Test2#test.xml"), xml2);

		writeSourceFile(sourceDir, "com/example/Foo.java");

		Set<String> selected = new LinkedHashSet<>(List.of("Test1#test", "Test2#test"));
		Map<String, Map<String, List<String>>> mappings = new LinkedHashMap<>();
		mappings.put("Test1#test", Map.of("classes", List.of("com.example.Foo"), "methods", List.of()));
		mappings.put("Test2#test", Map.of("classes", List.of("com.example.Foo"), "methods", List.of()));

		SourceCoverageGenerator gen = new SourceCoverageGenerator();
		SourceCoverageGenerator.SourceGenerationResult result =
				gen.generateSourcePages(reportsDir, sourceDir, outputDir, selected, mappings);

		// After merge: both lines should show as covered → 100%
		assertTrue(result.getCoveragePercentages().containsKey("com.example.Foo"));
		double pct = result.getCoveragePercentages().get("com.example.Foo");
		assertEquals(100.0, pct, 0.1, "Merged coverage: both lines covered by at least one test");
	}

	private void writeXml(File file, String packageName, String sourceFileName) throws IOException
	{
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<report name=\"test\">\n"
				+ "  <package name=\"" + packageName + "\">\n"
				+ "    <sourcefile name=\"" + sourceFileName + "\">\n"
				+ "      <line nr=\"10\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>\n"
				+ "    </sourcefile>\n"
				+ "  </package>\n"
				+ "</report>\n";
		writeFile(file, xml);
	}

	private void writeSourceFile(File sourceDir, String relativePath) throws IOException
	{
		File sourceFile = new File(sourceDir, relativePath);
		sourceFile.getParentFile().mkdirs();
		try (FileWriter writer = new FileWriter(sourceFile))
		{
			for (int i = 1; i <= 20; i++)
			{
				writer.write("// line " + i + "\n");
			}
		}
	}

	private void writeFile(File file, String content) throws IOException
	{
		file.getParentFile().mkdirs();
		try (FileWriter writer = new FileWriter(file))
		{
			writer.write(content);
		}
	}
}
