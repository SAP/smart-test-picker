// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.Completeness;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;

class SchemaV2SelectionAnalyzerTest
{
	private static final TestIdentity OLD = new TestIdentity("com.example.ServiceTest", "works");
	private static final TestIdentity OVERLOAD = new TestIdentity("com.example.ServiceTest", "works", "java.lang.String");
	private static final TestIdentity NESTED = new TestIdentity("com.example.OuterTest$Nested", "case 1");
	private static final TestIdentity PARAMETERIZED = new TestIdentity("com.example.ParameterTest", "declared", "int");

	@TempDir Path temporary;

	@Test void acceptsPublishedCompleteSchemaV2AndClassifiesExactInventory() throws Exception
	{
		Repo repo = repo();
		Path map = writeMap(repo.root, map(repo.head(), CoverageMapLifecycleState.PUBLISHED, complete(Set.of(OLD, OVERLOAD)),
				Map.of(OLD, coverage()), List.of(new UnmappedTest(OVERLOAD, UnmappedReason.SKIPPED))));
		HeadTestInventory inventory = HeadTestInventory.from(List.of(OLD, NESTED, PARAMETERIZED));
		SelectionAnalysisResult result = analyze(map, repo, inventory, 10, List.of());
		assertFalse(result.isRunAll());
		SelectionContext context = result.context().orElseThrow();
		assertEquals(Set.of(NESTED, PARAMETERIZED), context.newTests());
		assertEquals(Set.of(OVERLOAD), context.deletedTests());
		assertEquals(Set.of(OLD, NESTED, PARAMETERIZED), context.headTests());
		assertEquals(repo.head(), context.headRevision());
	}

	@Test void unsafeIngressAlwaysRunsAll() throws Exception
	{
		Repo repo = repo(); HeadTestInventory inventory = HeadTestInventory.from(List.of(OLD));
		assertRunAll(analyze(repo.root.resolve("missing.json"), repo, inventory, 10, List.of()));
		Path legacy = repo.root.resolve("legacy.json"); Files.writeString(legacy, "{\"metadata\":{},\"testMappings\":{}}");
		assertRunAll(analyze(legacy, repo, inventory, 10, List.of()));

		CoverageMap valid = validMap(repo.head());
		Path corrupt = writeMap(repo.root, valid);
		Files.writeString(corrupt, Files.readString(corrupt).replace("com.example.Service", "com.example.Other"));
		assertRunAll(analyze(corrupt, repo, inventory, 10, List.of()));

		assertRunAll(analyze(writeMap(repo.root, withLifecycle(valid, CoverageMapLifecycleState.CANDIDATE)), repo, inventory, 10, List.of()));
		assertRunAll(analyze(writeMap(repo.root, withLifecycle(valid, CoverageMapLifecycleState.FRAGMENT)), repo, inventory, 10, List.of()));
		assertRunAll(analyze(writeMap(repo.root, withCompleteness(valid, null)), repo, inventory, 10, List.of()));

		String higher = Files.readString(writeMap(repo.root, valid)).replace("\"schemaVersion\":2", "\"schemaVersion\":3");
		Path higherFile = repo.root.resolve("higher.json"); Files.writeString(higherFile, higher);
		assertRunAll(analyze(higherFile, repo, inventory, 10, List.of()));
	}

