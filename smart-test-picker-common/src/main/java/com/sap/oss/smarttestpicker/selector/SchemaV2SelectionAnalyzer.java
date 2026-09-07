// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationResult;

/** Authoritative schema-v2 ingress and revision-bound change-analysis boundary. */
public final class SchemaV2SelectionAnalyzer
{
	private final CoverageMapCodec codec;
	private final Runnable beforeHeadVerification;

	public SchemaV2SelectionAnalyzer() { this(new CoverageMapCodec(), () -> {}); }
	SchemaV2SelectionAnalyzer(CoverageMapCodec codec, Runnable beforeHeadVerification)
	{
		this.codec = codec;
		this.beforeHeadVerification = beforeHeadVerification;
	}

	public SelectionAnalysisResult analyze(File mapFile, File projectDir, HeadTestInventory headInventory,
			int maxCommitDistance, List<String> fullSuiteTriggers)
	{
		try
		{
			if (mapFile == null || !mapFile.isFile()) return runAll("Coverage map not found");
			if (headInventory == null) return runAll("Authoritative head test inventory unavailable");
			if (projectDir == null || !projectDir.isDirectory()) return runAll("Git project directory unavailable");

			// This is the sole decode. Everything below receives the semantic object.
			CoverageMap map = codec.deserialize(Files.readAllBytes(mapFile.toPath()));
			SelectionAnalysisResult inadmissible = validateAdmissibility(map);
			if (inadmissible != null) return inadmissible;

			Git git = new Git(projectDir);
			String head = git.output("rev-parse", "--verify", "HEAD^{commit}").trim();
			String revision = map.revision().value();
			if (revision == null || revision.isBlank()) return runAll("Coverage map revision is missing");
			git.output("rev-parse", "--verify", revision + "^{commit}");
			if (!git.isAncestor(revision, head))
				return runAll("Coverage map revision is not an ancestor of selection head");
			int distance = Integer.parseInt(git.output("rev-list", "--count", revision + ".." + head).trim());
			if (distance < 0) return runAll("Invalid commit distance");
			if (distance > maxCommitDistance) return runAll("Coverage map revision is stale: " + distance + " commits");

			List<PathChange> changes = new ArrayList<>();
			parseChanges(git.output("diff", "--name-status", "--find-renames", revision + ".." + head), changes);
			parseChanges(git.output("diff", "--name-status", "--find-renames", "--cached", head), changes);
			parseChanges(git.output("diff", "--name-status", "--find-renames", head), changes);
			for (String path : git.output("ls-files", "--others", "--exclude-standard").split("\\R"))
				if (!path.isBlank()) changes.add(new PathChange('A', path, path));

			Set<String> changedPaths = new TreeSet<>();
			for (PathChange change : changes) { changedPaths.add(change.oldPath()); changedPaths.add(change.newPath()); }
			changedPaths.remove("");
			String trigger = triggerMatch(changedPaths, fullSuiteTriggers == null ? List.of() : fullSuiteTriggers);
			if (trigger != null) return runAll("Changed path matches full-suite trigger: " + trigger);

			Set<String> changedClasses = new TreeSet<>();
			Set<String> changedTestContainers = new TreeSet<>();
			for (PathChange change : changes)
			{
				SelectionAnalysisResult unsafe = classify(change, changedClasses, changedTestContainers);
				if (unsafe != null) return unsafe;
			}

			Set<TestIdentity> mapTests = new TreeSet<>(map.tests().keySet());
			map.unmapped().forEach(value -> mapTests.add(value.test()));
			Set<TestIdentity> headTests = headInventory.runnableTests();
			Set<TestIdentity> newTests = difference(headTests, mapTests);
			Set<TestIdentity> deletedTests = difference(mapTests, headTests);
			Set<TestIdentity> changedTests = new TreeSet<>();
			for (TestIdentity test : headTests)
				if (changedTestContainers.contains(test.className())) changedTests.add(test);

			beforeHeadVerification.run();
			if (!head.equals(git.output("rev-parse", "--verify", "HEAD^{commit}").trim()))
				return runAll("HEAD moved during selection analysis");
			return SelectionAnalysisResult.ready(new SelectionContext(map, map.revision(), head, changedClasses,
					changedPaths, headTests, newTests, deletedTests, changedTests));
		}
		catch (IOException e) { return runAll("Coverage map unreadable: " + e.getMessage()); }
		catch (RuntimeException e) { return runAll("Unsafe selector analysis: " + safeMessage(e)); }
	}

	private SelectionAnalysisResult validateAdmissibility(CoverageMap map)
	{
		if (map.schemaVersion() != CoverageMapContract.SCHEMA_VERSION) return runAll("Unsupported coverage-map schema");
		if (map.revision() == null || map.revision().value() == null || map.revision().value().isBlank()) return runAll("Coverage map revision is missing");
		if (map.lifecycleState() != CoverageMapLifecycleState.PUBLISHED) return runAll("Coverage map is not PUBLISHED");
		if (map.completeness() == null) return runAll("Coverage map completeness is missing");
		ValidationResult validation = codec.validate(map);
		if (!validation.isValid()) return runAll("Coverage map validation failed: " + validation.errors().get(0).message());
		if (!map.completeness().isComplete()) return runAll("Coverage map is incomplete");
		Set<TestIdentity> logicalInventory = new TreeSet<>(map.tests().keySet());
		map.unmapped().forEach(value -> logicalInventory.add(value.test()));
		if (!logicalInventory.equals(map.completeness().reportedTests())
				|| !logicalInventory.equals(map.completeness().expectedTests()))
			return runAll("Coverage map completeness does not match its logical test inventory");
		return null;
	}

