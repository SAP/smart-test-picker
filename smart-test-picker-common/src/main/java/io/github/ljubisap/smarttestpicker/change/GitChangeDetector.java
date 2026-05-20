// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.change;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Detects code changes by running Git commands via {@link ProcessBuilder}.
 *
 * <p>This class is the core of the Change Analyzer module. It provides:</p>
 * <ul>
 *   <li><b>Class-level detection</b> — {@code git diff --name-only} to find changed Java files</li>
 *   <li><b>Method-level detection</b> — {@code git diff -U0} with hunk header parsing to find
 *       changed methods (requires {@code *.java diff=java} in .gitattributes)</li>
 *   <li><b>Commit validation</b> — verifies that the coverage map's commitId still exists</li>
 *   <li><b>Commit distance</b> — counts commits since coverage map generation</li>
 * </ul>
 *
 * <p>Both committed ({@code commitId..HEAD}) and uncommitted (working tree + staged) changes
 * are detected, so developers get accurate test selection even before committing.</p>
 *
 * <p>No Git library dependency — all operations use {@code git} CLI commands via ProcessBuilder.</p>
 */
public class GitChangeDetector
{

	/** Project root directory — used as the working directory for all git commands. */
	private final File projectDir;

	public GitChangeDetector(File projectDir)
	{
		this.projectDir = projectDir;
	}

	/**
	 * Returns the set of changed Java class FQNs between commitId and HEAD,
	 * plus any uncommitted changes in the working tree and staging area.
	 * Only considers files under recognized source roots.
	 */
	public Set<String> getChangedClasses(String commitId)
	{
		Set<String> classes = new LinkedHashSet<>();

		// Committed changes: commitId..HEAD
		collectChangedClasses(runGit("diff", "--name-only", commitId + "..HEAD"), classes);

		// Uncommitted changes: working tree + staged vs HEAD
		collectChangedClasses(runGit("diff", "--name-only", "HEAD"), classes);

		return classes;
	}

	/**
	 * Collects changed Java class FQNs from git diff output.
	 * Filters to only recognized source root files (ignores test sources, resources, etc.).
	 */
	private void collectChangedClasses(String diffOutput, Set<String> classes)
	{
		for (String line : diffOutput.split("\n"))
		{
			String path = line.trim();
			if (path.isEmpty())
				continue;

			if (path.endsWith(".java"))
			{
				String fqn = pathToFqn(path);
				if (fqn != null)
					classes.add(fqn);
			}
		}
	}

