// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import io.github.ljubisap.smarttestpicker.engine.ReportEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 * CLI subcommand that generates an HTML dashboard report of test selection results.
 *
 * <p>Reads a coverage map and selected-tests.json, then produces a self-contained
 * HTML report with stat cards, donut charts, coverage matrix, changed code listing,
 * unmapped tests, and optionally per-class source coverage pages.</p>
 *
 * <p>This is the CLI equivalent of the Gradle {@code generateTestReport} task
 * and the Maven {@code generate-report} goal.</p>
 *
 * @see ReportEngine
 */
@Command(
		name = "generate-report",
		mixinStandardHelpOptions = true,
		description = "Generate HTML dashboard report from coverage map and selection results"
)
public class GenerateReportCommand implements Callable<Integer>
{

	@Option(names = "--map", required = true,
			description = "Coverage map file (JSON, indexed, or gzip)")
	private File mapFile;

	@Option(names = "--selected-tests", required = true,
			description = "Selected tests JSON file (output of select-tests)")
	private File selectedTestsFile;

	@Option(names = "--output", required = true,
			description = "Output HTML report file")
	private File output;

	@Option(names = "--xml-dir",
			description = "Per-test JaCoCo XML reports directory (for source coverage pages)")
	private File xmlDir;

	@Option(names = "--source-dir",
			description = "Java source directory (for source coverage pages)")
	private File sourceDir;

	@Option(names = "--platform-home",
			description = "Platform home (auto-discovers source directories)")
	private File platformHome;

	@Option(names = "--project-dir",
			description = "Project root directory for git commands (default: current dir)")
	private File projectDir;

	@Option(names = "--max-commit-distance", defaultValue = "500",
			description = "Max commit distance for display (default: 500)")
	private int maxCommitDistance;

	@Option(names = "--class-level-selection", defaultValue = "false",
			description = "Enable class-level expansion section in report")
	private boolean classLevelSelection;

	@Override
	public Integer call()
	{
		if (!mapFile.exists())
		{
			System.err.println("Error: coverage map not found: " + mapFile);
			return 1;
		}

		if (!selectedTestsFile.exists())
		{
			System.err.println("Error: selected tests file not found: " + selectedTestsFile);
			return 1;
		}

		if (projectDir == null)
		{
			projectDir = new File(System.getProperty("user.dir"));
		}

		ConsoleLogger logger = new ConsoleLogger();
		logger.info("Coverage map:       {}", mapFile.getAbsolutePath());
		logger.info("Selected tests:     {}", selectedTestsFile.getAbsolutePath());
		logger.info("Output:             {}", output.getAbsolutePath());

		File effectiveXmlDir = xmlDir != null && xmlDir.exists() ? xmlDir : null;

		List<File> sourceDirList = new ArrayList<>();
		if (platformHome != null)
		{
			PlatformScanner scanner = new PlatformScanner(platformHome, logger);
			sourceDirList.addAll(scanner.discoverSourceDirs());
		}
		if (sourceDir != null && sourceDir.exists())
		{
			sourceDirList.add(sourceDir);
		}

		if (effectiveXmlDir != null)
		{
			logger.info("XML reports dir:    {}", effectiveXmlDir.getAbsolutePath());
		}
		if (!sourceDirList.isEmpty())
		{
			logger.info("Source dirs:        {} directories", sourceDirList.size());
		}

		try
		{
			new ReportEngine().generate(
					mapFile,
					selectedTestsFile,
					output,
					effectiveXmlDir,
					sourceDirList,
					projectDir,
					maxCommitDistance,
					classLevelSelection,
					logger);

			logger.info("Report generated:   {}", output.getAbsolutePath());
			return 0;
		}
		catch (IOException e)
		{
			System.err.println("Error generating report: " + e.getMessage());
			return 1;
		}
	}
}
