// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;

class ExplicitPrSelectorFlowTest
{
	private static final TestIdentity TEST = new TestIdentity("com.example.ServiceTest", "works");
	@TempDir Path temporary;

	@Test void freshExplicitPrBindsMapAndExplicitHead() throws Exception
	{
		Repo repo = repo(); String r1 = repo.head();
		repo.write("src/main/java/com/example/Head.java", "class Head {}"); repo.commit("R2"); String r2 = repo.head();
		ExplicitPrSelectionResult result = select(repo, r1, r1, r1, r2, 10, List.of());
		assertEquals(ExplicitPrSelectionStatus.SELECTION_RESULT, result.status());
		SelectionContext context = result.context().orElseThrow();
		assertEquals(r1, context.revision().value());
		assertEquals(r2, context.headRevision());
	}

	@Test void staleMapDiffIncludesIntegrationAndPrChangesAndIgnoresWorkspace() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head();
		repo.write("src/main/java/com/example/Integration.java", "class Integration {}"); repo.commit("R1"); String r1 = repo.head();
		repo.write("src/main/java/com/example/PullRequest.java", "class PullRequest {}"); repo.commit("R2"); String r2 = repo.head();
		repo.write("src/main/java/com/example/WorkspaceOnly.java", "class WorkspaceOnly {}"); repo.commit("workspace");
		repo.write("src/main/java/com/example/Untracked.java", "class Untracked {}");

