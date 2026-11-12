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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentJoiner;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageMapPublication;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventory;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectorFlow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class JoinFragmentsCommandTest
{
	private static final CoverageMapRevision REVISION = new CoverageMapRevision("a".repeat(40));
	private static final ExecutionTarget TARGET = ExecutionTarget.parse("ant:sample");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
	private final ExecutableCoverageFragmentCodec fragmentsCodec = new ExecutableCoverageFragmentCodec();
	private final ExecutableCoverageMapCodec mapsCodec = new ExecutableCoverageMapCodec();
	@TempDir Path root;
	private Path fragments;
	private Path assignments;
	private Path inventory;
	private Path output;

	@BeforeEach void fixture() throws Exception
	{
		fragments = Files.createDirectory(root.resolve("fragments"));
		assignments = Files.createDirectory(root.resolve("assignments"));
		inventory = root.resolve("head.json");
		output = root.resolve("map.json");
		new ExecutableHeadTestInventoryCodec().write(inventory.toFile(),
				ExecutableHeadTestInventory.atRevision(REVISION.value(), Set.of(test(1), test(2), test(3))));
		for (int shard = 1; shard <= 3; shard++)
		{
			writeAssignment(shard, REVISION, Set.of(test(shard)));
			writeFragment(shard, fragment(shard));
		}
	}

	@Test void threeShardsPublishExactMapAndSummary() throws Exception
	{
		Result result = run(command());
		assertEquals(0, result.code(), result.err());
		ExecutableCoverageMap actual = readMap();
		var inventoryModel = new ExecutableTestInventory(REVISION, Set.of(test(1), test(2), test(3)));
		var plan = new ExecutableShardPlan(REVISION, Map.of(shard(1), Set.of(test(1)),
				shard(2), Set.of(test(2)), shard(3), Set.of(test(3))));
		var generator = new GeneratorProvenance(new StpVersionProvider().getVersion()[0], null, null,
				System.getProperty("java.runtime.version"));
		var joined = new ExecutableCoverageFragmentJoiner().join(inventoryModel, plan,
				List.of(fragment(1), fragment(2), fragment(3)), Set.of(), CLOCK.instant(), generator);
		var aligned = ExecutableCoverageMapPublication.alignExpectedInventory(joined, inventoryModel, Set.of());
		assertEquals(withTests(aligned, aligned.tests(), CoverageMapLifecycleState.PUBLISHED), actual);
		assertTrue(mapsCodec.validate(actual).isValid());
		assertEquals(3, ExplicitPrSelectorFlow.selectionMap(Files.readAllBytes(output)).tests().size());
		JsonObject summary = result.summary();
		assertEquals(TARGET.toString(), summary.get("executionTarget").getAsString());
		assertEquals(REVISION.value(), summary.get("revision").getAsString());
		assertEquals(3, summary.get("shards").getAsInt());
		assertEquals(3, summary.get("mappedTests").getAsInt());
		assertEquals(0, summary.get("unmappedTests").getAsInt());
		assertEquals(0, summary.getAsJsonObject("unmappedByReason").size());
		assertEquals(1, summary.get("setupScopes").getAsInt());
		assertEquals(5, summary.get("distinctClasses").getAsInt());
		assertEquals(4, summary.get("distinctMethods").getAsInt());
		assertEquals(Files.size(output), summary.get("fileSizeBytes").getAsLong());
		assertEquals(JsonParser.parseString(Files.readString(output)).getAsJsonObject().get("checksum"), summary.get("checksum"));
		assertNoTemporaryFiles();
	}

	@Test void commandIsRegisteredAndSupportsMavenTargets() throws Exception
	{
		ExecutionTarget maven = ExecutionTarget.parse("maven:.");
		Set<ExecutableTestIdentity> all = new java.util.TreeSet<>();
		for (int i = 1; i <= 3; i++)
		{
			var identity = new ExecutableTestIdentity(maven, test(i).test());
			all.add(identity);
			writeAssignment(i, REVISION, Set.of(identity));
			writeFragment(i, new ExecutableCoverageFragment(3, REVISION, shard(i),
					Map.of(identity, coverage(i)), List.of(), List.of(), true));
		}
		new ExecutableHeadTestInventoryCodec().write(inventory.toFile(), ExecutableHeadTestInventory.atRevision(REVISION.value(), all));
		CommandLine rootCommand = new CommandLine(new SmartTestPickerCli());
		assertTrue(rootCommand.getSubcommands().containsKey("join-fragments"));
		StringWriter out = new StringWriter();
		rootCommand.setOut(new PrintWriter(out));
		List<String> arguments = new ArrayList<>(List.of("join-fragments"));
		arguments.addAll(List.of(arguments()));
		assertEquals(0, rootCommand.execute(arguments.toArray(String[]::new)));
		assertEquals(all, readMap().tests().keySet());
		assertEquals("maven:.", JsonParser.parseString(out.toString()).getAsJsonObject().get("executionTarget").getAsString());
	}

	@Test void missingShardCreatesNoMapAndPreservesExistingMap() throws Exception
	{
		Files.delete(fragmentPath(2));
		assertFailure(run(command()), "missing shards", "2");
		assertFalse(Files.exists(output));
		byte[] previous = "previous published map".getBytes(StandardCharsets.UTF_8);
		Files.write(output, previous);
		assertFailure(run(command()), "missing shards");
		assertArrayEquals(previous, Files.readAllBytes(output));
		assertNoTemporaryFiles();
	}

	@Test void duplicateFragmentIsRejectedByJoiner() throws Exception
	{
		Files.copy(fragmentPath(1), fragments.resolve("duplicate.json"));
		assertFailure(run(command()), "duplicate shard report", "1");
		assertFalse(Files.exists(output));
	}

	@Test void conflictingFragmentTargetsNameBothFiles() throws Exception
	{
		var other = new ExecutableTestIdentity(ExecutionTarget.parse("ant:other"), test(2).test());
		writeFragment(2, new ExecutableCoverageFragment(3, REVISION, shard(2), Map.of(other, coverage(2)), List.of(), List.of(), true));
		assertFailure(run(command()), "Conflicting execution targets", fragmentPath(1).toString(), fragmentPath(2).toString());
	}

	@Test void assignmentTargetMismatchNamesInventoryAndAssignment() throws Exception
	{
		writeAssignment(2, REVISION, Set.of(new ExecutableTestIdentity(ExecutionTarget.parse("ant:other"), test(2).test())));
		assertFailure(run(command()), "Conflicting execution targets", inventory.toString(), assignmentPath(2).toString());
	}

	@Test void mixedInventoryTargetsAreRejected() throws Exception
	{
		new ExecutableHeadTestInventoryCodec().write(inventory.toFile(), ExecutableHeadTestInventory.atRevision(REVISION.value(),
				Set.of(test(1), new ExecutableTestIdentity(ExecutionTarget.parse("ant:other"), test(2).test()))));
		assertFailure(run(command()), "Conflicting execution targets", inventory.toString());
	}

	@Test void setupScopeTargetCannotEscapeTargetCheck() throws Exception
	{
		SetupScope scope = new SetupScope("setup", SetupScopeType.CONTAINER, Set.of("example.SetupOnly"),
				Set.of(new TestContainer("example.Test1")), ExecutionTarget.parse("ant:other"));
		writeFragment(1, new ExecutableCoverageFragment(3, REVISION, shard(1), Map.of(test(1), coverage(1)), List.of(), List.of(scope), true));
		assertFailure(run(command()), "Conflicting execution targets", fragmentPath(1).toString());
	}

	@Test void fragmentRevisionMismatchNamesBothInputs() throws Exception
	{
		writeFragment(2, new ExecutableCoverageFragment(3, new CoverageMapRevision("b".repeat(40)), shard(2),
				Map.of(test(2), coverage(2)), List.of(), List.of(), true));
		assertFailure(run(command()), "Revision mismatch", fragmentPath(2).toString(), inventory.toString());
	}

	@Test void assignmentRevisionMismatchIsRejected() throws Exception
	{
		writeAssignment(2, new CoverageMapRevision("b".repeat(40)), Set.of(test(2)));
		assertFailure(run(command()), "Revision mismatch", assignmentPath(2).toString(), inventory.toString());
	}

	@Test void corruptWrittenByteIsRejectedAndStagingIsRemoved() throws Exception
	{
		assertFailure(run(corruptingCommand()), "checksum mismatch");
		assertFalse(Files.exists(output));
		assertNoTemporaryFiles();
	}

	@Test void corruptWriteDoesNotReplaceExistingOutput() throws Exception
	{
		Files.writeString(output, "old map");
		assertFailure(run(corruptingCommand()), "checksum mismatch");
		assertEquals("old map", Files.readString(output));
		assertNoTemporaryFiles();
	}

	@Test void validChecksumButChangedCoverageFailsEqualityCheck() throws Exception
	{
		JoinFragmentsCommand command = new JoinFragmentsCommand(CLOCK, path -> {
			ExecutableCoverageMap map = mapsCodec.deserialize(Files.readAllBytes(path));
			Map<ExecutableTestIdentity, TestCoverage> changed = new TreeMap<>(map.tests());
			changed.put(test(1), coverage(2));
			Files.write(path, mapsCodec.serialize(withTests(map, changed, map.lifecycleState())));
		});
		assertFailure(run(command), "Written map differs from joined map");
		assertFalse(Files.exists(output));
		assertNoTemporaryFiles();
	}

	@Test void failedAndCollectionFailedRemainUnmappedInPublishedMap() throws Exception
	{
		writeFragment(2, unmapped(2, UnmappedReason.FAILED, TARGET));
		writeFragment(3, unmapped(3, UnmappedReason.COLLECTION_FAILED, TARGET));
		Result result = run(command());
		assertEquals(0, result.code(), result.err());
		assertEquals(Set.of(test(1)), readMap().tests().keySet());
		assertEquals(List.of(new ExecutableUnmappedTest(test(2), UnmappedReason.FAILED),
				new ExecutableUnmappedTest(test(3), UnmappedReason.COLLECTION_FAILED)), readMap().unmapped());
		assertEquals(1, result.summary().getAsJsonObject("unmappedByReason").get("FAILED").getAsInt());
		assertEquals(1, result.summary().getAsJsonObject("unmappedByReason").get("COLLECTION_FAILED").getAsInt());
		assertEquals(2, ExplicitPrSelectorFlow.selectionMap(Files.readAllBytes(output)).unmapped().size());
	}

	@Test void validChecksumButChangedSetupScopeFailsEqualityCheck() throws Exception
	{
		JoinFragmentsCommand command = new JoinFragmentsCommand(CLOCK, path -> {
			ExecutableCoverageMap map = mapsCodec.deserialize(Files.readAllBytes(path));
			SetupScope changed = new SetupScope("setup", SetupScopeType.CONTAINER, Set.of("example.OtherSetup"),
					Set.of(new TestContainer("example.Test1")), TARGET);
			var altered = new ExecutableCoverageMap(map.schemaVersion(), map.revision(), map.generatedAt(), map.generator(),
					map.tests(), map.unmapped(), List.of(changed), map.completeness(), map.statistics(), map.lifecycleState(), null);
			Files.write(path, mapsCodec.serialize(altered));
		});
		assertFailure(run(command), "Written map differs from joined map");
		assertFalse(Files.exists(output));
		assertNoTemporaryFiles();
	}

	@Test void nonPublishedMapIsRejectedEvenWithValidChecksum() throws Exception
	{
		JoinFragmentsCommand command = new JoinFragmentsCommand(CLOCK, path -> {
			ExecutableCoverageMap map = mapsCodec.deserialize(Files.readAllBytes(path));
			Files.write(path, mapsCodec.serialize(withTests(map, map.tests(), CoverageMapLifecycleState.CANDIDATE)));
		});
		assertFailure(run(command), "Only PUBLISHED executable maps may be selector input");
		assertFalse(Files.exists(output));
	}

	@Test void unsupportedOutputSchemaIsRejected() throws Exception
	{
		JoinFragmentsCommand command = new JoinFragmentsCommand(CLOCK, path -> {
			ExecutableCoverageMap map = mapsCodec.deserialize(Files.readAllBytes(path));
			var altered = new ExecutableCoverageMap(4, map.revision(), map.generatedAt(), map.generator(),
					map.tests(), map.unmapped(), map.setupScopes(), map.completeness(), map.statistics(), map.lifecycleState(), null);
			Files.write(path, mapsCodec.serialize(altered));
		});
		assertFailure(run(command), "Unsupported executable coverage-map schema version: 4");
		assertFalse(Files.exists(output));
	}

	@Test void unmappedTargetCannotEscapeTargetCheck() throws Exception
	{
		writeFragment(2, unmapped(2, UnmappedReason.FAILED, ExecutionTarget.parse("ant:other")));
		assertFailure(run(command()), "Conflicting execution targets", fragmentPath(2).toString());
	}

	@Test void incompleteCollectionRemainsRejected() throws Exception
	{
		writeFragment(2, new ExecutableCoverageFragment(3, REVISION, shard(2), Map.of(test(2), coverage(2)), List.of(), List.of(), false));
		assertFailure(run(command()), "Fragment collection is not completed");
		assertFalse(Files.exists(output));
	}

	@Test void skippedEntrySuppliesPositiveNonExecution() throws Exception
	{
		writeFragment(2, unmapped(2, UnmappedReason.SKIPPED, TARGET));
		Result result = run(command());
		assertEquals(0, result.code(), result.err());
		ExecutableCoverageMap map = readMap();
		assertEquals(Set.of(test(1), test(3)), map.tests().keySet());
		assertTrue(map.unmapped().isEmpty());
		assertEquals(Set.of(test(1), test(2), test(3)), map.completeness().reportedTests());
		assertTrue(map.completeness().missingTests().isEmpty());
		assertTrue(map.completeness().isComplete());
		// The existing schema accounts positive non-execution without a separate serialized field.
		var nonExecuted = new java.util.TreeSet<>(map.completeness().reportedTests());
		nonExecuted.removeAll(map.tests().keySet());
		map.unmapped().forEach(entry -> nonExecuted.remove(entry.test()));
		assertEquals(Set.of(test(2)), nonExecuted);
		assertEquals(2, result.summary().get("mappedTests").getAsInt());
		assertEquals(0, result.summary().get("unmappedTests").getAsInt());
	}

	@Test void skippedEntryMustBelongToReportingShardAssignment() throws Exception
	{
		writeFragment(2, new ExecutableCoverageFragment(3, REVISION, shard(2), Map.of(),
				List.of(new ExecutableUnmappedTest(test(1), UnmappedReason.SKIPPED)), List.of(), true));
		assertFailure(run(command()), "SKIPPED identity", test(1).toString(), fragmentPath(2).toString(),
				"does not belong to assignment for shard 2");
		assertFalse(Files.exists(output));
	}

	@Test void positiveNonExecutionIsUnionOfSkippedEntriesAcrossFragments() throws Exception
	{
		writeFragment(1, unmapped(1, UnmappedReason.SKIPPED, TARGET));
		writeFragment(3, unmapped(3, UnmappedReason.SKIPPED, TARGET));
		Result result = run(command());
		assertEquals(0, result.code(), result.err());
		ExecutableCoverageMap map = readMap();
		assertEquals(Set.of(test(2)), map.tests().keySet());
		assertTrue(map.unmapped().isEmpty());
		assertEquals(Set.of(test(1), test(2), test(3)), map.completeness().reportedTests());
		assertTrue(map.completeness().isComplete());
	}

	@Test void skippedEntryWithoutShardAssignmentNamesFragment() throws Exception
	{
		writeFragment(4, new ExecutableCoverageFragment(3, REVISION, shard(4), Map.of(),
				List.of(new ExecutableUnmappedTest(test(1), UnmappedReason.SKIPPED)), List.of(), true));
		assertFailure(run(command()), "SKIPPED identity", fragmentPath(4).toString(),
				"does not belong to assignment for shard 4");
		assertFalse(Files.exists(output));
	}

	@Test void missingIdentityIsNotAbsorbedAsNonExecutable() throws Exception
	{
		writeFragment(1, unmapped(1, UnmappedReason.SKIPPED, TARGET));
		writeFragment(2, new ExecutableCoverageFragment(3, REVISION, shard(2), Map.of(), List.of(), List.of(), true));
		assertFailure(run(command()), "missing", test(2).toString());
		assertFalse(Files.exists(output));
	}

	@Test void skippedIdentityAlsoMappedByAnotherShardIsRejected() throws Exception
	{
		writeFragment(1, unmapped(1, UnmappedReason.SKIPPED, TARGET));
		writeFragment(2, new ExecutableCoverageFragment(3, REVISION, shard(2),
				Map.of(test(1), coverage(1), test(2), coverage(2)), List.of(), List.of(), true));
		assertFailure(run(command()), "unexpected", test(1).toString());
		assertFalse(Files.exists(output));
	}

	@Test void skippedIdentityAlsoUnmappedByAnotherShardIsRejected() throws Exception
	{
		writeFragment(1, unmapped(1, UnmappedReason.SKIPPED, TARGET));
		writeFragment(2, new ExecutableCoverageFragment(3, REVISION, shard(2), Map.of(test(2), coverage(2)),
				List.of(new ExecutableUnmappedTest(test(1), UnmappedReason.FAILED)), List.of(), true));
		assertFailure(run(command()), "unexpected", test(1).toString());
		assertFalse(Files.exists(output));
	}

	@Test void duplicateAssignmentFileCannotBeSilentlyOverwritten() throws Exception
	{
		Files.copy(assignmentPath(1), assignments.resolve("duplicate.json"));
		assertFailure(run(command()), "Duplicate shard assignment", "duplicate.json", "assignment-1.json");
	}

	@Test void duplicateAssignmentOwnershipAndNonPartitionRemainErrors() throws Exception
	{
		writeAssignment(2, REVISION, Set.of(test(1), test(2)));
		assertFailure(run(command()), "assigned to more than one shard");
		writeAssignment(2, REVISION, Set.of());
		assertFailure(run(command()), "not an exact inventory partition");
	}

	@Test void unexpectedShardRemainsAnError() throws Exception
	{
		Files.write(fragments.resolve("extra.json"), fragmentsCodec.serialize(new ExecutableCoverageFragment(
				3, REVISION, shard(4), Map.of(), List.of(), List.of(), true)));
		assertFailure(run(command()), "unexpected shards", "4");
	}

	@Test void malformedInputsNameTheirFilesWithoutExtensionFiltering() throws Exception
	{
		Path stray = fragments.resolve("broken.data");
		Files.writeString(stray, "not json");
		assertFailure(run(command()), "Cannot read", stray.toString());
		Files.delete(stray);
		Files.writeString(assignmentPath(1), "not json");
		assertFailure(run(command()), "Cannot read", assignmentPath(1).toString());
		writeAssignment(1, REVISION, Set.of(test(1)));
		Files.writeString(inventory, "not json");
		assertFailure(run(command()), "Cannot read", inventory.toString());
		assertFalse(Files.exists(output));
	}

	@Test void emptyShardWithMatchingAssignmentIsSupported() throws Exception
	{
		writeAssignment(4, REVISION, Set.of());
		writeFragment(4, new ExecutableCoverageFragment(3, REVISION, shard(4), Map.of(), List.of(), List.of(), true));
		Result result = run(command());
		assertEquals(0, result.code(), result.err());
		assertEquals(4, result.summary().get("shards").getAsInt());
	}

	@Test void emptyInventoryCannotDetermineARecipeTarget() throws Exception
	{
		new ExecutableHeadTestInventoryCodec().write(inventory.toFile(), ExecutableHeadTestInventory.atRevision(REVISION.value(), Set.of()));
		assertFailure(run(command()), "Cannot determine execution target", inventory.toString());
	}

	@Test void inputCannotBeOverwrittenAsOutput() throws Exception
	{
		byte[] original = Files.readAllBytes(inventory);
		output = inventory;
		assertFailure(run(command()), "Output must not overwrite an input file");
		assertArrayEquals(original, Files.readAllBytes(inventory));
	}

	@Test void directoryEntriesAreRejectedInsteadOfSilentlySkipped() throws Exception
	{
		Files.createDirectory(fragments.resolve("nested"));
		assertFailure(run(command()), "Expected a direct input file", "nested");
	}

	@Test void publicationFailureRemovesStagingAndPreservesDestination() throws Exception
	{
		Files.createDirectory(output);
		Files.writeString(output.resolve("keep"), "unchanged");
		assertFailure(run(command()), "Cannot publish");
		assertEquals("unchanged", Files.readString(output.resolve("keep")));
		assertNoTemporaryFiles();
	}

	private JoinFragmentsCommand command() { return new JoinFragmentsCommand(CLOCK, path -> { }); }
	private JoinFragmentsCommand corruptingCommand()
	{
		return new JoinFragmentsCommand(CLOCK, path -> {
			byte[] bytes = Files.readAllBytes(path);
			int index = new String(bytes, StandardCharsets.UTF_8).indexOf("example.Shared");
			assertTrue(index >= 0);
			bytes[index] = 'x';
			Files.write(path, bytes);
		});
	}
	private Result run(JoinFragmentsCommand command)
	{
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();
		CommandLine cli = new CommandLine(command).setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
		int code = cli.execute(arguments());
		return new Result(code, out.toString(), err.toString());
	}
	private String[] arguments()
	{
		return new String[] {"--fragments-dir", fragments.toString(), "--assignments-dir", assignments.toString(),
				"--head-inventory", inventory.toString(), "--output", output.toString()};
	}
	private static void assertFailure(Result result, String... messages)
	{
		assertNotEquals(0, result.code());
		assertEquals("", result.out(), "A failed command must not print a success summary");
		for (String message : messages)
		{
			assertTrue(result.err().contains(message), result.err());
		}
	}
	private void assertNoTemporaryFiles() throws Exception
	{
		try (var files = Files.list(root))
		{
			assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".stp-join-")));
		}
	}
	private ExecutableCoverageMap readMap() throws Exception { return mapsCodec.deserialize(Files.readAllBytes(output)); }
	private Path fragmentPath(int i) { return fragments.resolve("fragment-" + i + ".json"); }
	private Path assignmentPath(int i) { return assignments.resolve("assignment-" + i + ".json"); }
	private void writeFragment(int i, ExecutableCoverageFragment fragment) throws Exception
	{
		Files.write(fragmentPath(i), fragmentsCodec.serialize(fragment));
	}
	private void writeAssignment(int i, CoverageMapRevision revision, Set<ExecutableTestIdentity> tests) throws Exception
	{
		Files.write(assignmentPath(i), new ExecutableShardAssignmentCodec().serialize(new ExecutableShardAssignment(1, revision, shard(i), tests)));
	}
	private static ShardId shard(int i) { return new ShardId(Integer.toString(i)); }
	private static ExecutableTestIdentity test(int i)
	{
		return new ExecutableTestIdentity(TARGET, new TestIdentity("example.Test" + i, "works"));
	}
	private static TestCoverage coverage(int i)
	{
		return new TestCoverage(Set.of("example.Shared", "example.Production" + i),
				Set.of(new MethodIdentity("example.Shared", "shared", "()V"),
						new MethodIdentity("example.Production" + i, "work", "()I")),
				TestOutcome.PASS, CollectionStatus.COLLECTED_WITH_COVERAGE);
	}
	private static ExecutableCoverageFragment fragment(int i)
	{
		List<SetupScope> scopes = i == 1 ? List.of(new SetupScope("setup", SetupScopeType.CONTAINER,
				Set.of("example.SetupOnly"), Set.of(new TestContainer("example.Test1")), TARGET)) : List.of();
		return new ExecutableCoverageFragment(3, REVISION, shard(i), Map.of(test(i), coverage(i)), List.of(), scopes, true);
	}
	private static ExecutableCoverageFragment unmapped(int i, UnmappedReason reason, ExecutionTarget target)
	{
		return new ExecutableCoverageFragment(3, REVISION, shard(i), Map.of(),
				List.of(new ExecutableUnmappedTest(new ExecutableTestIdentity(target, test(i).test()), reason)), List.of(), true);
	}
	private static ExecutableCoverageMap withTests(ExecutableCoverageMap map,
			Map<ExecutableTestIdentity, TestCoverage> tests, CoverageMapLifecycleState state)
	{
		return new ExecutableCoverageMap(map.schemaVersion(), map.revision(), map.generatedAt(), map.generator(),
				tests, map.unmapped(), map.setupScopes(), map.completeness(), map.statistics(), state, map.methodCoverageReference());
	}
	private record Result(int code, String out, String err)
	{
		JsonObject summary() { return JsonParser.parseString(out).getAsJsonObject(); }
	}
}