	private SelectionAnalysisResult classify(PathChange change, Set<String> classes, Set<String> testContainers)
	{
		for (String path : change.paths())
		{
			if (isUnder(path, "src/test/java/"))
			{
				String identity = sourceIdentity(path, "src/test/java/");
				if (identity == null) return runAll("Ambiguous test source path: " + path);
				testContainers.add(identity);
			}
			else if (isProduction(path))
			{
				String identity = productionIdentity(path);
				if (identity == null) return runAll("Ambiguous production source path: " + path);
				classes.add(identity);
			}
		}
		if (change.rename() && (isProduction(change.oldPath()) != isProduction(change.newPath())))
			return runAll("Ambiguous production rename/move: " + change.oldPath() + " -> " + change.newPath());
		return null;
	}

	private static boolean isProduction(String path)
	{
		if (!path.endsWith(".java")) return false;
		if (path.contains("src/main/java/") || path.contains("src/main/groovy/") || path.contains("src/main/kotlin/")) return true;
		int source = path.indexOf("/src/");
		if (source < 0) return false;
		String relative = path.substring(source + 5);
		return !relative.startsWith("test/") && !relative.startsWith("testsrc/");
	}
	private static String productionIdentity(String path)
	{
		for (String marker : List.of("src/main/java/", "src/main/groovy/", "src/main/kotlin/"))
			if (path.contains(marker)) return sourceIdentity(path, marker);
		int source = path.indexOf("/src/");
		return source < 0 ? null : sourceIdentity(path, "/src/");
	}
	private static boolean isUnder(String path, String marker) { return path.endsWith(".java") && path.contains(marker); }
	private static String sourceIdentity(String path, String marker)
	{
		int start = path.indexOf(marker) + marker.length();
		if (start < marker.length() || start >= path.length() - 5) return null;
		String relative = path.substring(start, path.length() - 5);
		String[] parts = relative.split("/", -1);
		if (parts.length == 0) return null;
		for (String part : parts)
			if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))
					|| part.substring(1).codePoints().anyMatch(c -> !Character.isJavaIdentifierPart(c))) return null;
		return String.join(".", parts);
	}

	private static void parseChanges(String output, List<PathChange> target)
	{
		for (String line : output.split("\\R"))
		{
			if (line.isBlank()) continue;
			String[] fields = line.split("\\t", -1);
			if (fields.length < 2 || fields[0].isBlank()) throw new IllegalArgumentException("Malformed git name-status output");
			char status = fields[0].charAt(0);
			if (status != 'A' && status != 'M' && status != 'D' && status != 'R')
				throw new IllegalArgumentException("Ambiguous git change status: " + fields[0]);
			if (status == 'R')
			{
				if (fields.length != 3) throw new IllegalArgumentException("Malformed git rename output");
				target.add(new PathChange(status, fields[1], fields[2]));
			}
			else
			{
				if (fields.length != 2) throw new IllegalArgumentException("Malformed git change output");
				target.add(new PathChange(status, fields[1], fields[1]));
			}
		}
	}

	private static String triggerMatch(Set<String> paths, List<String> triggers)
	{
		try
		{
			for (String pattern : triggers)
			{
				PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
				for (String path : paths) if (matcher.matches(Paths.get(path))) return path + " (pattern: " + pattern + ")";
			}
			return null;
		}
		catch (InvalidPathException | java.util.regex.PatternSyntaxException e)
		{
			throw new IllegalArgumentException("Invalid full-suite trigger or changed path", e);
		}
	}

	private static <T extends Comparable<? super T>> Set<T> difference(Set<T> left, Set<T> right)
	{
		TreeSet<T> result = new TreeSet<>(left); result.removeAll(right); return result;
	}
	private static SelectionAnalysisResult runAll(String reason) { return SelectionAnalysisResult.runAll(reason); }
	private static String safeMessage(RuntimeException e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

	private record PathChange(char status, String oldPath, String newPath)
	{
		boolean rename() { return status == 'R'; }
		Set<String> paths() { return new LinkedHashSet<>(List.of(oldPath, newPath)); }
	}

	private static final class Git
	{
		private final File directory;
		private Git(File directory) { this.directory = directory; }
		private boolean isAncestor(String revision, String head)
		{
			CommandResult result = execute(true, "merge-base", "--is-ancestor", revision, head);
			if (result.exitCode == 0) return true;
			if (result.exitCode == 1) return false;
			throw new IllegalStateException("git merge-base --is-ancestor failed with exit " + result.exitCode);
		}
		private String output(String... args)
		{
			CommandResult result = execute(false, args);
			if (result.exitCode != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + result.output);
			return result.output;
		}
		private CommandResult execute(boolean statusOnly, String... args)
		{
			try
			{
				List<String> command = new ArrayList<>(); command.add("git"); command.addAll(List.of(args));
				Process process = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).start();
				String output = new String(process.getInputStream().readAllBytes());
				int exit = process.waitFor();
				return new CommandResult(exit, statusOnly ? "" : output.trim());
			}
			catch (IOException e) { throw new IllegalStateException("Failed to run git", e); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while running git", e); }
		}
	}
	private record CommandResult(int exitCode, String output) {}
}
