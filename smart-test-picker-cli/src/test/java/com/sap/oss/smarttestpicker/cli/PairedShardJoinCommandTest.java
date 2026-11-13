// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectorFlow;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionStatus;
import com.sap.oss.smarttestpicker.selector.HeadTestInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class PairedShardJoinCommandTest
{
	@TempDir Path root;
	private Path directory;
	private Path output;
	private CoverageMapRevision revision = new CoverageMapRevision("a".repeat(40));
	private static final ExecutionTarget TARGET = ExecutionTarget.parse("ant:sample");
	private final ExecutableCoverageFragmentCodec fragments = new ExecutableCoverageFragmentCodec();
	private final ExecutableShardAssignmentCodec assignments = new ExecutableShardAssignmentCodec();
	private final ExecutableCoverageMapCodec maps = new ExecutableCoverageMapCodec();

	@BeforeEach void fixture() throws Exception
	{
		directory = Files.createDirectory(root.resolve("shards"));
		output = root.resolve("map.json");
		writePairs();
	}

	@Test void threeConsistentPairsPublishMapThroughRegisteredCommand() throws Exception
	{
		Result result = run(new CommandLine(new SmartTestPickerCli()), true);
		assertEquals(0, result.code(), result.error());
		var map = readMap();
		assertEquals(Set.of(test(1), test(2), test(3)), map.tests().keySet());
		assertEquals(CoverageMapLifecycleState.PUBLISHED, map.lifecycleState());
		assertTrue(map.completeness().isComplete());
		assertEquals(3, JsonParser.parseString(result.out()).getAsJsonObject().get("shards").getAsInt());
		assertTrue(maps.validate(map).isValid());
	}

	@Test void missingListNamesFragmentAndPreservesPreviousMap() throws Exception
	{
		Files.delete(list(2));
		Files.writeString(output, "previous map");
		fails(run(), "Missing partner", fragment(2).toString(), list(2).toString());
		assertEquals("previous map", Files.readString(output));
	}

	@Test void missingFragmentNamesListAndCreatesNoMap() throws Exception
	{
		Files.delete(fragment(2));
		fails(run(), "Missing partner", list(2).toString(), fragment(2).toString());
		assertFalse(Files.exists(output));
	}

	@Test void tooFewPairsReportFoundAndExpected() throws Exception
	{
		Files.delete(fragment(3)); Files.delete(list(3));
		fails(run(), "found 2, expected 3");
	}

	@Test void tooManyPairsReportFoundAndExpected() throws Exception
	{
		fails(runWith("--dir", directory.toString(), "--expected-shards", "2", "--output", output.toString()),
				"found 3, expected 2");
	}

	@Test void assignedButNotReportedIsMandatoryThroughRealExplicitSelection() throws Exception
	{
		Path repo = Files.createDirectory(root.resolve("repo"));
		git(repo, "init", "-q");
		Files.writeString(repo.resolve("README.md"), "Fixture\n");
		git(repo, "add", "README.md");
		git(repo, "commit", "-qm", "Base");
		revision = new CoverageMapRevision(git(repo, "rev-parse", "HEAD"));
		writePairs();
		writeFragment(2, report(2, Map.of(), List.of(), true));
		byte[] original = Files.readAllBytes(fragment(2));
		Result joined = run();
		assertEquals(0, joined.code(), joined.error());
		var map = readMap();
		assertEquals(Set.of(test(1), test(3)), map.tests().keySet());
		assertEquals(List.of(new ExecutableUnmappedTest(test(2), UnmappedReason.ASSIGNED_NOT_REPORTED)), map.unmapped());
		assertTrue(map.completeness().isComplete());
		assertArrayEquals(original, Files.readAllBytes(fragment(2)));
		assertEquals(1, JsonParser.parseString(joined.out()).getAsJsonObject().getAsJsonObject("unmappedByReason")
				.get("ASSIGNED_NOT_REPORTED").getAsInt());
		git(repo, "commit", "--allow-empty", "-qm", "No source changes");
		String head = git(repo, "rev-parse", "HEAD");
		var selected = new ExplicitPrSelectorFlow().select(output.toFile(), repo.toFile(),
				HeadTestInventory.atRevision(head, List.of(test(1).test(), test(2).test(), test(3).test())),
				revision.value(), revision.value(), head, 10, List.of());
		assertEquals(ExplicitPrSelectionStatus.SELECTION_RESULT, selected.status(), selected.reason());
		assertEquals("SELECTED", selected.output().orElseThrow().getStatus());
		assertEquals(List.of(test(2).test().toString()), selected.output().orElseThrow().getSelectedTests());
		assertEquals("ASSIGNED_NOT_REPORTED", selected.output().orElseThrow().getUnmappedTests().get(test(2).test().toString()));
	}

	@Test void unexpectedMappedTestFails() throws Exception
	{
		writeFragment(2, report(2, Map.of(test(1), coverage()), List.of(), true));
		fails(run(), "Unexpected tests", fragment(2).toString(), list(2).toString(), test(1).toString());
	}

	@Test void unexpectedUnmappedTestFails() throws Exception
	{
		writeFragment(2, report(2, Map.of(), List.of(new ExecutableUnmappedTest(test(1), UnmappedReason.FAILED)), true));
		fails(run(), "Unexpected tests", fragment(2).toString(), test(1).toString());
	}

	@Test void shardIdMismatchNamesPair() throws Exception
	{
		writeFragment(2, report(1, Map.of(test(2), coverage()), List.of(), true));
		fails(run(), "Pair shardId/revision mismatch", fragment(2).toString(), list(2).toString());
	}

	@Test void revisionMismatchInsidePairNamesPair() throws Exception
	{
		Files.write(list(2), assignments.serialize(new ExecutableShardAssignment(1, new CoverageMapRevision("b".repeat(40)),
				new ShardId("2"), Set.of(test(2)))));
		fails(run(), "Pair shardId/revision mismatch", fragment(2).toString(), list(2).toString());
	}

	@Test void differentPairRevisionsFail() throws Exception
	{
		revision = new CoverageMapRevision("b".repeat(40));
		writeAssignment(2, Set.of(test(2))); writeFragment(2, report(2, Map.of(test(2), coverage()), List.of(), true));
		fails(run(), "Revision mismatch", list(1).toString(), list(2).toString());
	}

	@Test void strayFileFailsAndNamesFile() throws Exception
	{
		Path stray = directory.resolve("notes.txt"); Files.writeString(stray, "ignored?");
		fails(run(), "Unexpected shard-directory file", stray.toString());
	}

	@Test void hiddenFilesAreSkippedAndLoggedOnceEach() throws Exception
	{
		Path finder = directory.resolve(".DS_Store");
		Path hidden = directory.resolve(".notes");
		Files.writeString(finder, "not JSON"); Files.writeString(hidden, "not JSON either");
		Result result = run();
		assertEquals(0, result.code(), result.error());
		assertEquals(Set.of(test(1), test(2), test(3)), readMap().tests().keySet());
		assertEquals(List.of("Skipping hidden shard-directory file: " + finder,
				"Skipping hidden shard-directory file: " + hidden), result.error().lines().toList());
		assertEquals("not JSON", Files.readString(finder));
	}

	@Test void emptyNameIsNotAPair() throws Exception
	{
		Path stray = directory.resolve("tests-.json"); Files.copy(list(1), stray);
		fails(run(), "Unexpected shard-directory file", stray.toString());
	}

	@Test void filenamePairingDoesNotFallBackToShardId() throws Exception
	{
		Files.move(list(2), directory.resolve("tests-other.json"));
		fails(run(), "Missing partner", directory.resolve("tests-other.json").toString(),
				directory.resolve("fragment-other.json").toString());
	}

	@Test void mixedTargetsFail() throws Exception
	{
		var other = new ExecutableTestIdentity(ExecutionTarget.parse("ant:other"), test(2).test());
		writeAssignment(2, Set.of(other)); writeFragment(2, report(2, Map.of(other, coverage()), List.of(), true));
		fails(run(), "Conflicting execution targets", list(1).toString(), list(2).toString());
	}

	@Test void duplicateShardIdsUnderDifferentNamesFail() throws Exception
	{
		Files.copy(list(1), list(2), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.copy(fragment(1), fragment(2), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		fails(run(), "Duplicate shard assignment", list(1).toString(), list(2).toString());
	}

	@Test void duplicateTestOwnershipStillFails() throws Exception
	{
		writeAssignment(2, Set.of(test(1), test(2)));
		fails(run(), "assigned to more than one shard");
	}

	@Test void skippedIsPositiveNonExecutionAndNotAbsorbed() throws Exception
	{
		writeFragment(2, report(2, Map.of(), List.of(new ExecutableUnmappedTest(test(2), UnmappedReason.SKIPPED)), true));
		Result result = run(); assertEquals(0, result.code(), result.error());
		var map = readMap();
		assertEquals(Set.of(test(1), test(3)), map.tests().keySet());
		assertTrue(map.unmapped().isEmpty());
		assertEquals(Set.of(test(1), test(2), test(3)), map.completeness().reportedTests());
	}

	@Test void skippedOutsideAssignmentStillFails() throws Exception
	{
		writeFragment(2, report(2, Map.of(), List.of(new ExecutableUnmappedTest(test(1), UnmappedReason.SKIPPED)), true));
		fails(run(), "Unexpected tests", fragment(2).toString());
	}

	@Test void incompleteFragmentIsNotMadeCompleteByAbsorption() throws Exception
	{
		writeFragment(2, report(2, Map.of(), List.of(), false));
		fails(run(), "Fragment collection is not completed");
		assertFalse(Files.exists(output));
	}

	@Test void malformedPairFileNamesFile() throws Exception
	{
		Files.writeString(list(2), "not JSON");
		fails(run(), "Cannot read", list(2).toString());
		writeAssignment(2, Set.of(test(2))); Files.writeString(fragment(2), "not JSON");
		fails(run(), "Cannot read", fragment(2).toString());
	}

	@Test void newAndLegacyOptionsCannotBeCombined() throws Exception
	{
		for (String option : List.of("--fragments-dir", "--assignments-dir", "--head-inventory"))
		{
			fails(runWith("--dir", directory.toString(), "--expected-shards", "3", option, directory.toString(),
					"--output", output.toString()), "--dir cannot be combined");
		}
	}

	@Test void expectedCountIsRequiredAndPositiveOnlyInPairedMode() throws Exception
	{
		fails(runWith("--dir", directory.toString(), "--output", output.toString()), "positive --expected-shards");
		for (String count : List.of("0", "-1"))
		{
			fails(runWith("--dir", directory.toString(), "--expected-shards", count, "--output", output.toString()),
					"positive --expected-shards");
		}
		fails(runWith("--expected-shards", "3", "--output", output.toString()), "--expected-shards requires --dir");
		fails(runWith("--output", output.toString()), "Provide --dir");
	}

	@Test void corruptPairedOutputPreservesPreviousMap() throws Exception
	{
		Files.writeString(output, "previous map");
		JoinFragmentsCommand command = new JoinFragmentsCommand(Clock.systemUTC(), staged -> Files.writeString(staged, "broken"));
		fails(run(new CommandLine(command), false), "Cannot publish");
		assertEquals("previous map", Files.readString(output));
		try (var files = Files.list(root)) { assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".stp-join-"))); }
	}

	private void writePairs() throws Exception
	{
		for (int i = 1; i <= 3; i++)
		{
			writeAssignment(i, Set.of(test(i)));
			writeFragment(i, report(i, Map.of(test(i), coverage()), List.of(), true));
		}
	}
	private Path fragment(int i) { return directory.resolve("fragment-part-" + i + ".json"); }
	private Path list(int i) { return directory.resolve("tests-part-" + i + ".json"); }
	private void writeAssignment(int i, Set<ExecutableTestIdentity> tests) throws Exception
	{
		Files.write(list(i), assignments.serialize(new ExecutableShardAssignment(1, revision, new ShardId("" + i), tests)));
	}
	private void writeFragment(int i, ExecutableCoverageFragment fragment) throws Exception { Files.write(fragment(i), fragments.serialize(fragment)); }
	private ExecutableCoverageFragment report(int i, Map<ExecutableTestIdentity, TestCoverage> tests,
			List<ExecutableUnmappedTest> unmapped, boolean complete)
	{
		return new ExecutableCoverageFragment(3, revision, new ShardId("" + i), tests, unmapped, List.of(), complete);
	}
	private static ExecutableTestIdentity test(int i) { return new ExecutableTestIdentity(TARGET, new TestIdentity("example.Test" + i, "works")); }
	private static TestCoverage coverage() { return new TestCoverage(Set.of("example.Production"), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_WITH_COVERAGE); }
	private ExecutableCoverageMap readMap() throws Exception { return maps.read(output); }
	private Result run() { return run(new CommandLine(new JoinFragmentsCommand()), false); }
	private Result run(CommandLine command, boolean rootCommand)
	{
		List<String> args = new ArrayList<>();
		if (rootCommand) { args.add("join-fragments"); }
		args.addAll(List.of("--dir", directory.toString(), "--expected-shards", "3", "--output", output.toString()));
		return execute(command, args.toArray(String[]::new));
	}
	private Result runWith(String... args) { return execute(new CommandLine(new JoinFragmentsCommand()), args); }
	private Result execute(CommandLine command, String... args)
	{
		StringWriter out = new StringWriter(); StringWriter error = new StringWriter();
		command.setOut(new PrintWriter(out)); command.setErr(new PrintWriter(error));
		int code = command.execute(args);
		return new Result(code, out.toString(), error.toString());
	}
	private static void fails(Result result, String... messages)
	{
		assertNotEquals(0, result.code());
		for (String message : messages) { assertTrue(result.error().contains(message), result.error()); }
	}
	private static String git(Path repo, String... args) throws Exception
	{
		List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
				"-c", "commit.gpgsign=false"));
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), output);
		return output.trim();
	}
	private record Result(int code, String out, String error) { }
}