	@Test void everyCompletenessDiscrepancyRunsAll() throws Exception
	{
		Repo repo = repo(); HeadTestInventory inventory = HeadTestInventory.from(List.of(OLD));
		ShardId one = new ShardId("one"), two = new ShardId("two");
		List<Completeness> invalid = List.of(
				new Completeness(Set.of(OLD), Set.of(), Set.of(one), Set.of(one), Set.of(OLD), Set.of(), Set.of(), Set.of(), Set.of()),
				new Completeness(Set.of(), Set.of(OLD), Set.of(one), Set.of(one), Set.of(), Set.of(OLD), Set.of(), Set.of(), Set.of()),
				new Completeness(Set.of(OLD), Set.of(OLD), Set.of(one, two), Set.of(one), Set.of(), Set.of(), Set.of(two), Set.of(), Set.of()),
				new Completeness(Set.of(OLD), Set.of(OLD), Set.of(one), Set.of(one), Set.of(), Set.of(), Set.of(), Set.of(one), Set.of()),
				new Completeness(Set.of(OLD), Set.of(OLD), Set.of(one), Set.of(one, two), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()),
				new Completeness(Set.of(OLD), Set.of(OLD), Set.of(one), Set.of(one), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(OLD)));
		for (Completeness completeness : invalid)
			assertRunAll(analyze(writeMap(repo.root, withCompleteness(validMap(repo.head()), completeness)), repo, inventory, 10, List.of()));

		CoverageMap overlap = map(repo.head(), CoverageMapLifecycleState.PUBLISHED, complete(Set.of(OLD)),
				Map.of(OLD, coverage()), List.of(new UnmappedTest(OLD, UnmappedReason.FAILED)));
		assertRunAll(analyze(writeMap(repo.root, overlap), repo, inventory, 10, List.of()));
		CoverageMap duplicateUnmapped = map(repo.head(), CoverageMapLifecycleState.PUBLISHED, complete(Set.of(OLD)),
				Map.of(), List.of(new UnmappedTest(OLD, UnmappedReason.FAILED), new UnmappedTest(OLD, UnmappedReason.SKIPPED)));
		assertRunAll(analyze(writeMap(repo.root, duplicateUnmapped), repo, inventory, 10, List.of()));
		CoverageMap falseComplete = map(repo.head(), CoverageMapLifecycleState.PUBLISHED, complete(Set.of(OVERLOAD)),
				Map.of(OLD, coverage()), List.of());
		assertRunAll(analyze(writeMap(repo.root, falseComplete), repo, inventory, 10, List.of()));
	}

	@Test void enforcesRevisionCommitAncestryAndStaleness() throws Exception
	{
		Repo repo = repo(); String base = repo.head();
		repo.write("README.md", "two"); repo.commit("second"); String head = repo.head();
		assertFalse(analyze(writeMap(repo.root, validMap(base)), repo, inventory(), 1, List.of()).isRunAll());
		assertRunAll(analyze(writeMap(repo.root, validMap(base)), repo, inventory(), 0, List.of()));
		assertRunAll(analyze(writeMap(repo.root, validMap("does-not-exist")), repo, inventory(), 10, List.of()));

		run(repo.root, "git", "checkout", "--detach", base);
		repo.write("side.txt", "side"); repo.commit("side"); String divergent = repo.head();
		run(repo.root, "git", "checkout", "--detach", head);
		assertRunAll(analyze(writeMap(repo.root, validMap(divergent)), repo, inventory(), 10, List.of()));
		run(repo.root, "git", "checkout", "--detach", base);
		assertRunAll(analyze(writeMap(repo.root, validMap(head)), new Repo(repo.root), inventory(), 10, List.of()));
	}

	@Test void includesCommittedStagedUnstagedAndUntrackedChanges() throws Exception
	{
		Repo repo = repo(); String revision = repo.head();
		repo.write("src/main/java/com/example/Committed.java", "class Committed {}"); repo.commit("committed");
		repo.write("src/main/java/com/example/Staged.java", "class Staged {}"); run(repo.root, "git", "add", ".");
		repo.write("src/main/java/com/example/Committed.java", "class Committed { int x; }");
		repo.write("src/main/java/com/example/Untracked.java", "class Untracked {}");
		SelectionContext context = analyze(writeMap(repo.root, validMap(revision)), repo, inventory(), 10, List.of()).context().orElseThrow();
		assertEquals(Set.of("com.example.Committed", "com.example.Staged", "com.example.Untracked"), context.changedClasses());
	}

