// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import com.google.gson.GsonBuilder;

import com.sap.oss.smarttestpicker.engine.ExecToXmlEngine;
import com.sap.oss.smarttestpicker.engine.ReportEngine;
import com.sap.oss.smarttestpicker.engine.TestSelectionEngine;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 * CLI command that generates a fresh coverage report for changed code.
 *
 * <p>Orchestrates the full pipeline in one shot:</p>
 * <ol>
 *   <li>Select tests impacted by code changes (coverage map + git diff)</li>
 *   <li>Run selected tests via {@code ant unittests} with JaCoCo per-test splitting</li>
 *   <li>Convert per-test {@code .exec} files to XML reports</li>
 *   <li>Generate HTML dashboard with line-level source coverage for changed classes</li>
 * </ol>
 *
 * <p>Requires both method-level test execution and JaCoCo per-test
 * splitting to be active on the target platform.</p>
 */
@Command(
		name = "refreshed-report",
		mixinStandardHelpOptions = true,
		description = "Run impacted tests with JaCoCo and generate fresh coverage report"
)
public class RefreshedReportCommand implements Callable<Integer>
{

	@Option(names = "--map", required = true,
			description = "Coverage map file (JSON, indexed, or gzip)")
	private File mapFile;

	@Option(names = "--platform-home", required = true,
			description = "Platform home directory (e.g. bin/platform/)")
	private File platformHome;

	@Option(names = "--exec-dir", defaultValue = "/tmp/jacoco-exec/",
			description = "Directory where JaCoCo per-test .exec files are written (default: /tmp/jacoco-exec/)")
	private File execDir;

	@Option(names = "--output", defaultValue = "build/reports/smart-test-picker/index.html",
			description = "Output HTML report file")
	private File output;

	@Option(names = "--max-commit-distance", defaultValue = "500",
			description = "Max commits before coverage map is considered stale (default: 500)")
	private int maxCommitDistance;

	@Option(names = "--threads", defaultValue = "0",
			description = "Parallel threads for exec-to-xml conversion (default: CPU count)")
	private int threads;

	@Option(names = "--skip-tests",
			description = "Skip test execution (use existing exec files)")
	private boolean skipTests;

	@Option(names = "--java-home",
			description = "Java home for ant (default: JAVA_HOME env var)")
	private File javaHome;

