// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.sap.oss.smarttestpicker.mapper.CoverageMap;
import com.sap.oss.smarttestpicker.mapper.CoverageMapReader;
import com.sap.oss.smarttestpicker.store.CoverageMapResolver;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/**
 * CLI subcommand for querying a coverage map interactively.
 *
 * <p>The coverage map can be provided explicitly via {@code --map}, or resolved
 * automatically from the local cache using the {@code --prefer-map} mode
 * (same behavior as {@code select-tests}).</p>
 *
 * <p>Provides multiple query modes for debugging and exploring test coverage data:</p>
 * <ul>
 *   <li>{@code --test} -- show all classes and methods covered by a specific test</li>
 *   <li>{@code --class} -- find all tests that cover a given class</li>
 *   <li>{@code --method} -- find all tests that cover a given method</li>
 *   <li>{@code --grep} -- case-insensitive substring search across tests, classes, and methods</li>
 *   <li>{@code --stats} -- display aggregate statistics (totals, min/max/avg coverage)</li>
 * </ul>
 *
 * @see CoverageMapReader
 * @see CoverageMapResolver
 */
@Command(
		name = "query",
		mixinStandardHelpOptions = true,
		description = "Query a coverage map for tests, classes, or methods"
)
public class QueryCommand implements Callable<Integer>
{

	@Option(names = "--map",
			description = "Coverage map JSON file. If not provided, resolves from local cache.")
	private File mapFile;

	@Option(names = "--prefer-map", defaultValue = "nearest",
			description = "Map selection mode when using cache: nearest, remote, local (default: nearest)")
	private String preferMap;

	@Option(names = "--project-dir",
			description = "Project root directory (default: current dir)")
	private File projectDir;

	@ArgGroup(exclusive = true, multiplicity = "1")
	private QueryMode queryMode;

	static class QueryMode
	{
		@Option(names = "--test",
				description = "Show coverage for a specific test (FQN#method)")
		String test;

		@Option(names = "--class",
				description = "Find all tests covering a class (FQN)")
		String className;

		@Option(names = "--method",
				description = "Find all tests covering a method (FQN#method)")
		String method;

		@Option(names = "--grep",
				description = "Substring search across test names, classes, and methods")
		String grep;

		@Option(names = "--stats",
				description = "Show map statistics (total tests, classes, methods)")
		boolean stats;
	}

