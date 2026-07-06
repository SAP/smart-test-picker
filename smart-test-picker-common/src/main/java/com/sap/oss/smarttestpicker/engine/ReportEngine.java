// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.engine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;

import com.sap.oss.smarttestpicker.change.GitChangeDetector;
import com.sap.oss.smarttestpicker.mapper.CoverageMap;
import com.sap.oss.smarttestpicker.mapper.CoverageMapMetadata;
import com.sap.oss.smarttestpicker.mapper.CoverageMapReader;
import com.sap.oss.smarttestpicker.report.HtmlReportGenerator;
import com.sap.oss.smarttestpicker.report.ChunkDataWriter;
import com.sap.oss.smarttestpicker.report.ReportData;
import com.sap.oss.smarttestpicker.report.SourceCoverageGenerator;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;


/**
 * Generates the HTML dashboard report of test selection results.
 *
 * <p>Extracted from the Gradle {@code GenerateTestReportTask} to be reusable
 * across both Gradle and Maven plugins.</p>
 */
public class ReportEngine
{

	/**
	 * Generates the HTML report from coverage map and selection data.
	 *
	 * @param coverageMapFile    the coverage map JSON file
	 * @param selectedTestsFile  the selected-tests.json file
	 * @param outputFile         output HTML report file
	 * @param jacocoXmlDir       directory with per-test JaCoCo XML reports (nullable)
	 * @param sourceDir          Java source directory (nullable)
	 * @param projectDir         project root directory (for git commands)
	 * @param maxCommitDistance   max commit distance (for display)
	 * @param classLevelSelection whether class-level selection is enabled
	 * @param logger             engine logger
	 */
	public void generate(File coverageMapFile, File selectedTestsFile, File outputFile,
			File jacocoXmlDir, File sourceDir, File projectDir,
			int maxCommitDistance, boolean classLevelSelection,
			EngineLogger logger) throws IOException
	{
		List<File> sourceDirs = sourceDir != null ? List.of(sourceDir) : List.of();
		generate(coverageMapFile, selectedTestsFile, outputFile,
				jacocoXmlDir, sourceDirs, projectDir,
				maxCommitDistance, classLevelSelection, logger);
	}

	/**
	 * Generates the HTML report, searching multiple source directories for source files.
	 */
	public void generate(File coverageMapFile, File selectedTestsFile, File outputFile,
			File jacocoXmlDir, List<File> sourceDirs, File projectDir,
			int maxCommitDistance, boolean classLevelSelection,
			EngineLogger logger) throws IOException
	{
		// Parse coverage map
		CoverageMap coverageMap = parseCoverageMap(coverageMapFile, logger);

		// Parse selected-tests.json
		ReportData data = new ReportData();
		parseSelectedTests(selectedTestsFile, data, logger);
		data.setClassLevelSelection(classLevelSelection);

		// Populate metadata from coverage map
		if (coverageMap != null && coverageMap.getMetadata() != null)
		{
			CoverageMapMetadata metadata = coverageMap.getMetadata();
			data.setBaseBranch(metadata.getBaseBranch());
			data.setCommitId(metadata.getCommitId());
			data.setTimestamp(metadata.getTimestamp());
		}

		// Populate test mappings
		if (coverageMap != null && coverageMap.getTestMappings() != null)
		{
			data.setTestMappings(coverageMap.getTestMappings());
		}

		if (coverageMap != null && coverageMap.getClassMetrics() != null)
		{
			data.setClassMetrics(coverageMap.getClassMetrics());
		}

		// Get live git context
		populateGitContext(data, coverageMap, projectDir, logger);

		// Generate per-class source coverage pages
		List<File> validSourceDirs = sourceDirs.stream()
				.filter(d -> d != null && d.exists()).toList();
		if (jacocoXmlDir != null && !validSourceDirs.isEmpty()
				&& jacocoXmlDir.exists()
				&& data.getSelectedTests() != null && !data.getSelectedTests().isEmpty())
		{
			File sourcesDir = new File(outputFile.getParentFile(), "sources");
			SourceCoverageGenerator sourceGen = new SourceCoverageGenerator();
			SourceCoverageGenerator.SourceGenerationResult result = sourceGen.generateSourcePages(
					jacocoXmlDir, validSourceDirs, sourcesDir, data.getSelectedTests(), data.getTestMappings());
			data.setSourceLinks(result.getSourceLinks());
			data.setClassCoveragePercentages(result.getCoveragePercentages());
			if (!result.getSourceLinks().isEmpty())
			{
				logger.info("[SmartTestPicker] Generated {} source coverage pages", result.getSourceLinks().size());
			}
		}

		// Write chunk data files for large coverage maps
		if (data.getTestMappings() != null
				&& data.getTestMappings().size() > ChunkDataWriter.CHUNK_SIZE)
		{
			File dataDir = new File(outputFile.getParentFile(), "data");
			ChunkDataWriter chunkWriter = new ChunkDataWriter();
			ChunkDataWriter.ChunkWriteResult chunkResult = chunkWriter.writeChunks(
					dataDir,
					data.getTestMappings(),
					data.getSelectedTests(),
					data.getSourceLinks(),
					data.getClassCoveragePercentages());
			data.setChunkCount(chunkResult.getChunkCount());
			data.setTotalTestCount(chunkResult.getTotalTests());
			logger.info("[SmartTestPicker] Generated {} data chunks for {} tests",
					chunkResult.getChunkCount(), chunkResult.getTotalTests());
		}

		// Generate HTML
		HtmlReportGenerator generator = new HtmlReportGenerator();
		String html = generator.generate(data);

		// Write output
		FileUtils.ensureParentDirExists(outputFile);
		try (FileWriter writer = new FileWriter(outputFile))
		{
			writer.write(html);
			logger.info("[SmartTestPicker] Report generated: {}", outputFile.getAbsolutePath());
		}
	}

