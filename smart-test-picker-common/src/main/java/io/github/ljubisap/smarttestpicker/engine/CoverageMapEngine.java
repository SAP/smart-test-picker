// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.ljubisap.smarttestpicker.change.GitChangeDetector;
import io.github.ljubisap.smarttestpicker.mapper.ClassCoverageMetrics;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapMetadata;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapperJaxb;
import io.github.ljubisap.smarttestpicker.mapper.IndexedCoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.TestClassFilter;


/**
 * Generates the JSON coverage map from per-test JaCoCo XML reports.
 *
 * <p>Orchestrates the full XML-to-JSON pipeline:</p>
 * <ol>
 *   <li>Parses per-test XML reports via {@link CoverageMapperJaxb}</li>
 *   <li>Collects git metadata (commitId, baseBranch, timestamp)</li>
 *   <li>Writes the coverage map in one of three formats:
 *       plain JSON, indexed JSON, or gzip-compressed</li>
 * </ol>
 *
 * <p>Extracted from the Gradle {@code GenerateTestCoverageJsonTask} to be reusable
 * across Gradle, Maven, and CLI.</p>
 *
 * @see io.github.ljubisap.smarttestpicker.mapper.CoverageMap
 * @see io.github.ljubisap.smarttestpicker.mapper.IndexedCoverageMap
 */
public class CoverageMapEngine
{

	/** Convenience overload that writes plain (non-indexed, non-compressed) JSON. */
	public void generate(File reportsDir, File outputFile, String baseBranch,
			File projectDir, EngineLogger logger) throws IOException
	{
		generate(reportsDir, outputFile, baseBranch, projectDir, logger, false, false);
	}

	/**
	 * Parses XML reports, builds the test-to-coverage mapping, and writes JSON output.
	 *
	 * @param reportsDir directory containing per-test JaCoCo XML reports
	 * @param outputFile output JSON file path ({@code .gz} extension triggers gzip)
	 * @param baseBranch base branch name for metadata
	 * @param projectDir project root directory (for git commands)
	 * @param logger     engine logger
	 * @param indexed    if true, use indexed format with integer references
	 * @param gzip       if true, compress output with gzip
	 */
	public void generate(File reportsDir, File outputFile, String baseBranch,
			File projectDir, EngineLogger logger, boolean indexed, boolean gzip) throws IOException
	{
		CoverageMapperJaxb mapper = new CoverageMapperJaxb(reportsDir);
		Map<String, Map<String, List<String>>> testCoverage = mapper.generateTestCoverageMapping();
		Map<String, ClassCoverageMetrics> classMetrics = mapper.getClassMetrics();
		classMetrics.keySet().removeIf(TestClassFilter::isTestClass);

		GitChangeDetector git = new GitChangeDetector(projectDir);
		String commitId = git.getHeadCommitId();

		CoverageMapMetadata metadata = new CoverageMapMetadata(baseBranch, commitId, Instant.now().toString());

		File actualOutput = gzip && !outputFile.getName().endsWith(".gz")
				? new File(outputFile.getAbsolutePath() + ".gz")
				: outputFile;

		if (indexed)
		{
			writeIndexed(metadata, testCoverage, classMetrics, actualOutput, gzip, logger);
		}
		else
		{
			writePlain(metadata, testCoverage, classMetrics, actualOutput, gzip, logger);
		}

		logger.info("Generated test coverage map: {} ({} tests, commitId: {})",
				actualOutput.getAbsolutePath(), testCoverage.size(), commitId);
	}

	private void writePlain(CoverageMapMetadata metadata, Map<String, Map<String, List<String>>> testCoverage,
			Map<String, ClassCoverageMetrics> classMetrics,
			File outputFile, boolean gzip, EngineLogger logger) throws IOException
	{
		CoverageMap coverageMap = new CoverageMap(metadata, testCoverage);
		coverageMap.setClassMetrics(classMetrics);
		Gson gson = new GsonBuilder().setPrettyPrinting().create();

		try (Writer writer = createWriter(outputFile, gzip))
		{
			gson.toJson(coverageMap, writer);
		}
	}

	private void writeIndexed(CoverageMapMetadata metadata, Map<String, Map<String, List<String>>> testCoverage,
			Map<String, ClassCoverageMetrics> classMetrics,
			File outputFile, boolean gzip, EngineLogger logger) throws IOException
	{
		// Phase 1: Collect all unique class and method FQNs across all tests.
		// TreeSet gives sorted order for deterministic output.
		TreeSet<String> allClasses = new TreeSet<>();
		TreeSet<String> allMethods = new TreeSet<>();

		for (Map<String, List<String>> coverage : testCoverage.values())
		{
			List<String> classes = coverage.get("classes");
			if (classes != null)
			{
				allClasses.addAll(classes);
			}
			List<String> methods = coverage.get("methods");
			if (methods != null)
			{
				allMethods.addAll(methods);
			}
		}

		// Phase 2: Build position-based lookup maps (FQN string -> index in the array).
		List<String> classIndex = new ArrayList<>(allClasses);
		List<String> methodIndex = new ArrayList<>(allMethods);

		Map<String, Integer> classLookup = new HashMap<>();
		for (int i = 0; i < classIndex.size(); i++)
		{
			classLookup.put(classIndex.get(i), i);
		}
		Map<String, Integer> methodLookup = new HashMap<>();
		for (int i = 0; i < methodIndex.size(); i++)
		{
			methodLookup.put(methodIndex.get(i), i);
		}

		// Phase 3: Convert each test's string lists to integer reference lists.
		Map<String, IndexedCoverageMap.TestCoverageRef> indexedMappings = new HashMap<>();
		for (Map.Entry<String, Map<String, List<String>>> entry : testCoverage.entrySet())
		{
			Map<String, List<String>> coverage = entry.getValue();

			List<Integer> classRefs = new ArrayList<>();
			List<String> classes = coverage.get("classes");
			if (classes != null)
			{
				for (String cls : classes)
				{
					classRefs.add(classLookup.get(cls));
				}
			}

			List<Integer> methodRefs = new ArrayList<>();
			List<String> methods = coverage.get("methods");
			if (methods != null)
			{
				for (String method : methods)
				{
					methodRefs.add(methodLookup.get(method));
				}
			}

			indexedMappings.put(entry.getKey(), new IndexedCoverageMap.TestCoverageRef(classRefs, methodRefs));
		}

		IndexedCoverageMap indexedMap = new IndexedCoverageMap(metadata, classIndex, methodIndex, indexedMappings);
		indexedMap.setClassMetrics(classMetrics);

		logger.info("Index sizes: {} unique classes, {} unique methods", classIndex.size(), methodIndex.size());

		Gson gson = new Gson();
		try (Writer writer = createWriter(outputFile, gzip))
		{
			gson.toJson(indexedMap, writer);
		}
	}

	private Writer createWriter(File file, boolean gzip) throws IOException
	{
		file.getParentFile().mkdirs();
		if (gzip || file.getName().endsWith(".gz"))
		{
			return new OutputStreamWriter(
					new GZIPOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8);
		}
		return new FileWriter(file);
	}
}