	@Test void handlesStructuralProductionChangesConservatively() throws Exception
	{
		Repo repo = repo();
		repo.write("src/main/java/com/example/Foo.java", "class Foo {}"); repo.commit("foo"); String revision = repo.head();
		repo.write("src/main/java/com/example/Foo.java", "class Foo { int x; }");
		SelectionContext modified = analyze(writeMap(repo.root, validMap(revision)), repo, inventory(), 10, List.of()).context().orElseThrow();
		assertEquals(Set.of("com.example.Foo"), modified.changedClasses());

		run(repo.root, "git", "reset", "--hard", revision);
		run(repo.root, "git", "mv", "src/main/java/com/example/Foo.java", "src/main/java/com/example/Bar.java");
		SelectionContext renamed = analyze(writeMap(repo.root, validMap(revision)), repo, inventory(), 10, List.of()).context().orElseThrow();
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), renamed.changedClasses());

		run(repo.root, "git", "reset", "--hard", revision);
		Files.delete(repo.root.resolve("src/main/java/com/example/Foo.java"));
		assertEquals(Set.of("com.example.Foo"), analyze(writeMap(repo.root, validMap(revision)), repo, inventory(), 10, List.of()).context().orElseThrow().changedClasses());

		run(repo.root, "git", "reset", "--hard", revision);
		repo.write("src/main/java/com/example/bad-name.java", "class Bad {}");
		assertRunAll(analyze(writeMap(repo.root, validMap(revision)), repo, inventory(), 10, List.of()));
	}

	@Test void classifiesChangedTestContainerWithoutGuessingMethods() throws Exception
	{
		Repo repo = repo(); String revision = repo.head();
		repo.write("src/test/java/com/example/ServiceTest.java", "class ServiceTest {}");
		HeadTestInventory inventory = HeadTestInventory.from(List.of(OLD, OVERLOAD, NESTED));
		SelectionContext context = analyze(writeMap(repo.root, validMap(revision)), repo, inventory, 10, List.of()).context().orElseThrow();
		assertEquals(Set.of(OLD, OVERLOAD), context.changedTests());
	}

	@Test void triggersAndInventoryFailuresRunAll() throws Exception
	{
		Repo repo = repo(); String revision = repo.head(); repo.write("config/build.yml", "changed");
		Path map = writeMap(repo.root, validMap(revision));
		assertRunAll(analyze(map, repo, inventory(), 10, List.of("config/**")));
		assertFalse(analyze(map, repo, inventory(), 10, List.of("docs/**")).isRunAll());
		assertFalse(analyze(map, repo, inventory(), 10, List.of()).isRunAll());
		assertRunAll(analyze(map, repo, null, 10, List.of()));
		assertRunAll(analyze(map, repo, inventory(), 10, List.of("[invalid")));
		assertThrows(IllegalArgumentException.class, () -> HeadTestInventory.from(List.of(OLD, OLD)));
	}

	@Test void movingHeadRunsAll() throws Exception
	{
		Repo repo = repo(); Path map = writeMap(repo.root, validMap(repo.head()));
		SchemaV2SelectionAnalyzer analyzer = new SchemaV2SelectionAnalyzer(new CoverageMapCodec(), () -> {
			try
			{
				repo.write("moved.txt", "new head"); repo.commit("move head during analysis");
			}
			catch (Exception e) { throw new IllegalStateException(e); }
		});
		assertRunAll(analyzer.analyze(map.toFile(), repo.root.toFile(), inventory(), 10, List.of()));
	}

	private SelectionAnalysisResult analyze(Path map, Repo repo, HeadTestInventory inventory, int distance, List<String> triggers)
	{
		return new SchemaV2SelectionAnalyzer().analyze(map.toFile(), repo.root.toFile(), inventory, distance, triggers);
	}
	private static HeadTestInventory inventory() { return HeadTestInventory.from(List.of(OLD)); }
	private static void assertRunAll(SelectionAnalysisResult result) { assertTrue(result.isRunAll(), result.reason()); }

	private Repo repo() throws Exception
	{
		Path root = temporary.resolve("repo-" + System.nanoTime()); Files.createDirectories(root);
		run(root, "git", "init", "-q"); run(root, "git", "config", "user.email", "test@example.com"); run(root, "git", "config", "user.name", "Test");
		Repo repo = new Repo(root); repo.write("README.md", "one"); repo.commit("initial"); return repo;
	}
	private static Path writeMap(Path root, CoverageMap map) throws Exception
	{
		Path path = root.resolve("coverage-map-" + System.nanoTime() + ".json"); Files.write(path, new CoverageMapCodec().serialize(map)); return path;
	}
	private static CoverageMap validMap(String revision)
	{
		return map(revision, CoverageMapLifecycleState.PUBLISHED, complete(Set.of(OLD)), Map.of(OLD, coverage()), List.of());
	}

	@Test void authoritativeLogicalInventoryIncludesIntentionallyNonExecutableDeclarations() throws Exception
	{
		Repo repo = repo();
		Completeness complete = complete(Set.of(OLD, OVERLOAD));
		CoverageMap map = map(repo.head(), CoverageMapLifecycleState.PUBLISHED, complete,
				Map.of(OLD, coverage()), List.of());
		SelectionAnalysisResult result = analyze(writeMap(repo.root, map), repo,
				HeadTestInventory.from(List.of(OLD, OVERLOAD)), 10, List.of());
		assertFalse(result.isRunAll(), result.reason());
		assertTrue(result.context().orElseThrow().newTests().isEmpty());
		assertTrue(new SchemaV2TestSelector().select(result).getSelectedTests().isEmpty());
	}
	private static CoverageMap map(String revision, CoverageMapLifecycleState lifecycle, Completeness completeness,
			Map<TestIdentity, TestCoverage> tests, List<UnmappedTest> unmapped)
	{
		return new CoverageMap(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision(revision), Instant.EPOCH,
				new GeneratorProvenance("test", "test", "test", "17"), tests, unmapped, List.of(), completeness,
				new MapStatistics(tests.size() + unmapped.size(), tests.size(), unmapped.size(), 0, 1, 1), lifecycle, null);
	}
	private static Completeness complete(Set<TestIdentity> tests)
	{
		ShardId shard = new ShardId("one"); return new Completeness(tests, tests, Set.of(shard), Set.of(shard), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
	}
	private static CoverageMap withLifecycle(CoverageMap map, CoverageMapLifecycleState lifecycle)
	{
		return new CoverageMap(map.schemaVersion(), map.revision(), map.generatedAt(), map.generator(), map.tests(), map.unmapped(), map.setupScopes(), map.completeness(), map.statistics(), lifecycle, map.methodCoverageReference());
	}
	private static CoverageMap withCompleteness(CoverageMap map, Completeness completeness)
	{
		return new CoverageMap(map.schemaVersion(), map.revision(), map.generatedAt(), map.generator(), map.tests(), map.unmapped(), map.setupScopes(), completeness, map.statistics(), map.lifecycleState(), map.methodCoverageReference());
	}
	private static TestCoverage coverage() { return new TestCoverage(Set.of("com.example.Service"), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_WITH_COVERAGE); }
	private static String run(Path directory, String... command) throws Exception
	{
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); int exit = process.waitFor();
		if (exit != 0) throw new AssertionError(String.join(" ", command) + ": " + output); return output.trim();
	}
	private static final class Repo
	{
		private final Path root;
		private Repo(Path root) { this.root = root; }
		private void write(String relative, String text) throws Exception { Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, text); }
		private void commit(String message) throws Exception { run(root, "git", "add", "."); run(root, "git", "commit", "-q", "-m", message); }
		private String head() throws Exception { return run(root, "git", "rev-parse", "HEAD"); }
	}
}