	/**
	 * Generates the HTML report, searching multiple source and XML directories.
	 * If exec and classes directories are provided, uses binary exec merge for
	 * accurate branch coverage.
	 */
	public void generate(File coverageMapFile, File selectedTestsFile, File outputFile,
			List<File> jacocoXmlDirs, List<File> sourceDirs, File projectDir,
			int maxCommitDistance, boolean classLevelSelection,
			EngineLogger logger) throws IOException
	{
		generate(coverageMapFile, selectedTestsFile, outputFile,
				jacocoXmlDirs, sourceDirs, List.of(), List.of(),
				projectDir, maxCommitDistance, classLevelSelection, logger);
	}

	/**
	 * Generates the HTML report with optional exec-based binary merge for accurate branch coverage.
	 *
	 * @param jacocoXmlDirs          per-test JaCoCo XML report directories
	 * @param sourceDirs             Java source directories for line-level coverage highlighting
	 * @param execDirs               .exec file directories for binary merge (empty to skip)
	 * @param classesDirs            compiled class directories for binary merge (empty to skip)
	 * @param projectDir             git working directory
	 * @param classLevelSelection    whether class-level expansion section should be included
	 */
	public void generate(File coverageMapFile, File selectedTestsFile, File outputFile,
			List<File> jacocoXmlDirs, List<File> sourceDirs,
			List<File> execDirs, List<File> classesDirs,
			File projectDir, int maxCommitDistance, boolean classLevelSelection,
			EngineLogger logger) throws IOException
	{
		// Parse coverage map
		CoverageMap coverageMap = parseCoverageMap(coverageMapFile, logger);

		// Parse selected-tests.json
		ReportData data = new ReportData();
		parseSelectedTests(selectedTestsFile, data, logger);
		data.setClassLevelSelection(classLevelSelection);

		// Populate metadata from coverage map
		if (coverageMap != null && coverageMap.getMetadata() != null)
		{
			CoverageMapMetadata metadata = coverageMap.getMetadata();
			data.setBaseBranch(metadata.getBaseBranch());
			data.setCommitId(metadata.getCommitId());
			data.setTimestamp(metadata.getTimestamp());
		}

		// Populate test mappings
		if (coverageMap != null && coverageMap.getTestMappings() != null)
		{
			data.setTestMappings(coverageMap.getTestMappings());
		}

		if (coverageMap != null && coverageMap.getClassMetrics() != null)
		{
			data.setClassMetrics(coverageMap.getClassMetrics());
		}

		// Get live git context
		populateGitContext(data, coverageMap, projectDir, logger);

		// Generate per-class source coverage pages from multiple XML dirs
		List<File> validSourceDirs = sourceDirs != null
				? sourceDirs.stream().filter(d -> d != null && d.exists()).toList()
				: List.of();
		List<File> validXmlDirs = jacocoXmlDirs != null
				? jacocoXmlDirs.stream().filter(d -> d != null && d.exists()).toList()
				: List.of();

		if (!validSourceDirs.isEmpty()
				&& data.getSelectedTests() != null && !data.getSelectedTests().isEmpty())
		{
			File sourcesDir = new File(outputFile.getParentFile(), "sources");
			SourceCoverageGenerator sourceGen = new SourceCoverageGenerator();

			List<File> validExecDirs = execDirs != null
					? execDirs.stream().filter(d -> d != null && d.exists()).toList()
					: List.of();
			List<File> validClassesDirs = classesDirs != null
					? classesDirs.stream().filter(d -> d != null && d.exists()).toList()
					: List.of();

			SourceCoverageGenerator.SourceGenerationResult result;
			if (!validExecDirs.isEmpty() && !validClassesDirs.isEmpty())
			{
				logger.info("[SmartTestPicker] Using binary exec merge for accurate branch coverage");
				result = sourceGen.generateSourcePagesFromExec(
						validExecDirs, validClassesDirs, validSourceDirs, sourcesDir,
						data.getSelectedTests(), data.getTestMappings());
			}
			else if (!validXmlDirs.isEmpty())
			{
				result = sourceGen.generateSourcePages(
						validXmlDirs, validSourceDirs, sourcesDir,
						data.getSelectedTests(), data.getTestMappings());
			}
			else
			{
				result = new SourceCoverageGenerator.SourceGenerationResult(Map.of(), Map.of());
			}

			data.setSourceLinks(result.getSourceLinks());
			data.setClassCoveragePercentages(result.getCoveragePercentages());
			if (!result.getSourceLinks().isEmpty())
			{
				logger.info("[SmartTestPicker] Generated {} source coverage pages", result.getSourceLinks().size());
			}
		}

		// Write chunk data files for large coverage maps
		if (data.getTestMappings() != null
				&& data.getTestMappings().size() > ChunkDataWriter.CHUNK_SIZE)
		{
			File dataDir = new File(outputFile.getParentFile(), "data");
			ChunkDataWriter chunkWriter = new ChunkDataWriter();
			ChunkDataWriter.ChunkWriteResult chunkResult = chunkWriter.writeChunks(
					dataDir,
					data.getTestMappings(),
					data.getSelectedTests(),
					data.getSourceLinks(),
					data.getClassCoveragePercentages());
			data.setChunkCount(chunkResult.getChunkCount());
			data.setTotalTestCount(chunkResult.getTotalTests());
			logger.info("[SmartTestPicker] Generated {} data chunks for {} tests",
					chunkResult.getChunkCount(), chunkResult.getTotalTests());
		}

		// Generate HTML
		HtmlReportGenerator generator = new HtmlReportGenerator();
		String html = generator.generate(data);

		// Write output
		FileUtils.ensureParentDirExists(outputFile);
		try (FileWriter writer = new FileWriter(outputFile))
		{
			writer.write(html);
			logger.info("[SmartTestPicker] Report generated: {}", outputFile.getAbsolutePath());
		}
	}

