// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

import com.sap.oss.smarttestpicker.engine.CoverageMapEngine;
import com.sap.oss.smarttestpicker.store.CoverageMapResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 * CLI subcommand that generates a JSON coverage map from per-test JaCoCo XML reports.
 *
 * <p>Wraps {@link CoverageMapEngine} with picocli option parsing. The coverage map
 * records which classes and methods each test covers, along with git metadata
 * (commitId, baseBranch, timestamp) for change detection.</p>
 *
 * <p>When {@code --cache} is specified, the generated map is also written to the
 * local cache at {@code ~/.gradle/smart-test-picker/PROJECT_NAME/local-coverage-map.json}
 * so that {@code select-tests} and {@code query} can resolve it automatically.</p>
 *
 * @see CoverageMapEngine
 * @see CoverageMapResolver
 */
@Command(
		name = "generate-map",
		mixinStandardHelpOptions = true,
		description = "Generate JSON coverage map from per-test JaCoCo XML reports"
)
public class GenerateMapCommand implements Callable<Integer>
{

	@Option(names = "--xml-dir", required = true,
			description = "Directory containing per-test JaCoCo XML reports")
	private File xmlDir;

	@Option(names = "--output", required = true,
			description = "Output JSON file path")
	private File output;

	@Option(names = "--project-dir",
			description = "Project root directory for git metadata (default: current dir)")
	private File projectDir;

	@Option(names = "--base-branch", defaultValue = "main",
			description = "Base branch name for metadata (default: main)")
	private String baseBranch;

	@Option(names = "--indexed",
			description = "Use indexed format (smaller file, integer references)")
	private boolean indexed;

	@Option(names = "--gzip",
			description = "Compress output with gzip")
	private boolean gzip;

	@Option(names = "--cache",
			description = "Also write to local cache (~/.gradle/smart-test-picker/PROJECT_NAME/local-coverage-map.json)")
	private boolean cache;

	@Override
	public Integer call()
	{
		if (!xmlDir.isDirectory())
		{
			System.err.println("Error: XML directory does not exist: " + xmlDir);
			return 1;
		}

		if (projectDir == null)
		{
			projectDir = new File(System.getProperty("user.dir"));
		}

		ConsoleLogger logger = new ConsoleLogger();
		logger.info("XML directory:   {}", xmlDir.getAbsolutePath());
		logger.info("Output file:     {}", output.getAbsolutePath());
		logger.info("Project dir:     {}", projectDir.getAbsolutePath());
		logger.info("Base branch:     {}", baseBranch);
		logger.info("Indexed:         {}", indexed);
		logger.info("Gzip:            {}", gzip);
		logger.info("Cache:           {}", cache);

		try
		{
			new CoverageMapEngine().generate(xmlDir, output, baseBranch, projectDir, logger, indexed, gzip);

			if (cache)
			{
				CoverageMapResolver resolver = new CoverageMapResolver(projectDir, logger);
				File cacheFile = resolver.getLocalMapPath();
				new CoverageMapEngine().generate(xmlDir, cacheFile, baseBranch, projectDir, logger, indexed, gzip);
				logger.info("[SmartTestPicker] Cached local map: {}", cacheFile.getAbsolutePath());
			}

			return 0;
		}
		catch (IOException e)
		{
			System.err.println("Error: " + e.getMessage());
			return 1;
		}
	}
}
