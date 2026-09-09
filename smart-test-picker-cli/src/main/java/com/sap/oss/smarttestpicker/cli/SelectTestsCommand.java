// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import com.sap.oss.smarttestpicker.engine.FileUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.google.gson.GsonBuilder;

import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionResult;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionStatus;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectorFlow;
import com.sap.oss.smarttestpicker.selector.HeadTestInventory;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.SchemaV2SelectorFlow;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;
import com.sap.oss.smarttestpicker.store.CoverageMapResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 * CLI subcommand that selects tests impacted by code changes.
 *
 * <p>Reads a coverage map and uses git diff to determine which tests need to run.
 * Delegates to the authoritative schema-v2 analyzer and selector.</p>
 *
 * <p>The coverage map can be provided explicitly via {@code --map}, or resolved
 * automatically from the local cache using the {@code --prefer-map} mode.</p>
 *
 * <p>Supports three output formats:</p>
 * <ul>
 *   <li>{@code json} (default) - standard SelectionOutput JSON, compatible with Gradle/Maven plugins</li>
 *   <li>{@code txt} - one test per line, for shell scripts</li>
 *   <li>{@code ant} - grouped by class with method filtering syntax (ClassName#method1+method2)</li>
 * </ul>
 *
 * @see CoverageMapResolver
 */
@Command(
		name = "select-tests",
		mixinStandardHelpOptions = true,
		description = "Select tests impacted by code changes using coverage map and git diff"
)
public class SelectTestsCommand implements Callable<Integer>
{

	@Option(names = "--map",
			description = "Coverage map file (JSON, indexed, or gzip). If not provided, resolves from local cache.")
	private File mapFile;

	@Option(names = "--prefer-map", defaultValue = "nearest",
			description = "Map selection mode when using cache: nearest, remote, local (default: nearest)")
	private String preferMap;

	@Option(names = "--project-dir",
			description = "Project root directory for git commands (default: current dir)")
	private File projectDir;

	@Option(names = "--output", required = true,
			description = "Output file for selected tests")
	private File output;

	@Option(names = "--format", defaultValue = "json",
			description = "Output format: json, txt, ant (default: json)")
	private String format;

	@Option(names = "--max-commit-distance", defaultValue = "500",
			description = "Max commits before coverage map is considered stale (default: 500)")
	private int maxCommitDistance;

	@Option(names = "--full-suite-trigger",
			description = "Glob pattern that forces full suite when matched (repeatable)")
	private List<String> fullSuiteTriggers = new ArrayList<>();

	@Option(names = "--test-classes-dir",
			description = "Deprecated legacy option; schema-v2 selection requires --head-inventory")
	private File testClassesDir;

	@Option(names = "--head-inventory",
			description = "Exact schema-v2 head test inventory JSON")
	private File headInventoryFile;

	@Option(names = "--integration-revision",
			description = "Frozen full integration commit ID (enables explicit PR mode)")
	private String integrationRevision;

	@Option(names = "--pr-base-revision",
			description = "Frozen full PR base commit ID (explicit PR mode)")
	private String prBaseRevision;

	@Option(names = "--pr-head-revision",
			description = "Frozen full PR head commit ID (explicit PR mode)")
	private String prHeadRevision;

	@Override
	public Integer call()
	{
		if (projectDir == null)
		{
			projectDir = new File(System.getProperty("user.dir"));
		}

		ConsoleLogger logger = new ConsoleLogger();
		boolean explicitPr = integrationRevision != null || prBaseRevision != null || prHeadRevision != null;
		if (explicitPr && (integrationRevision == null || prBaseRevision == null || prHeadRevision == null))
		{
			System.err.println("Explicit PR mode requires --integration-revision, --pr-base-revision,"
					+ " and --pr-head-revision");
			return 2;
		}
		if (explicitPr && (mapFile == null || headInventoryFile == null))
		{
			System.err.println("Explicit PR mode requires --map and --head-inventory");
			return 2;
		}
		if (explicitPr && !"json".equalsIgnoreCase(format))
		{
			System.err.println("Explicit PR mode requires --format json");
			return 2;
		}

		// Resolve coverage map: explicit --map takes precedence over cache
		if (mapFile == null)
		{
			CoverageMapResolver.PreferMode mode = parsePreferMode(preferMap);
			CoverageMapResolver resolver = new CoverageMapResolver(projectDir, logger);
			mapFile = resolver.resolve(mode);

			if (mapFile == null) logger.warn("No coverage map available; selection will fail open.");
		}
		else
		{
			if (mapFile.exists()) logger.info("[SmartTestPicker] Using explicit map: {}", mapFile.getAbsolutePath());
			else logger.warn("Coverage map not found; selection will fail open: {}", mapFile);
		}

		logger.info("Coverage map:      {}", mapFile == null ? "<missing>" : mapFile.getAbsolutePath());
		logger.info("Head inventory:    {}", headInventoryFile == null ? "<missing>" : headInventoryFile.getAbsolutePath());
		logger.info("Project dir:       {}", projectDir.getAbsolutePath());
		logger.info("Output:            {}", output.getAbsolutePath());
		logger.info("Format:            {}", format);
		logger.info("Max commit dist:   {}", maxCommitDistance);

		if (explicitPr) return selectExplicitPr(logger);

		SelectionOutput result = new SchemaV2SelectorFlow().select(
				mapFile, headInventoryFile, projectDir,
				maxCommitDistance, fullSuiteTriggers);

		logger.info("Status:  {}", result.getStatus());
		logger.info("Reason:  {}", result.getReason());

		if (result.getSelectedTests() != null)
		{
			logger.info("Selected tests:  {}", result.getSelectedTests().size());
		}
		if (result.getUnmappedTests() != null && !result.getUnmappedTests().isEmpty())
		{
			logger.info("Unmapped tests:  {}", result.getUnmappedTests().size());
		}

		try
		{
			writeOutput(result);
			return 0;
		}
		catch (IOException e)
		{
			System.err.println("Error writing output: " + e.getMessage());
			return 1;
		}
	}

	private int selectExplicitPr(ConsoleLogger logger)
	{
		ExplicitPrSelectionResult result;
		try
		{
			HeadTestInventory inventory = new HeadTestInventoryCodec().read(headInventoryFile);
			result = new ExplicitPrSelectorFlow().select(mapFile, projectDir, inventory,
					integrationRevision, prBaseRevision, prHeadRevision, maxCommitDistance, fullSuiteTriggers);
		}
		catch (IOException | RuntimeException e)
		{
			result = ExplicitPrSelectionResult.failure(ExplicitPrSelectionStatus.ERROR,
					"Explicit PR selection failed: " + safeMessage(e));
		}

		ExplicitPrCliOutput outputResult = ExplicitPrCliOutput.from(result);
		logger.info("Status:  {}", outputResult.status());
		logger.info("Reason:  {}", outputResult.reason());
		try
		{
			FileUtils.ensureParentDirExists(output);
			try (FileWriter writer = new FileWriter(output))
			{
				new GsonBuilder().setPrettyPrinting().create().toJson(outputResult, writer);
			}
			return 0;
		}
		catch (IOException e)
		{
			System.err.println("Error writing output: " + e.getMessage());
			return 1;
		}
	}

	private static String safeMessage(Exception e)
	{
		return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
	}

	private record ExplicitPrCliOutput(String status, String reason, SelectionOutput selectionOutput)
	{
		private static ExplicitPrCliOutput from(ExplicitPrSelectionResult result)
		{
			if (result.status() == ExplicitPrSelectionStatus.SELECTION_RESULT)
			{
				SelectionOutput selection = result.output().orElseThrow();
				return new ExplicitPrCliOutput(selection.getStatus(), selection.getReason(), selection);
			}
			return new ExplicitPrCliOutput(result.status().name(), result.reason(), null);
		}
	}

	private CoverageMapResolver.PreferMode parsePreferMode(String value)
	{
		switch (value.toLowerCase())
		{
			case "remote":
				return CoverageMapResolver.PreferMode.REMOTE;
			case "local":
				return CoverageMapResolver.PreferMode.LOCAL;
			case "nearest":
			default:
				return CoverageMapResolver.PreferMode.NEAREST;
		}
	}

	private void writeOutput(SelectionOutput result) throws IOException
	{
		FileUtils.ensureParentDirExists(output);

		switch (format.toLowerCase())
		{
			case "json":
				writeJson(result);
				break;
			case "txt":
				writeTxt(result);
				break;
			case "ant":
				writeAnt(result);
				break;
			default:
				System.err.println("Unknown format: " + format + ". Using json.");
				writeJson(result);
		}
	}

	private void writeJson(SelectionOutput result) throws IOException
	{
		try (FileWriter writer = new FileWriter(output))
		{
			new GsonBuilder().setPrettyPrinting().create().toJson(result, writer);
		}
	}

	private void writeTxt(SelectionOutput result) throws IOException
	{
		try (FileWriter writer = new FileWriter(output))
		{
			if (failOpen(result)) writer.write("FULL_SUITE\n");
			else for (String test : selected(result))
			{
				writer.write(test + "\n");
			}
		}
	}

	/**
	 * Writes tests in ant format: grouped by class, methods joined with +.
	 * Format: ClassName#method1+method2,OtherClass#method3
	 * If a test has no method (unmapped tests), just the class name is written.
	 */
	private void writeAnt(SelectionOutput result) throws IOException
	{
		if (failOpen(result))
		{
			throw new IOException("Ant output cannot safely represent FULL_SUITE; use JSON or txt");
		}
		// Group selected tests by class: ClassName -> [method1, method2, ...]
		Map<String, List<String>> byClass = new LinkedHashMap<>();

		if (result.getSelectedTests() != null)
		{
			for (String test : result.getSelectedTests())
			{
				int hash = test.indexOf('#');
				if (hash > 0)
				{
					String className = test.substring(0, hash);
					String method = test.substring(hash + 1);
					byClass.computeIfAbsent(className, k -> new ArrayList<>()).add(method);
				}
				else
				{
					byClass.computeIfAbsent(test, k -> new ArrayList<>());
				}
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

		try (FileWriter writer = new FileWriter(output))
		{
			writer.write(sb.toString());
		}
	}

	private static boolean failOpen(SelectionOutput result)
	{
		return result == null || !List.of("SELECTED", "NONE", "FULL_SUITE").contains(result.getStatus())
				|| "FULL_SUITE".equals(result.getStatus());
	}

	private static List<String> selected(SelectionOutput result)
	{
		return result.getSelectedTests() == null ? List.of() : result.getSelectedTests();
	}
}