	@Override
	public Integer call()
	{
		ConsoleLogger logger = new ConsoleLogger();

		if (!mapFile.exists())
		{
			logger.warn("Coverage map not found: {}", mapFile);
			return 1;
		}
		if (!platformHome.isDirectory())
		{
			logger.warn("Platform home not found: {}", platformHome);
			return 1;
		}

		// Step 1: Select tests
		logger.info("=== Step 1: Selecting impacted tests ===");
		File testClassesDir = new File(platformHome, "ext/core/classes");
		TestSelectionEngine selectionEngine = new TestSelectionEngine();
		SelectionOutput selection = selectionEngine.select(
				mapFile, testClassesDir, platformHome,
				maxCommitDistance, List.of(), logger);

		logger.info("Status: {}", selection.getStatus());
		logger.info("Reason: {}", selection.getReason());

		if ("NONE".equals(selection.getStatus()))
		{
			logger.info("No production code changes — nothing to run.");
			return 0;
		}

		if (selection.getSelectedTests() == null || selection.getSelectedTests().isEmpty())
		{
			logger.warn("No tests selected. Status: {}", selection.getStatus());
			if ("FULL_SUITE".equals(selection.getStatus()))
			{
				logger.warn("Full suite would be required — skipping (too broad for refreshed report).");
			}
			return 0;
		}

		logger.info("Selected {} tests", selection.getSelectedTests().size());

		// Format for ant: group by class, methods joined with +
		String antPackages = formatForAnt(selection);
		logger.info("Ant packages: {}", antPackages);

		// Step 2: Discover platform directories
		logger.info("=== Step 2: Discovering platform directories ===");
		PlatformScanner scanner = new PlatformScanner(platformHome, logger);
		List<File> classesDirs = scanner.discoverClassesDirs();
		List<File> sourceDirs = scanner.discoverSourceDirs();

		// Step 3: Run tests via ant
		if (!skipTests)
		{
			logger.info("=== Step 3: Running tests via ant ===");

			// Clean exec dir before running
			if (execDir.isDirectory())
			{
				File[] oldExecs = execDir.listFiles((dir, name) -> name.endsWith(".exec"));
				if (oldExecs != null)
				{
					for (File f : oldExecs)
					{
						f.delete();
					}
				}
			}
			else
			{
				execDir.mkdirs();
			}

			int antResult = runAnt(antPackages, logger);
			if (antResult != 0)
			{
				logger.warn("Ant unittests failed with exit code {}", antResult);
				return 1;
			}
		}
		else
		{
			logger.info("=== Step 3: Skipping test execution (--skip-tests) ===");
		}

		// Step 4: Exec → XML (only for selected tests)
		logger.info("=== Step 4: Converting exec files to XML ===");
		int threadCount = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
		File xmlDir = new File(output.getParentFile(), "jacoco-xml");
		FilenameFilter selectedTestFilter = buildSelectedTestFilter(selection);
		try
		{
			new ExecToXmlEngine().generateReports(
					execDir, classesDirs, null, xmlDir,
					logger, threadCount, selectedTestFilter);
		}
		catch (IOException e)
		{
			logger.warn("Exec-to-XML conversion failed: {}", e.getMessage());
			return 1;
		}

		// Step 5: Generate report
		logger.info("=== Step 5: Generating HTML report ===");
		File selectedTestsFile = new File(output.getParentFile(), "selected-tests.json");
		try
		{
			writeSelectedTests(selection, selectedTestsFile);
			new ReportEngine().generate(
					mapFile, selectedTestsFile, output,
					xmlDir, sourceDirs, platformHome,
					maxCommitDistance, false, logger);
			logger.info("Report generated: {}", output.getAbsolutePath());
			return 0;
		}
		catch (IOException e)
		{
			logger.warn("Report generation failed: {}", e.getMessage());
			return 1;
		}
	}

	String formatForAnt(SelectionOutput selection)
	{
		Map<String, List<String>> byClass = new LinkedHashMap<>();
		for (String test : selection.getSelectedTests())
		{
			int hash = test.indexOf('#');
			if (hash > 0)
			{
				byClass.computeIfAbsent(test.substring(0, hash), k -> new ArrayList<>())
						.add(test.substring(hash + 1));
			}
			else
			{
				byClass.computeIfAbsent(test, k -> new ArrayList<>());
			}
		}

		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Map.Entry<String, List<String>> entry : byClass.entrySet())
		{
			if (!first) sb.append(",");
			first = false;
			sb.append(entry.getKey());
			if (!entry.getValue().isEmpty())
			{
				sb.append("#").append(String.join("+", entry.getValue()));
			}
		}
		return sb.toString();
	}

	private int runAnt(String antPackages, ConsoleLogger logger)
	{
		try
		{
			String antCmd = "source ./setantenv.sh > /dev/null 2>&1 && ant unittests"
					+ " -Dtestclasses.packages=" + antPackages
					+ " -Dtestclasses.method.filtering=true";
			ProcessBuilder pb = new ProcessBuilder("bash", "-c", antCmd);
			pb.directory(platformHome);
			pb.redirectErrorStream(true);

			if (javaHome != null && javaHome.isDirectory())
			{
				pb.environment().put("JAVA_HOME", javaHome.getAbsolutePath());
			}

			Process process = pb.start();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					System.out.println(line);
				}
			}
			return process.waitFor();
		}
		catch (IOException | InterruptedException e)
		{
			logger.warn("Failed to run ant: {}", e.getMessage());
			return 1;
		}
	}

	private FilenameFilter buildSelectedTestFilter(SelectionOutput selection)
	{
		Set<String> execNames = new HashSet<>();
		for (String test : selection.getSelectedTests())
		{
			execNames.add(test + ".exec");
		}
		return (dir, name) -> execNames.contains(name);
	}

	private void writeSelectedTests(SelectionOutput selection, File file) throws IOException
	{
		file.getParentFile().mkdirs();
		try (FileWriter writer = new FileWriter(file))
		{
			new GsonBuilder().setPrettyPrinting().create().toJson(selection, writer);
		}
	}
}
