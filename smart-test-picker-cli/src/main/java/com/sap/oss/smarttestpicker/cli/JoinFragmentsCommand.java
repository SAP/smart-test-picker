// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentJoiner;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageMapPublication;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MethodIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventory;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectorFlow;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Strict, single-target schema-v3 joining with verified atomic file publication. */
@Command(name = "join-fragments", mixinStandardHelpOptions = true,
		description = "Join complete schema-v3 shard fragments into one verified, published coverage map")
public final class JoinFragmentsCommand implements Callable<Integer>
{
	private static final java.util.regex.Pattern PAIR_FILE = java.util.regex.Pattern.compile("(fragment|tests)-(.+)\\.json");
	@Option(names = "--dir", description = "Directory of paired fragment-<name>.json and tests-<name>.json files")
	private Path pairedDir;
	@Option(names = "--expected-shards", description = "Positive number of shard pairs required with --dir")
	private Integer expectedShards;
	@Option(names = "--fragments-dir",
			description = "Directory of fragment files; every direct file is decoded, without extension filtering")
	private Path fragmentsDir;
	@Option(names = "--assignments-dir",
			description = "Directory containing one executable assignment file per shard")
	private Path assignmentsDir;
	@Option(names = "--head-inventory", description = "Versioned executable head inventory file")
	private Path headInventory;
	@Option(names = "--output", required = true, description = "Published schema-v3 map; replaced only after verification")
	private Path output;
	@Spec private CommandSpec spec;

	private final Clock clock;
	private final StagedFileHook stagedFileHook;

	public JoinFragmentsCommand()
	{
		this(Clock.systemUTC(), path -> { });
	}

	// Package-private injection is only for deterministic tests and post-write fault injection.
	JoinFragmentsCommand(Clock clock, StagedFileHook stagedFileHook)
	{
		this.clock = clock;
		this.stagedFileHook = stagedFileHook;
	}

	@FunctionalInterface
	interface StagedFileHook
	{
		void afterWrite(Path stagedFile) throws IOException;
	}

	@Override public Integer call()
	{
		try
		{
			Inputs inputs = readInputs();
			Set<ExecutableTestIdentity> nonExecutable = inputs.intentionallyNonExecutable();
			GeneratorProvenance generator = new GeneratorProvenance(new StpVersionProvider().getVersion()[0],
					null, null, System.getProperty("java.runtime.version"));
			ExecutableCoverageMap joined = new ExecutableCoverageFragmentJoiner().join(inputs.inventory(),
					inputs.plan(), inputs.fragments(), nonExecutable, clock.instant(), generator);
			ExecutableCoverageMap aligned = ExecutableCoverageMapPublication.alignExpectedInventory(
					joined, inputs.inventory(), nonExecutable);
			ExecutableCoverageMap published = new ExecutableCoverageMap(aligned.schemaVersion(), aligned.revision(),
					aligned.generatedAt(), aligned.generator(), aligned.tests(), aligned.unmapped(), aligned.setupScopes(),
					aligned.completeness(), aligned.statistics(), CoverageMapLifecycleState.PUBLISHED,
					aligned.methodCoverageReference());
			JsonObject summary = writeVerified(published, inputs.target());
			spec.commandLine().getOut().println(new GsonBuilder().setPrettyPrinting().create().toJson(summary));
			return 0;
		}
		catch (Exception error)
		{
			spec.commandLine().getErr().println("join-fragments: " + message(error));
			return 1;
		}
	}

	private Inputs readInputs() throws IOException
	{
		if (pairedDir != null)
		{
			if (fragmentsDir != null || assignmentsDir != null || headInventory != null)
			{
				throw new IllegalArgumentException("--dir cannot be combined with --fragments-dir, --assignments-dir or --head-inventory");
			}
			if (expectedShards == null || expectedShards <= 0)
			{
				throw new IllegalArgumentException("--dir requires a positive --expected-shards");
			}
			return readPairedInputs();
		}
		if (expectedShards != null)
		{
			throw new IllegalArgumentException("--expected-shards requires --dir");
		}
		if (fragmentsDir == null || assignmentsDir == null || headInventory == null)
		{
			throw new IllegalArgumentException("Provide --dir and --expected-shards, or all of --fragments-dir, --assignments-dir and --head-inventory");
		}
		return readLegacyInputs();
	}

