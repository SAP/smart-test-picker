// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.cli;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.google.gson.GsonBuilder;

import io.github.ljubisap.smarttestpicker.engine.TestSelectionEngine;
import io.github.ljubisap.smarttestpicker.selector.SelectionOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 * CLI subcommand that selects tests impacted by code changes.
 *
 * <p>Reads a coverage map and uses git diff to determine which tests need to run.
 * Delegates to {@link TestSelectionEngine} for the full 8-step selection flow.</p>
 *
 * <p>Supports three output formats:</p>
 * <ul>
 *   <li>{@code json} (default) -- standard SelectionOutput JSON, compatible with Gradle/Maven plugins</li>
 *   <li>{@code txt} -- one test per line, for shell scripts</li>
 *   <li>{@code ant} -- grouped by class with method filtering syntax (ClassName#method1+method2)</li>
 * </ul>
 *
 * @see TestSelectionEngine
 */
@Command(
		name = "select-tests",
		mixinStandardHelpOptions = true,
		description = "Select tests impacted by code changes using coverage map and git diff"
)
public class SelectTestsCommand implements Callable<Integer>
{

	@Option(names = "--map", required = true,
			description = "Coverage map file (JSON, indexed, or gzip)")
	private File mapFile;

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
			description = "Compiled test classes directory for new test detection")
	private File testClassesDir;

	@Override
	public Integer call()
	{
		if (!mapFile.exists())
		{
			System.err.println("Error: coverage map not found: " + mapFile);
			return 1;
		}

		if (projectDir == null)
		{
			projectDir = new File(System.getProperty("user.dir"));
		}

		// Use a non-existent dir if not provided -- NewTestDetector returns empty map
		if (testClassesDir == null)
		{
			testClassesDir = new File(projectDir, "build/classes/java/test");
		}

		ConsoleLogger logger = new ConsoleLogger();
		logger.info("Coverage map:      {}", mapFile.getAbsolutePath());
		logger.info("Project dir:       {}", projectDir.getAbsolutePath());
		logger.info("Output:            {}", output.getAbsolutePath());
		logger.info("Format:            {}", format);
		logger.info("Max commit dist:   {}", maxCommitDistance);

		TestSelectionEngine engine = new TestSelectionEngine();
		SelectionOutput result = engine.select(
				mapFile, testClassesDir, projectDir,
				maxCommitDistance, fullSuiteTriggers, logger);

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

	private void writeOutput(SelectionOutput result) throws IOException
	{
		output.getParentFile().mkdirs();

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
		List<String> allTests = collectAllTests(result);

		try (FileWriter writer = new FileWriter(output))
		{
			for (String test : allTests)
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

		// Add unmapped tests (class-level only, no methods)
		if (result.getUnmappedTests() != null)
		{
			for (String fqn : result.getUnmappedTests().keySet())
			{
				byClass.computeIfAbsent(fqn, k -> new ArrayList<>());
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

	private List<String> collectAllTests(SelectionOutput result)
	{
		List<String> all = new ArrayList<>();
		if (result.getSelectedTests() != null)
		{
			all.addAll(result.getSelectedTests());
		}
		if (result.getUnmappedTests() != null)
		{
			all.addAll(result.getUnmappedTests().keySet());
		}
		return all;
	}
}
