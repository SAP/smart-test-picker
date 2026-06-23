// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ljubisap.smarttestpicker.change.GitChangeDetector;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;


/**
 * Detects test classes that exist on disk but are not in the coverage map.
 * These are "new tests" or "unmapped tests" that should always be run.
 *
 * <p>Extracted from the Gradle {@code SelectTestsTask} to be reusable across
 * both Gradle and Maven plugins.</p>
 */
public class NewTestDetector
{

	/**
	 * Scans compiled test classes and identifies those not present in the coverage map.
	 *
	 * @param coverageMap    the loaded coverage map (may be null)
	 * @param git            the git change detector
	 * @param commitId       the coverage map's commitId (may be null if unavailable)
	 * @param testClassesDir compiled test classes directory (e.g. build/classes/java/test)
	 * @param logger         engine logger for warnings
	 * @return map of FQN to reason for each unmapped test class
	 */
	public Map<String, String> detect(CoverageMap coverageMap, GitChangeDetector git,
			String commitId, File testClassesDir, EngineLogger logger)
	{
		return detect(coverageMap, git, commitId,
				testClassesDir != null ? List.of(testClassesDir) : List.of(),
				List.of(), logger);
	}

	/**
	 * Scans multiple compiled test class directories (multi-module support).
	 */
	public Map<String, String> detect(CoverageMap coverageMap, GitChangeDetector git,
			String commitId, List<File> testClassesDirs, EngineLogger logger)
	{
		return detect(coverageMap, git, commitId, testClassesDirs, List.of(), logger);
	}

	/**
	 * Scans multiple compiled test class directories with source lookup for filtering.
	 *
	 * @param testSourceDirs test source directories (parallel to testClassesDirs), used to
	 *                       filter out abstract classes, interfaces, and annotations by reading source
	 */
	public Map<String, String> detect(CoverageMap coverageMap, GitChangeDetector git,
			String commitId, List<File> testClassesDirs, List<File> testSourceDirs,
			EngineLogger logger)
	{
		Set<String> knownTestClasses = new HashSet<>();
		if (coverageMap != null && coverageMap.getTestMappings() != null)
		{
			for (String testName : coverageMap.getTestMappings().keySet())
			{
				int hash = testName.indexOf('#');
				if (hash > 0)
				{
					knownTestClasses.add(testName.substring(0, hash));
				}
			}
		}

		Set<String> newTestFqns = new LinkedHashSet<>();
		Map<String, String> newTestReasons = new LinkedHashMap<>();

		for (File testClassesDir : testClassesDirs)
		{
			if (testClassesDir == null || !testClassesDir.exists())
			{
				continue;
			}
			scanTestClasses(testClassesDir, knownTestClasses, newTestFqns, newTestReasons,
					testSourceDirs, logger);
		}

		// Detect modified known test classes that may contain new methods
		Map<String, String> changedTestFiles = Map.of();
		if (commitId != null)
		{
			try
			{
				changedTestFiles = git.getChangedTestFiles(commitId);
			}
			catch (RuntimeException e)
			{
				logger.warn("[SmartTestPicker] Failed to get test file change status: {}", e.getMessage());
			}
		}

		Set<String> modifiedKnownTestClasses = new LinkedHashSet<>();
		for (Map.Entry<String, String> entry : changedTestFiles.entrySet())
		{
			String fqn = entry.getKey();
			String status = entry.getValue();
			int lastDot = fqn.lastIndexOf('.');
			String simpleName = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
			if (("M".equals(status) || "A".equals(status)) && knownTestClasses.contains(simpleName))
			{
				modifiedKnownTestClasses.add(simpleName);
			}
		}

		Map<String, String> result = new LinkedHashMap<>();

		// For modified known classes, add them as unmapped so all their methods run
		if (!modifiedKnownTestClasses.isEmpty())
		{
			for (Map.Entry<String, String> entry : changedTestFiles.entrySet())
			{
				String fqn = entry.getKey();
				int lastDot = fqn.lastIndexOf('.');
				String simpleName = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
				if (modifiedKnownTestClasses.contains(simpleName))
				{
					result.put(fqn, "Modified test class \u2014 may contain new test methods");
				}
			}
		}

		// Add new test classes
		for (String fqn : newTestFqns)
		{
			String status = changedTestFiles.get(fqn);
			String sourceReason = newTestReasons.getOrDefault(fqn, "");
			String reason;
			if ("A".equals(status))
			{
				reason = "New test \u2014 added after coverage map";
			}
			else if ("M".equals(status))
			{
				reason = "Modified after coverage map";
			}
			else if (!sourceReason.isEmpty())
			{
				reason = sourceReason;
			}
			else
			{
				reason = "Not in coverage map";
			}
			result.put(fqn, reason);
		}

		if (!result.isEmpty())
		{
			logger.info("[SmartTestPicker] Unmapped test classes (not in coverage map): {}", result.keySet());
		}

		return result;
	}