	private Inputs readPairedInputs() throws IOException
	{
		Map<String, Path> fragmentFiles = new TreeMap<>();
		Map<String, Path> testFiles = new TreeMap<>();
		for (Path file : inputFiles(pairedDir))
		{
			var match = PAIR_FILE.matcher(file.getFileName().toString());
			if (!match.matches())
			{
				throw new IllegalArgumentException("Unexpected shard-directory file: " + file);
			}
			(match.group(1).equals("fragment") ? fragmentFiles : testFiles).put(match.group(2), file);
		}
		Set<String> names = new TreeSet<>(fragmentFiles.keySet());
		names.addAll(testFiles.keySet());
		for (String name : names)
		{
			if (!fragmentFiles.containsKey(name) || !testFiles.containsKey(name))
			{
				Path present = fragmentFiles.containsKey(name) ? fragmentFiles.get(name) : testFiles.get(name);
				Path missing = pairedDir.resolve((fragmentFiles.containsKey(name) ? "tests-" : "fragment-") + name + ".json");
				throw new IllegalArgumentException("Missing partner for " + present + ": expected " + missing);
			}
		}
		if (names.size() != expectedShards)
		{
			throw new IllegalArgumentException("Shard pair count mismatch: found " + names.size() + ", expected " + expectedShards);
		}
		Map<ShardId, Set<ExecutableTestIdentity>> assignments = new TreeMap<>();
		Map<ShardId, Path> assignmentSources = new TreeMap<>();
		Set<ExecutableTestIdentity> expectedTests = new TreeSet<>();
		Set<ExecutableTestIdentity> nonExecutable = new TreeSet<>();
		List<ExecutableCoverageFragment> fragments = new ArrayList<>();
		TargetBinding target = new TargetBinding();
		CoverageMapRevision revision = null;
		Path revisionSource = null;
		for (String name : names)
		{
			Path fragmentFile = fragmentFiles.get(name);
			Path testFile = testFiles.get(name);
			ExecutableShardAssignment assignment = read(testFile, bytes -> new ExecutableShardAssignmentCodec().deserialize(bytes));
			ExecutableCoverageFragment fragment = read(fragmentFile, bytes -> new ExecutableCoverageFragmentCodec().deserialize(bytes));
			if (!fragment.shardId().equals(assignment.shardId()) || !fragment.revision().equals(assignment.revision()))
			{
				throw new IllegalArgumentException("Pair shardId/revision mismatch: " + fragmentFile + " ("
						+ fragment.shardId().value() + ", " + fragment.revision().value() + ") and " + testFile
						+ " (" + assignment.shardId().value() + ", " + assignment.revision().value() + ")");
			}
			if (revision == null)
			{
				revision = assignment.revision();
				revisionSource = testFile;
			}
			else if (!revision.equals(assignment.revision()))
			{
				throw new IllegalArgumentException("Revision mismatch: " + revisionSource + " has " + revision.value()
						+ "; " + testFile + " has " + assignment.revision().value());
			}
			Path previous = assignmentSources.putIfAbsent(assignment.shardId(), testFile);
			if (previous != null)
			{
				throw new IllegalArgumentException("Duplicate shard assignment " + assignment.shardId().value()
						+ " in " + previous + " and " + testFile);
			}
			assignment.tests().forEach(test -> target.accept(test.target(), testFile));
			fragment.tests().keySet().forEach(test -> target.accept(test.target(), fragmentFile));
			fragment.unmapped().forEach(entry -> target.accept(entry.test().target(), fragmentFile));
			fragment.setupScopes().forEach(scope -> target.accept(scope.owner(), fragmentFile));
			Set<ExecutableTestIdentity> reported = new TreeSet<>(fragment.tests().keySet());
			fragment.unmapped().forEach(entry -> reported.add(entry.test()));
			Set<ExecutableTestIdentity> unexpected = new TreeSet<>(reported);
			unexpected.removeAll(assignment.tests());
			if (!unexpected.isEmpty())
			{
				throw new IllegalArgumentException("Unexpected tests in " + fragmentFile + " outside " + testFile + ": " + unexpected);
			}
			fragment.unmapped().stream().filter(entry -> entry.reason() == UnmappedReason.SKIPPED)
					.forEach(entry -> nonExecutable.add(entry.test()));
			Set<ExecutableTestIdentity> absent = new TreeSet<>(assignment.tests());
			absent.removeAll(reported);
			List<ExecutableUnmappedTest> unmapped = new ArrayList<>(fragment.unmapped());
			absent.forEach(test -> unmapped.add(new ExecutableUnmappedTest(test, UnmappedReason.ASSIGNED_NOT_REPORTED)));
			// Adapt only the in-memory fragment. Preserve its completion flag and every observed outcome.
			fragments.add(new ExecutableCoverageFragment(fragment.schemaVersion(), fragment.revision(), fragment.shardId(),
					fragment.tests(), unmapped, fragment.setupScopes(), fragment.collectionCompleted()));
			assignments.put(assignment.shardId(), assignment.tests());
			expectedTests.addAll(assignment.tests());
		}
		if (target.value == null)
		{
			throw new IllegalArgumentException("Cannot determine execution target from empty test lists in " + pairedDir);
		}
		return new Inputs(ExecutableTestInventory.from(revision, expectedTests), new ExecutableShardPlan(revision, assignments),
				fragments, target.value, Set.copyOf(nonExecutable));
	}