		ExplicitPrSelectionResult result = select(repo, r0, r1, r1, r2, 10, List.of());
		SelectionContext context = result.context().orElseThrow();
		assertEquals(Set.of("com.example.Integration", "com.example.PullRequest"), context.changedClasses());
		assertEquals(r2, context.headRevision());
		assertFalse(context.changedPaths().contains("src/main/java/com/example/WorkspaceOnly.java"));
		assertFalse(context.changedPaths().contains("src/main/java/com/example/Untracked.java"));
	}

	@Test void stalePrIsDistinctAndHasNoSelectionOutput() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head(); repo.commitFile("integration", "E"); String integration = repo.head();
		run(repo.root, "git", "checkout", "--detach", r0); repo.commitFile("pr", "P"); String head = repo.head();
		ExplicitPrSelectionResult result = select(repo, r0, integration, r0, head, 10, List.of());
		assertEquals(ExplicitPrSelectionStatus.BASE_OUT_OF_DATE, result.status());
		assertTrue(result.output().isEmpty());
		assertTrue(result.context().isEmpty());
	}

	@Test void tooOldMapAndDivergentMapFailOpen() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head(); repo.commitFile("r1", "R1"); String r1 = repo.head();
		repo.commitFile("r2", "R2"); String r2 = repo.head();
		assertFullSuite(select(repo, r0, r1, r1, r2, 1, List.of()));

		run(repo.root, "git", "checkout", "--detach", r0);
		repo.commitFile("side", "side"); String divergent = repo.head();
		run(repo.root, "git", "checkout", "--detach", r2);
		assertFullSuite(select(repo, divergent, r1, r1, r2, 10, List.of()));
	}

	@Test void divergentHeadIsBaseOutOfDate() throws Exception
	{
		Repo repo = repo(); String base = repo.head(); repo.commitFile("integration", "integration"); String integration = repo.head();
		run(repo.root, "git", "checkout", "--detach", base); repo.commitFile("side", "head"); String side = repo.head();
		ExplicitPrSelectionResult result = select(repo, base, integration, integration, side, 10, List.of());
		assertEquals(ExplicitPrSelectionStatus.BASE_OUT_OF_DATE, result.status());
		assertTrue(result.output().isEmpty());
	}

	@Test void triggerBetweenMapAndIntegrationCoversWholeInterval() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head();
		repo.write("build.gradle", "plugins {}"); repo.commit("R1"); String r1 = repo.head();
		repo.commitFile("head", "R2"); String r2 = repo.head();
		assertFullSuite(select(repo, r0, r1, r1, r2, 10, List.of("build.gradle")));
	}

	@Test void rejectsInventoryFromIntegrationRevision() throws Exception
	{
		Repo repo = repo(); String r1 = repo.head(); repo.commitFile("head", "R2"); String r2 = repo.head();
		ExplicitPrSelectionResult result = select(repo, r1, r1, r1, r2, r1, 10, List.of());
		assertEquals(ExplicitPrSelectionStatus.ERROR, result.status());
		assertEquals("HEAD_INVENTORY_REVISION_MISMATCH: inventory revision does not equal prHeadRevision",
				result.reason());
		assertTrue(result.output().isEmpty());
	}

	@Test void rejectsInventoryFromLaterRevision() throws Exception
	{
		Repo repo = repo(); String r1 = repo.head(); repo.commitFile("head", "R2"); String r2 = repo.head();
		repo.commitFile("later", "R3"); String r3 = repo.head();
		ExplicitPrSelectionResult result = select(repo, r1, r1, r1, r2, r3, 10, List.of());
		assertEquals(ExplicitPrSelectionStatus.ERROR, result.status());
		assertTrue(result.output().isEmpty());
	}

	@Test void rejectsSymbolicAndUnresolvedInventoryRevisions() throws Exception
	{
		Repo repo = repo(); String head = repo.head();
		ExplicitPrSelectionResult symbolic = select(repo, head, head, head, head, "HEAD", 10, List.of());
		assertEquals(ExplicitPrSelectionStatus.ERROR, symbolic.status());
		assertTrue(symbolic.reason().startsWith("HEAD_INVENTORY_REVISION_MISMATCH"));
		ExplicitPrSelectionResult unresolved = select(repo, head, head, head, head, "does-not-exist", 10, List.of());
		assertEquals(ExplicitPrSelectionStatus.ERROR, unresolved.status());
		assertTrue(unresolved.reason().startsWith("HEAD_INVENTORY_REVISION_MISMATCH"));
	}

	@Test void explicitInventoryCanMatchHeadThatIsNotWorkspaceHead() throws Exception
	{
		Repo repo = repo(); String r1 = repo.head(); repo.commitFile("head", "R2"); String r2 = repo.head();
		repo.commitFile("workspace", "workspace");
		ExplicitPrSelectionResult result = select(repo, r1, r1, r1, r2, r2, 10, List.of());
		assertEquals(ExplicitPrSelectionStatus.SELECTION_RESULT, result.status());
		assertEquals(r2, result.context().orElseThrow().headRevision());
	}

	private ExplicitPrSelectionResult select(Repo repo, String map, String integration, String base, String head,
			int distance, List<String> triggers) throws Exception
	{
		return select(repo, map, integration, base, head, head, distance, triggers);
	}
	private ExplicitPrSelectionResult select(Repo repo, String map, String integration, String base, String head,
			String inventoryRevision, int distance, List<String> triggers) throws Exception
	{
		Path mapFile = repo.root.resolve("map-" + System.nanoTime() + ".json");
		Files.write(mapFile, new CoverageMapCodec().serialize(map(map)));
		return new ExplicitPrSelectorFlow().select(mapFile.toFile(), repo.root.toFile(),
				HeadTestInventory.atRevision(inventoryRevision, List.of(TEST)), integration, base, head, distance, triggers);
	}
	private static void assertFullSuite(ExplicitPrSelectionResult result)
	{
		assertEquals(ExplicitPrSelectionStatus.SELECTION_RESULT, result.status());
		assertEquals("FULL_SUITE", result.output().orElseThrow().getStatus());
	}
	private Repo repo() throws Exception
	{
		Path root = temporary.resolve("repo-" + System.nanoTime()); Files.createDirectories(root);
		run(root, "git", "init", "-q"); run(root, "git", "config", "user.email", "test@example.com");
		run(root, "git", "config", "user.name", "Test");
		Repo repo = new Repo(root); repo.write("README.md", "R0"); repo.commit("R0"); return repo;
	}
	private static CoverageMap map(String revision)
	{
		ShardId shard = new ShardId("one");
		Completeness completeness = new Completeness(Set.of(TEST), Set.of(TEST), Set.of(shard), Set.of(shard),
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
		TestCoverage coverage = new TestCoverage(Set.of("com.example.Service"), Set.of(), TestOutcome.PASS,
				CollectionStatus.COLLECTED_WITH_COVERAGE);
		return new CoverageMap(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision(revision), Instant.EPOCH,
				new GeneratorProvenance("test", "test", "test", "17"), Map.of(TEST, coverage), List.of(), List.of(),
				completeness, new MapStatistics(1, 1, 0, 0, 1, 1), CoverageMapLifecycleState.PUBLISHED, null);
	}
	private static String run(Path directory, String... command) throws Exception
	{
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) throw new AssertionError(String.join(" ", command) + ": " + output);
		return output.trim();
	}
	private static final class Repo
	{
		private final Path root;
		private Repo(Path root) { this.root = root; }
		private void write(String relative, String text) throws Exception
		{
			Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, text);
		}
		private void commit(String message) throws Exception
		{
			run(root, "git", "add", "."); run(root, "git", "commit", "-q", "-m", message);
		}
		private void commitFile(String name, String message) throws Exception { write(name + ".txt", message); commit(message); }
		private String head() throws Exception { return run(root, "git", "rev-parse", "HEAD"); }
	}
}
