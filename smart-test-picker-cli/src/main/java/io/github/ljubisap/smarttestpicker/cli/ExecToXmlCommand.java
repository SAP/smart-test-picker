// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import io.github.ljubisap.smarttestpicker.engine.ExecToXmlEngine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 * CLI subcommand that converts per-test JaCoCo {@code .exec} files into XML reports.
 *
 * <p>Wraps {@link io.github.ljubisap.smarttestpicker.engine.ExecToXmlEngine} with
 * picocli option parsing. Supports custom platform auto-discovery
 * of compiled classes directories via {@code --platform-home}.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * smart-test-picker exec-to-xml \
 *     --exec-dir /path/to/exec-files \
 *     --output-dir /path/to/xml-output \
 *     --platform-home /path/to/platform
 * }</pre>
 *
 * @see io.github.ljubisap.smarttestpicker.engine.ExecToXmlEngine
 */
@Command(
		name = "exec-to-xml",
		mixinStandardHelpOptions = true,
		description = "Convert per-test JaCoCo .exec files into XML reports"
)
public class ExecToXmlCommand implements Callable<Integer>
{

	@Option(names = "--exec-dir", required = true,
			description = "Directory containing .exec files")
	private File execDir;

	@Option(names = "--output-dir", required = true,
			description = "Output directory for XML reports")
	private File outputDir;

	@Option(names = "--platform-home",
			description = "Platform home (auto-discovers classes directories)")
	private File platformHome;

	@Option(names = "--classes-dir",
			description = "Compiled classes directory (repeatable)")
	private List<File> classesDirs = new ArrayList<>();

	@Option(names = "--source-dir",
			description = "Java source directory for report references")
	private File sourceDir;

	@Option(names = "--threads", defaultValue = "0",
			description = "Parallel threads (default: CPU count)")
	private int threads;

	@Override
	public Integer call()
	{
		if (!execDir.isDirectory())
		{
			System.err.println("Error: exec directory does not exist: " + execDir);
			return 1;
		}

		if (platformHome != null)
		{
			PlatformScanner scanner = new PlatformScanner(platformHome, new ConsoleLogger());
			classesDirs.addAll(scanner.discoverClassesDirs());
		}

		if (classesDirs.isEmpty())
		{
			System.err.println("Error: no classes directories found. Use --platform-home or --classes-dir");
			return 1;
		}

		int threadCount = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();

		ConsoleLogger logger = new ConsoleLogger();
		logger.info("Exec directory:    {}", execDir.getAbsolutePath());
		logger.info("Output directory:  {}", outputDir.getAbsolutePath());
		logger.info("Classes dirs:      {} directories", classesDirs.size());
		for (File dir : classesDirs)
		{
			logger.info("  - {}", dir.getAbsolutePath());
		}
		logger.info("Threads:           {}", threadCount);

		try
		{
			new ExecToXmlEngine().generateReports(
					execDir, classesDirs, sourceDir, outputDir,
					logger, threadCount, ExecToXmlEngine.allExecFilter());
			return 0;
		}
		catch (IOException e)
		{
			System.err.println("Error: " + e.getMessage());
			return 1;
		}
	}

}