	private Inputs readLegacyInputs() throws IOException
	{
		List<Path> assignmentFiles = inputFiles(assignmentsDir);
		List<Path> fragmentFiles = inputFiles(fragmentsDir);
		ensureNotOutput(headInventory);
		ExecutableHeadTestInventory head = read(headInventory, bytes -> new ExecutableHeadTestInventoryCodec().deserialize(bytes));
		ExecutableTestInventory inventory = ExecutableTestInventory.from(new CoverageMapRevision(head.revision()), head.runnableTests());
		TargetBinding target = new TargetBinding();
		for (ExecutableTestIdentity test : new TreeSet<>(head.runnableTests()))
		{
			target.accept(test.target(), headInventory);
		}
		if (target.value == null)
		{
			throw new IllegalArgumentException("Cannot determine execution target from empty inventory: " + headInventory);
		}
		Map<ShardId, Set<ExecutableTestIdentity>> assignments = new TreeMap<>();
		Map<ShardId, Path> assignmentSources = new TreeMap<>();
		for (Path file : assignmentFiles)
		{
			ExecutableShardAssignment assignment = read(file, bytes -> new ExecutableShardAssignmentCodec().deserialize(bytes));
			checkRevision(assignment.revision(), inventory.revision(), file);
			Path previous = assignmentSources.putIfAbsent(assignment.shardId(), file);
			if (previous != null)
			{
				throw new IllegalArgumentException("Duplicate shard assignment " + assignment.shardId()
						+ " in " + previous + " and " + file);
			}
			for (ExecutableTestIdentity test : assignment.tests())
			{
				target.accept(test.target(), file);
			}
			assignments.put(assignment.shardId(), assignment.tests());
		}
		List<ExecutableCoverageFragment> fragments = new ArrayList<>();
		Set<ExecutableTestIdentity> nonExecutable = new TreeSet<>();
		for (Path file : fragmentFiles)
		{
			ExecutableCoverageFragment fragment = read(file, bytes -> new ExecutableCoverageFragmentCodec().deserialize(bytes));
			checkRevision(fragment.revision(), inventory.revision(), file);
			fragment.tests().keySet().forEach(test -> target.accept(test.target(), file));
			fragment.unmapped().forEach(test -> target.accept(test.test().target(), file));
			fragment.setupScopes().forEach(scope -> target.accept(scope.owner(), file));
			for (var entry : fragment.unmapped())
			{
				if (entry.reason() == UnmappedReason.SKIPPED)
				{
					if (!assignments.getOrDefault(fragment.shardId(), Set.of()).contains(entry.test()))
					{
						throw new IllegalArgumentException("SKIPPED identity " + entry.test() + " in " + file
								+ " does not belong to assignment for shard " + fragment.shardId().value());
					}
					// Positive runtime evidence only; absence from a fragment is not evidence of non-execution.
					nonExecutable.add(entry.test());
				}
			}
			fragments.add(fragment);
		}
		return new Inputs(inventory, new ExecutableShardPlan(inventory.revision(), assignments), fragments,
				target.value, Set.copyOf(nonExecutable));
	}

	private List<Path> inputFiles(Path directory) throws IOException
	{
		if (!Files.isDirectory(directory))
		{
			throw new IOException("Input directory not found: " + directory);
		}
		List<Path> files;
		try (var entries = Files.list(directory))
		{
			files = entries.sorted().toList();
		}
		for (Path file : files)
		{
			if (!Files.isRegularFile(file))
			{
				throw new IOException("Expected a direct input file, not a directory or special entry: " + file);
			}
			ensureNotOutput(file);
		}
		return files;
	}