	/**
	 * Returns true if the given commitId is a valid git commit object.
	 */
	public boolean isValidCommit(String commitId)
	{
		try
		{
			String type = runGit("cat-file", "-t", commitId).trim();
			return "commit".equals(type);
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	/**
	 * Returns the number of commits between commitId and HEAD.
	 */
	public int getCommitDistance(String commitId)
	{
		String output = runGit("rev-list", "--count", commitId + "..HEAD").trim();
		return Integer.parseInt(output);
	}

	/**
	 * Returns the current branch name.
	 */
	public String getCurrentBranch()
	{
		return runGit("rev-parse", "--abbrev-ref", "HEAD").trim();
	}

	/**
	 * Returns the full SHA of the current HEAD commit.
	 */
	public String getHeadCommitId()
	{
		return runGit("rev-parse", "HEAD").trim();
	}

	/**
	 * Returns the set of changed Java method FQNs (e.g. "org.example.Foo#bar") between
	 * commitId and HEAD, plus uncommitted/staged changes.
	 * Requires "*.java diff=java" in .gitattributes for accurate method detection.
	 * Falls back to class-level if method name cannot be extracted from hunk header.
	 */
	public Set<String> getChangedMethods(String commitId)
	{
		Set<String> methods = new LinkedHashSet<>();

		// Committed changes
		collectChangedMethods(runGit("diff", "-U0", commitId + "..HEAD"), methods);

		// Uncommitted changes
		collectChangedMethods(runGit("diff", "-U0", "HEAD"), methods);

		return methods;
	}

	/**
	 * Returns a map of changed test source files between commitId and HEAD (plus uncommitted).
	 * Key: fully qualified class name (e.g. "org.example.MyTest")
	 * Value: git status letter — "A" (added), "M" (modified), "D" (deleted), "R" (renamed)
	 * Only considers files under src/test/java/.
	 */
	public Map<String, String> getChangedTestFiles(String commitId)
	{
		Map<String, String> result = new LinkedHashMap<>();

		// Committed changes
		collectChangedTestFiles(runGit("diff", "--name-status", commitId + "..HEAD"), result);

		// Uncommitted changes (working tree + staged)
		collectChangedTestFiles(runGit("diff", "--name-status", "HEAD"), result);

		return result;
	}

	/**
	 * Returns the set of all changed file paths (relative to project root) between
	 * commitId and HEAD, plus uncommitted/staged changes. No filtering — includes
	 * all file types (Java, XML, properties, etc.).
	 *
	 * <p>Used for matching against {@code fullSuiteTriggers} glob patterns.</p>
	 */
	public Set<String> getChangedFiles(String commitId)
	{
		Set<String> files = new LinkedHashSet<>();

		// Committed changes
		for (String line : runGit("diff", "--name-only", commitId + "..HEAD").split("\n"))
		{
			String path = line.trim();
			if (!path.isEmpty())
			{
				files.add(path);
			}
		}

		// Uncommitted changes
		for (String line : runGit("diff", "--name-only", "HEAD").split("\n"))
		{
			String path = line.trim();
			if (!path.isEmpty())
			{
				files.add(path);
			}
		}

		return files;
	}

	/**
	 * Collects changed test file FQNs from git diff --name-status output.
	 * Filters to src/test/java/ files only.
	 */
	private void collectChangedTestFiles(String diffOutput, Map<String, String> result)
	{
		for (String line : diffOutput.split("\n"))
		{
			String trimmed = line.trim();
			if (trimmed.isEmpty())
				continue;

			// Format: "A\tpath/to/File.java" or "M\tpath/to/File.java" or "R100\told\tnew"
			String[] parts = trimmed.split("\t");
			if (parts.length < 2)
				continue;

			String status = parts[0].substring(0, 1); // First letter: A, M, D, R, C, etc.
			String path = parts[parts.length - 1];     // Last part is the target path (handles renames)

			if (path.endsWith(".java") && path.contains("src/test/java/"))
			{
				String fqn = testPathToFqn(path);
				if (fqn != null)
				{
					result.put(fqn, status);
				}
			}
		}
	}

	/**
	 * Converts a test source file path to a fully qualified class name.
	 * e.g. "src/test/java/org/example/MyTest.java" → "org.example.MyTest"
	 */
	String testPathToFqn(String path)
	{
		int idx = path.indexOf("src/test/java/");
		if (idx < 0)
			return null;

		String relative = path.substring(idx + "src/test/java/".length());
		if (!relative.endsWith(".java"))
			return null;

		return relative.substring(0, relative.length() - ".java".length())
				.replace('/', '.');
	}

	// Matches: @@ <range> @@ <optional method signature>
	private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s+[^@]+@@\\s*(.*)$");

	// Extracts method name from a Java method signature like "public void foo(", "String bar() {"
	private static final Pattern METHOD_SIGNATURE = Pattern.compile(
			"(?:public|private|protected|static|final|abstract|synchronized|native|default|\\s)*"
					+ "[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\(");

	/**
	 * Collects changed method FQNs from a unified diff output ({@code git diff -U0}).
	 *
	 * <p>Tracks the current file from {@code diff --git} lines, then extracts method names
	 * from hunk headers ({@code @@ ... @@ methodSignature}). The Java diff driver
	 * ({@code *.java diff=java} in .gitattributes) makes git annotate hunks with the
	 * enclosing method signature.</p>
	 */
	private void collectChangedMethods(String diffOutput, Set<String> methods)
	{
		String currentClassFqn = null;

		for (String line : diffOutput.split("\n"))
		{
			// Track which file we're in: "diff --git a/src/main/java/org/example/Foo.java b/..."
			if (line.startsWith("diff --git"))
			{
				currentClassFqn = extractClassFqnFromDiffLine(line);
				continue;
			}

			// Parse hunk header for method name
			if (line.startsWith("@@") && currentClassFqn != null)
			{
				Matcher hunkMatcher = HUNK_HEADER.matcher(line);
				if (hunkMatcher.matches())
				{
					String context = hunkMatcher.group(1).trim();
					String methodName = extractMethodName(context);
					if (methodName != null)
					{
						methods.add(currentClassFqn + "#" + methodName);
					}
				}
			}
		}
	}

	/**
	 * Extracts the class FQN from a "diff --git" line.
	 * e.g. "diff --git a/src/main/java/org/example/Foo.java b/src/main/java/org/example/Foo.java"
	 * → "org.example.Foo"
	 */
	private String extractClassFqnFromDiffLine(String line)
	{
		int bIdx = line.lastIndexOf(" b/");
		if (bIdx < 0)
			return null;
		String path = line.substring(bIdx + 3);
		return pathToFqn(path);
	}

	/**
	 * Extracts the method name from a hunk header context string.
	 * e.g. "public String getName() {" → "getName"
	 */
	String extractMethodName(String context)
	{
		if (context == null || context.isEmpty())
			return null;

		Matcher m = METHOD_SIGNATURE.matcher(context);
		if (m.find())
		{
			return m.group(1);
		}
		return null;
	}

	/**
	 * Ensures .gitattributes has "*.java diff=java" for proper method-level hunk headers.
	 * Creates or appends to .gitattributes if needed.
	 */
	public void ensureJavaDiffDriver()
	{
		File gitattributes = new File(projectDir, ".gitattributes");
		String marker = "*.java diff=java";

		try
		{
			if (gitattributes.exists())
			{
				String content = new String(java.nio.file.Files.readAllBytes(gitattributes.toPath()));
				if (content.contains(marker))
					return;
				java.nio.file.Files.writeString(gitattributes.toPath(), "\n" + marker + "\n",
						java.nio.file.StandardOpenOption.APPEND);
			}
			else
			{
				java.nio.file.Files.writeString(gitattributes.toPath(), marker + "\n");
			}
		}
		catch (IOException e)
		{
			// Non-fatal: method detection falls back to class-level
		}
	}

	private static final String[] SOURCE_ROOT_MARKERS = {
			"src/main/java/",
			"src/main/groovy/",
			"src/main/kotlin/",
	};

	/**
	 * Converts a Java source file path to a fully qualified class name.
	 * Recognizes standard Maven/Gradle source roots ({@code src/main/java/}, etc.)
	 * and non-standard layouts where {@code src/} is followed directly by a package
	 * directory (e.g., {@code ext/core/src/com/sap/...}).
	 */
	String pathToFqn(String path)
	{
		if (!path.endsWith(".java"))
			return null;

		for (String marker : SOURCE_ROOT_MARKERS)
		{
			int idx = path.indexOf(marker);
			if (idx >= 0)
			{
				String relative = path.substring(idx + marker.length());
				return relative.substring(0, relative.length() - ".java".length())
						.replace('/', '.');
			}
		}

		int srcIdx = path.indexOf("/src/");
		if (srcIdx >= 0)
		{
			String afterSrc = path.substring(srcIdx + "/src/".length());
			if (afterSrc.contains("/") && !afterSrc.startsWith("test/") && !afterSrc.startsWith("testsrc/"))
			{
				return afterSrc.substring(0, afterSrc.length() - ".java".length())
						.replace('/', '.');
			}
		}

		return null;
	}

	/**
	 * Runs a git command in the project directory and returns its stdout.
	 *
	 * @param args git subcommand and arguments (e.g. {@code "diff", "--name-only", "abc..HEAD"})
	 * @return the command's standard output
	 * @throws RuntimeException if the command exits with a non-zero status
	 */
	private String runGit(String... args)
	{
		try
		{
			String[] command = new String[args.length + 1];
			command[0] = "git";
			System.arraycopy(args, 0, command, 1, args.length);

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(projectDir);
			pb.redirectErrorStream(true);

			Process process = pb.start();
			String output;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
			{
				output = reader.lines().collect(Collectors.joining("\n"));
			}

			int exitCode = process.waitFor();
			if (exitCode != 0)
			{
				throw new RuntimeException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
			}

			return output;
		}
		catch (IOException | InterruptedException e)
		{
			throw new RuntimeException("Failed to run git command", e);
		}
	}
}