	private void scanTestClasses(File testClassesDir, Set<String> knownTestClasses,
			Set<String> newTestFqns, Map<String, String> newTestReasons,
			List<File> testSourceDirs, EngineLogger logger)
	{
		logger.info("[SmartTestPicker] Scanning {} for unmapped test classes", testClassesDir);
		try
		{
			java.nio.file.Path basePath = testClassesDir.toPath();
			Files.walk(basePath)
					.filter(p -> p.toString().endsWith(".class"))
					.forEach(classFile -> {
						String relativePath = basePath.relativize(classFile).toString();
						String fqn = relativePath.replace(File.separatorChar, '.')
								.replace('/', '.').replaceAll("\\.class$", "");

						if (fqn.contains("$"))
							return;

						int lastDot = fqn.lastIndexOf('.');
						String simpleName = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;

						if (!knownTestClasses.contains(simpleName) && looksLikeTestClass(simpleName))
						{
							String javaRelPath = relativePath.replaceAll("\\.class$", ".java");
							if (isAbstractOrInterface(javaRelPath, testSourceDirs))
							{
								return;
							}
							newTestFqns.add(fqn);
							String reason = classifyFromSource(javaRelPath, testSourceDirs);
							if (reason != null)
							{
								newTestReasons.put(fqn, reason);
							}
						}
					});
		}
		catch (IOException e)
		{
			logger.warn("[SmartTestPicker] Failed to scan test classes in {}: {}",
					testClassesDir, e.getMessage());
		}
	}