	@Override
	public Integer call()
	{
		if (projectDir == null)
		{
			projectDir = new File(System.getProperty("user.dir"));
		}

		ConsoleLogger logger = new ConsoleLogger();

		// Resolve map: explicit --map takes precedence over cache
		if (mapFile == null)
		{
			CoverageMapResolver.PreferMode mode = parsePreferMode(preferMap);
			CoverageMapResolver resolver = new CoverageMapResolver(projectDir, logger);
			mapFile = resolver.resolve(mode);

			if (mapFile == null)
			{
				System.err.println("No coverage map available. Use --map or run 'pull-map' first.");
				return 1;
			}
		}
		else if (!mapFile.exists())
		{
			System.err.println("Error: coverage map not found: " + mapFile);
			return 1;
		}

		System.err.println("Loading coverage map: " + mapFile.getAbsolutePath());
		long start = System.currentTimeMillis();

		CoverageMap map;
		try
		{
			map = CoverageMapReader.load(mapFile);
		}
		catch (IOException e)
		{
			System.err.println("Error: " + e.getMessage());
			return 1;
		}

		long elapsed = System.currentTimeMillis() - start;
		System.err.printf("Loaded %d tests in %.1fs%n",
				map.getTestMappings().size(), elapsed / 1000.0);

		if (queryMode.stats)
		{
			return showStats(map);
		}
		else if (queryMode.test != null)
		{
			return queryByTest(map, queryMode.test);
		}
		else if (queryMode.className != null)
		{
			return queryByClass(map, queryMode.className);
		}
		else if (queryMode.method != null)
		{
			return queryByMethod(map, queryMode.method);
		}
		else
		{
			return queryByGrep(map, queryMode.grep);
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

	private int showStats(CoverageMap map)
	{
		int totalTests = map.getTestMappings().size();
		long totalClasses = map.getTestMappings().values().stream()
				.map(c -> c.get("classes"))
				.filter(l -> l != null)
				.flatMap(List::stream)
				.distinct()
				.count();
		long totalMethods = map.getTestMappings().values().stream()
				.map(c -> c.get("methods"))
				.filter(l -> l != null)
				.flatMap(List::stream)
				.distinct()
				.count();

		System.out.println("Coverage Map Statistics");
		System.out.println("=======================");
		if (map.getMetadata() != null)
		{
			System.out.println("Base branch:  " + map.getMetadata().getBaseBranch());
			System.out.println("Commit ID:    " + map.getMetadata().getCommitId());
			System.out.println("Timestamp:    " + map.getMetadata().getTimestamp());
		}
		System.out.println("Total tests:   " + totalTests);
		System.out.println("Unique classes: " + totalClasses);
		System.out.println("Unique methods: " + totalMethods);

		int minClasses = Integer.MAX_VALUE, maxClasses = 0;
		long sumClasses = 0;
		for (Map<String, List<String>> coverage : map.getTestMappings().values())
		{
			int n = coverage.get("classes") != null ? coverage.get("classes").size() : 0;
			minClasses = Math.min(minClasses, n);
			maxClasses = Math.max(maxClasses, n);
			sumClasses += n;
		}
		System.out.printf("Classes/test:  min=%d, max=%d, avg=%.1f%n",
				minClasses, maxClasses, (double) sumClasses / totalTests);

		return 0;
	}

	private int queryByTest(CoverageMap map, String testName)
	{
		Map<String, List<String>> coverage = map.getTestMappings().get(testName);
		if (coverage == null)
		{
			List<String> matches = new ArrayList<>();
			for (String key : map.getTestMappings().keySet())
			{
				if (key.contains(testName))
				{
					matches.add(key);
				}
			}
			if (matches.isEmpty())
			{
				System.err.println("Test not found: " + testName);
				return 1;
			}
			System.err.println("Exact match not found. Did you mean:");
			for (String m : matches)
			{
				System.out.println("  " + m);
			}
			return 1;
		}

		List<String> classes = coverage.get("classes");
		List<String> methods = coverage.get("methods");

		System.out.println("Test: " + testName);
		System.out.println();

		if (classes != null && !classes.isEmpty())
		{
			System.out.println("Classes covered (" + classes.size() + "):");
			for (String cls : classes)
			{
				System.out.println("  " + cls);
			}
		}

		if (methods != null && !methods.isEmpty())
		{
			System.out.println();
			System.out.println("Methods covered (" + methods.size() + "):");
			for (String m : methods)
			{
				System.out.println("  " + m);
			}
		}

		return 0;
	}

	private int queryByClass(CoverageMap map, String className)
	{
		List<String> matchingTests = new ArrayList<>();

		for (Map.Entry<String, Map<String, List<String>>> entry : map.getTestMappings().entrySet())
		{
			List<String> classes = entry.getValue().get("classes");
			if (classes != null && classes.contains(className))
			{
				matchingTests.add(entry.getKey());
			}
		}

		if (matchingTests.isEmpty())
		{
			// No exact FQN match found. Try substring matching against all class names
			// to suggest corrections (e.g. user typed short name instead of full FQN).
			List<String> partialMatches = new ArrayList<>();
			for (Map.Entry<String, Map<String, List<String>>> entry : map.getTestMappings().entrySet())
			{
				List<String> classes = entry.getValue().get("classes");
				if (classes != null)
				{
					for (String cls : classes)
					{
						if (cls.contains(className))
						{
							partialMatches.add(cls);
							break;
						}
					}
				}
			}
			if (!partialMatches.isEmpty())
			{
				System.err.println("No exact match. Classes containing '" + className + "':");
				partialMatches.stream().distinct().sorted().limit(20).forEach(c -> System.out.println("  " + c));
			}
			else
			{
				System.err.println("No tests found covering class: " + className);
			}
			return 1;
		}

		matchingTests.sort(String::compareTo);
		System.out.println("Tests covering " + className + " (" + matchingTests.size() + "):");
		for (String test : matchingTests)
		{
			System.out.println("  " + test);
		}

		return 0;
	}

	private int queryByMethod(CoverageMap map, String method)
	{
		List<String> matchingTests = new ArrayList<>();

		for (Map.Entry<String, Map<String, List<String>>> entry : map.getTestMappings().entrySet())
		{
			List<String> methods = entry.getValue().get("methods");
			if (methods != null && methods.contains(method))
			{
				matchingTests.add(entry.getKey());
			}
		}

		if (matchingTests.isEmpty())
		{
			System.err.println("No tests found covering method: " + method);
			return 1;
		}

		matchingTests.sort(String::compareTo);
		System.out.println("Tests covering " + method + " (" + matchingTests.size() + "):");
		for (String test : matchingTests)
		{
			System.out.println("  " + test);
		}

		return 0;
	}

	private int queryByGrep(CoverageMap map, String pattern)
	{
		String lower = pattern.toLowerCase();
		List<String> testMatches = new ArrayList<>();
		List<String> classMatches = new ArrayList<>();
		List<String> methodMatches = new ArrayList<>();

		for (Map.Entry<String, Map<String, List<String>>> entry : map.getTestMappings().entrySet())
		{
			if (entry.getKey().toLowerCase().contains(lower))
			{
				testMatches.add(entry.getKey());
			}

			List<String> classes = entry.getValue().get("classes");
			if (classes != null)
			{
				for (String cls : classes)
				{
					if (cls.toLowerCase().contains(lower))
					{
						classMatches.add(cls);
					}
				}
			}

			List<String> methods = entry.getValue().get("methods");
			if (methods != null)
			{
				for (String m : methods)
				{
					if (m.toLowerCase().contains(lower))
					{
						methodMatches.add(m);
					}
				}
			}
		}

		boolean found = false;

		if (!testMatches.isEmpty())
		{
			found = true;
			testMatches.sort(String::compareTo);
			System.out.println("Matching tests (" + testMatches.size() + "):");
			for (String t : testMatches)
			{
				System.out.println("  " + t);
			}
		}

		List<String> uniqueClasses = classMatches.stream().distinct().sorted().collect(java.util.stream.Collectors.toList());
		if (!uniqueClasses.isEmpty())
		{
			found = true;
			if (!testMatches.isEmpty()) System.out.println();
			System.out.println("Matching classes (" + uniqueClasses.size() + "):");
			for (String c : uniqueClasses)
			{
				System.out.println("  " + c);
			}
		}

		List<String> uniqueMethods = methodMatches.stream().distinct().sorted().collect(java.util.stream.Collectors.toList());
		if (!uniqueMethods.isEmpty())
		{
			found = true;
			if (!uniqueClasses.isEmpty() || !testMatches.isEmpty()) System.out.println();
			System.out.println("Matching methods (" + uniqueMethods.size() + "):");
			for (String m : uniqueMethods)
			{
				System.out.println("  " + m);
			}
		}

		if (!found)
		{
			System.err.println("No matches for: " + pattern);
			return 1;
		}

		return 0;
	}
}