	private void ensureNotOutput(Path input) throws IOException
	{
		if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
				|| (Files.exists(output) && Files.exists(input) && Files.isSameFile(input, output)))
		{
			throw new IOException("Output must not overwrite an input file: " + input);
		}
	}

	private void checkRevision(CoverageMapRevision actual, CoverageMapRevision expected, Path file)
	{
		if (!expected.equals(actual))
		{
			throw new IllegalArgumentException("Revision mismatch: " + file + " has " + actual.value()
					+ "; " + headInventory + " has " + expected.value());
		}
	}

	private JsonObject writeVerified(ExecutableCoverageMap expected, ExecutionTarget target) throws IOException
	{
		Path destination = output.toAbsolutePath().normalize();
		Files.createDirectories(destination.getParent());
		Path staged = Files.createTempFile(destination.getParent(), ".stp-join-", ".tmp");
		boolean installed = false;
		try
		{
			ExecutableCoverageMapCodec codec = new ExecutableCoverageMapCodec();
			Files.write(staged, codec.serialize(expected));
			stagedFileHook.afterWrite(staged);
			// Read the bytes back from disk, not from the serialization buffer.
			byte[] bytes = Files.readAllBytes(staged);
			ExecutableCoverageMap actual = codec.deserialize(bytes);
			var validation = codec.validate(actual);
			if (!validation.isValid())
			{
				throw new IllegalArgumentException("Written map is invalid: " + validation.errors());
			}
			if (!expected.equals(actual))
			{
				throw new IllegalArgumentException("Written map differs from joined map (identities, coverage, scopes or metadata)");
			}
			ExplicitPrSelectorFlow.selectionMap(bytes);
			JsonObject summary = summary(actual, target, bytes);
			// No non-atomic fallback: a failed publication must preserve an existing map.
			Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			installed = true;
			return summary;
		}
		catch (Exception error)
		{
			throw new IOException("Cannot publish " + destination + ": " + message(error), error);
		}
		finally
		{
			if (!installed)
			{
				Files.deleteIfExists(staged);
			}
		}
	}

	private static JsonObject summary(ExecutableCoverageMap map, ExecutionTarget target, byte[] bytes)
	{
		TreeSet<String> classes = new TreeSet<>();
		TreeSet<MethodIdentity> methods = new TreeSet<>();
		map.tests().values().forEach(coverage -> {
			classes.addAll(coverage.coveredClasses());
			methods.addAll(coverage.coveredMethods());
		});
		map.setupScopes().forEach(scope -> classes.addAll(scope.coveredClasses()));
		Map<String, Integer> reasons = new TreeMap<>();
		map.unmapped().forEach(test -> reasons.merge(test.reason().name(), 1, Integer::sum));
		JsonObject summary = new JsonObject();
		summary.addProperty("executionTarget", target.toString());
		summary.addProperty("revision", map.revision().value());
		summary.addProperty("shards", map.completeness().completedShards().size());
		summary.addProperty("mappedTests", map.tests().size());
		summary.addProperty("unmappedTests", map.unmapped().size());
		JsonObject byReason = new JsonObject();
		reasons.forEach(byReason::addProperty);
		summary.add("unmappedByReason", byReason);
		summary.addProperty("setupScopes", map.setupScopes().size());
		summary.addProperty("distinctClasses", classes.size());
		summary.addProperty("distinctMethods", methods.size());
		summary.addProperty("fileSizeBytes", bytes.length);
		summary.addProperty("checksum", JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
				.getAsJsonObject().get("checksum").getAsString());
		return summary;
	}

	private static <T> T read(Path file, Decoder<T> decoder) throws IOException
	{
		try
		{
			return decoder.decode(Files.readAllBytes(file));
		}
		catch (Exception error)
		{
			throw new IOException("Cannot read " + file + ": " + message(error), error);
		}
	}

	private static String message(Exception error)
	{
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}

	@FunctionalInterface private interface Decoder<T>
	{
		T decode(byte[] bytes) throws IOException;
	}
	private record Inputs(ExecutableTestInventory inventory, ExecutableShardPlan plan,
			List<ExecutableCoverageFragment> fragments, ExecutionTarget target,
			Set<ExecutableTestIdentity> intentionallyNonExecutable) { }

	private static final class TargetBinding
	{
		private ExecutionTarget value;
		private final Set<Path> sources = new TreeSet<>();
		private void accept(ExecutionTarget target, Path file)
		{
			if (value == null)
			{
				value = target;
			}
			else if (!value.equals(target))
			{
				throw new IllegalArgumentException("Conflicting execution targets: " + sources + " have " + value
						+ "; " + file + " has " + target);
			}
			sources.add(file);
		}
	}
}