	/**
	 * Reads a Java source file and classifies why the test class may not appear in the coverage map.
	 *
	 * <p>Priority order: {@code @Disabled/@Ignore} > Enclosed runner > package-private >
	 * conditional skip ({@code System.getenv}) > ArchUnit {@code @ArchTest} > no {@code @Test} methods.</p>
	 *
	 * @param javaRelPath    relative path to the .java file (e.g. "com/example/FooTest.java")
	 * @param testSourceDirs directories to search for the source file
	 * @return a human-readable reason string, or {@code null} if the test looks normal
	 */
	static String classifyFromSource(String javaRelPath, List<File> testSourceDirs)
	{
		for (File srcDir : testSourceDirs)
		{
			File javaFile = new File(srcDir, javaRelPath);
			if (!javaFile.exists())
			{
				continue;
			}
			try
			{
				List<String> lines = Files.readAllLines(javaFile.toPath());
				String fullText = String.join("\n", lines);
				boolean hasTestAnnotation = false;
				boolean hasDisabledOrIgnore = false;
				boolean isPackagePrivate = false;
				boolean hasConditionalSkip = false;
				boolean hasEnclosedRunner = false;
				boolean hasArchUnit = false;
				boolean inBlockComment = false;

				for (String line : lines)
				{
					String trimmed = line.trim();
					if (inBlockComment)
					{
						if (trimmed.contains("*/")) inBlockComment = false;
						continue;
					}
					if (trimmed.startsWith("/*"))
					{
						if (!trimmed.contains("*/")) inBlockComment = true;
						continue;
					}

					if (trimmed.contains("@Test") || trimmed.contains("@ParameterizedTest"))
					{
						hasTestAnnotation = true;
					}
					// Exact match + startsWith("(") avoids false positives from annotations like @IgnoreReason
					if (trimmed.equals("@Disabled") || trimmed.startsWith("@Disabled(")
							|| trimmed.equals("@Ignore") || trimmed.startsWith("@Ignore("))
					{
						hasDisabledOrIgnore = true;
					}
					if (trimmed.contains("@ArchTest"))
					{
						hasArchUnit = true;
					}
					if (trimmed.contains("Enclosed.class"))
					{
						hasEnclosedRunner = true;
					}
					if (!trimmed.startsWith("//") && !trimmed.startsWith("@")
							&& !trimmed.startsWith("import") && !trimmed.startsWith("package")
							&& (trimmed.matches("^class\\s+.*") || trimmed.matches("^final\\s+class\\s+.*")))
					{
						isPackagePrivate = true;
					}
				}

				if (fullText.contains("System.getenv("))
				{
					hasConditionalSkip = true;
				}

				if (hasDisabledOrIgnore) return "All tests @Disabled/@Ignore";
				if (hasEnclosedRunner) return "Enclosed runner \u2014 tests only in inner classes";
				if (isPackagePrivate) return "Package-private class \u2014 may not produce coverage sessions";
				if (hasConditionalSkip) return "Conditional skip \u2014 tests may not execute without env vars";
				if (hasArchUnit) return "ArchUnit test \u2014 @ArchTest fields, no standard @Test";
				if (!hasTestAnnotation) return "No @Test methods found";
				return null;
			}
			catch (IOException ignored)
			{
			}
			return null;
		}
		return null;
	}

	/**
	 * Checks if a Java source file declares an abstract class, interface, or annotation type.
	 * Used to filter out non-instantiable test base classes from the unmapped tests list.
	 */
	static boolean isAbstractOrInterface(String javaRelPath, List<File> testSourceDirs)
	{
		for (File srcDir : testSourceDirs)
		{
			File javaFile = new File(srcDir, javaRelPath);
			if (!javaFile.exists())
			{
				continue;
			}
			try
			{
				// Track block comments to skip multi-line copyright headers (e.g. SAP /* ... */ blocks)
				boolean inBlockComment = false;
				for (String line : Files.readAllLines(javaFile.toPath()))
				{
					String trimmed = line.trim();
					if (inBlockComment)
					{
						if (trimmed.contains("*/"))
						{
							inBlockComment = false;
						}
						continue;
					}
					if (trimmed.startsWith("/*"))
					{
						if (!trimmed.contains("*/"))
						{
							inBlockComment = true;
						}
						continue;
					}
					if (trimmed.isEmpty() || trimmed.startsWith("package ")
							|| trimmed.startsWith("import ") || trimmed.startsWith("//"))
					{
						continue;
					}
					if (trimmed.contains("abstract class ") || trimmed.contains("@interface ")
							|| trimmed.matches(".*\\binterface\\s+\\w+.*"))
					{
						return true;
					}
					if (trimmed.startsWith("@"))
					{
						continue;
					}
					if (trimmed.contains(" class ") || trimmed.contains(" enum "))
					{
						return false;
					}
				}
			}
			catch (IOException ignored)
			{
			}
			return false;
		}
		return false;
	}

	private static boolean looksLikeTestClass(String simpleName)
	{
		return simpleName.endsWith("Test") || simpleName.endsWith("Tests")
				|| simpleName.endsWith("Spec") || simpleName.endsWith("IT");
	}
}
