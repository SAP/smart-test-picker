// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

import com.sap.oss.smarttestpicker.engine.CoverageMapEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 * CLI subcommand that generates a JSON coverage map from per-test JaCoCo XML reports.
 *
 * <p>Wraps {@link CoverageMapEngine} with picocli option parsing. The coverage map
 * records which classes and methods each test covers, along with git metadata
 * (commitId, baseBranch, timestamp) for change detection.</p>
 *
 * <p>Supports three output formats:</p>
 * <ul>
 *   <li>Plain JSON (default) -- human-readable, with pretty printing</li>
 *   <li>Indexed JSON ({@code --indexed}) -- deduplicated class/method strings with
 *       integer references, typically 10-15x smaller</li>
 *   <li>Gzip ({@code --gzip}) -- additional compression, combinable with indexed format</li>
 * </ul>
 *
 * @see CoverageMapEngine
 * @see com.sap.oss.smarttestpicker.mapper.IndexedCoverageMap
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

		try
		{
			new CoverageMapEngine().generate(xmlDir, output, baseBranch, projectDir, logger, indexed, gzip);
			return 0;
		}
		catch (IOException e)
		{
			System.err.println("Error: " + e.getMessage());
			return 1;
		}
	}
}