	private CoverageMap parseCoverageMap(File mapFile, EngineLogger logger)
	{
		try
		{
			return CoverageMapReader.load(mapFile);
		}
		catch (IOException e)
		{
			logger.warn("[SmartTestPicker] Failed to read coverage map: {}", e.getMessage());
			return null;
		}
	}

	private void parseSelectedTests(File selectedFile, ReportData data, EngineLogger logger)
	{
		try
		{
			String json = new String(Files.readAllBytes(selectedFile.toPath()));
			SelectionOutput output = new Gson().fromJson(json, SelectionOutput.class);

			if (output == null)
			{
				data.setSelectedTests(Set.of());
				return;
			}

			if ("FULL_SUITE".equals(output.getStatus()))
			{
				data.setFullSuite(true);
			}
			else if ("NONE".equals(output.getStatus()))
			{
				data.setNone(true);
			}

			data.setFullSuiteReason(output.getReason());

			if (output.getSelectedTests() != null)
			{
				data.setSelectedTests(new LinkedHashSet<>(output.getSelectedTests()));
			}
			else
			{
				data.setSelectedTests(Set.of());
			}

			if (output.getUnmappedTests() != null)
			{
				data.setUnmappedTests(new LinkedHashMap<>(output.getUnmappedTests()));
			}
			else
			{
				data.setUnmappedTests(Map.of());
			}
		}
		catch (IOException e)
		{
			logger.warn("[SmartTestPicker] Failed to read selected tests file: {}", e.getMessage());
			data.setSelectedTests(Set.of());
			data.setUnmappedTests(Map.of());
		}
	}

	private void populateGitContext(ReportData data, CoverageMap coverageMap,
			File projectDir, EngineLogger logger)
	{
		try
		{
			GitChangeDetector git = new GitChangeDetector(projectDir);
			data.setCurrentBranch(git.getCurrentBranch());

			if (coverageMap != null && coverageMap.getMetadata() != null)
			{
				String commitId = coverageMap.getMetadata().getCommitId();
				if (commitId != null && !commitId.isEmpty() && git.isValidCommit(commitId))
				{
					data.setCommitDistance(git.getCommitDistance(commitId));
					data.setChangedClasses(git.getChangedClasses(commitId));
					data.setChangedMethods(git.getChangedMethods(commitId));
				}
				else
				{
					data.setCommitDistance(0);
					data.setChangedClasses(Set.of());
					data.setChangedMethods(Set.of());
				}
			}
			else
			{
				data.setChangedClasses(Set.of());
				data.setChangedMethods(Set.of());
			}
		}
		catch (RuntimeException e)
		{
			logger.warn("[SmartTestPicker] Git context unavailable: {}", e.getMessage());
			data.setCurrentBranch("unknown");
			data.setCommitDistance(0);
			data.setChangedClasses(Set.of());
			data.setChangedMethods(Set.of());
		}
	}
}
