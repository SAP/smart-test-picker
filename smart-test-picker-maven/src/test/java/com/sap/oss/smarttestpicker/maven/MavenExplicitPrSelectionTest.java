// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;
import com.sap.oss.smarttestpicker.selector.*;

/** Adapter contract: Maven delegates explicit decisions unchanged to common core. */
class MavenExplicitPrSelectionTest
{
	private static final TestIdentity TEST = new TestIdentity("com.example.ServiceTest", "works");
	@TempDir Path temporary;

	@Test void freshAndStaleMapUseCommonExplicitInterval() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head();
		repo.commit("src/main/java/com/example/Service.java", "class Service { int integration; }", "R1"); String r1 = repo.head();
		repo.commit("src/main/java/com/example/PullRequest.java", "class PullRequest {}", "R2"); String r2 = repo.head();
		assertEquals("NONE", output(select(repo, r1, r1, r1, r2, r2, 10)).getStatus());
		ExplicitPrSelectionResult stale = select(repo, r0, r1, r1, r2, r2, 10);
		assertEquals("SELECTED", output(stale).getStatus());
		assertEquals(Set.of("com.example.Service", "com.example.PullRequest"), stale.context().orElseThrow().changedClasses());
	}

	@Test void stalePrInvalidHeadAndTooOldMapPreserveCommonStatuses() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head(); repo.commitText("r1", "R1"); String r1 = repo.head();
		repo.commitText("r2", "R2"); String r2 = repo.head();
		assertEquals("FULL_SUITE", output(select(repo, r0, r1, r1, r2, r2, 1)).getStatus());
		run(repo.root, "git", "checkout", "--detach", r0); repo.commitText("side", "side"); String side = repo.head();
		assertEquals(ExplicitPrSelectionStatus.BASE_OUT_OF_DATE,
				select(repo, r0, r2, r0, side, side, 10).status());
	}

	@Test void inventoryMismatchAndRevisionlessInventoryAreErrors() throws Exception
	{
		Repo repo = repo(); String r1 = repo.head(); repo.commitText("head", "R2"); String r2 = repo.head();
		assertMismatch(select(repo, r1, r1, r1, r2, r1, 10));
		assertMismatch(select(repo, r1, r1, r1, r2, null, 10));
	}

	@Test void localModeGateRequiresNoExplicitRevision()
	{
		assertFalse(MavenExplicitPrSelection.requested(null, null, null));
		assertTrue(MavenExplicitPrSelection.requested("a", null, null));
	}

	private void assertMismatch(ExplicitPrSelectionResult result)
	{
		assertEquals(ExplicitPrSelectionStatus.ERROR, result.status());
		assertTrue(result.reason().startsWith("HEAD_INVENTORY_REVISION_MISMATCH")); assertTrue(result.output().isEmpty());
	}
	private SelectionOutput output(ExplicitPrSelectionResult result)
	{
		assertEquals(ExplicitPrSelectionStatus.SELECTION_RESULT, result.status()); return result.output().orElseThrow();
	}
	private ExplicitPrSelectionResult select(Repo repo, String mapRevision, String integration, String base,
			String head, String inventoryRevision, int distance) throws Exception
	{
		Path map = temporary.resolve("map-" + System.nanoTime() + ".json"); Files.write(map, new CoverageMapCodec().serialize(map(mapRevision)));
		Path inventory = temporary.resolve("inventory-" + System.nanoTime() + ".json");
		HeadTestInventory value = inventoryRevision == null ? HeadTestInventory.from(List.of(TEST)) : HeadTestInventory.atRevision(inventoryRevision, List.of(TEST));
		new HeadTestInventoryCodec().write(inventory.toFile(), value);
		return MavenExplicitPrSelection.select(map.toFile(), inventory.toFile(), repo.root.toFile(), integration, base, head, distance, List.of());
	}
	private static CoverageMap map(String revision)
	{
		ShardId shard = new ShardId("one"); Set<TestIdentity> tests = Set.of(TEST);
		Completeness completeness = new Completeness(tests, tests, Set.of(shard), Set.of(shard), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
		TestCoverage coverage = new TestCoverage(Set.of("com.example.Service"), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_WITH_COVERAGE);
		return new CoverageMap(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision(revision), Instant.EPOCH,
				new GeneratorProvenance("test", "test", "test", "17"), Map.of(TEST, coverage), List.of(), List.of(), completeness,
				new MapStatistics(1, 1, 0, 0, 1, 1), CoverageMapLifecycleState.PUBLISHED, null);
	}
	private Repo repo() throws Exception
	{
		Path root = temporary.resolve("repo-" + System.nanoTime()); Files.createDirectories(root);
		run(root, "git", "init", "-q"); run(root, "git", "config", "user.email", "test@example.com"); run(root, "git", "config", "user.name", "Test");
		Repo repo = new Repo(root); repo.commit("README.md", "R0", "R0"); return repo;
	}
	private static String run(Path dir, String... command) throws Exception
	{
		Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) throw new AssertionError(String.join(" ", command) + ": " + output); return output.trim();
	}
	private static final class Repo
	{
		private final Path root; private Repo(Path root) { this.root = root; }
		private void commitText(String name, String message) throws Exception { commit(name + ".txt", message, message); }
		private void commit(String relative, String text, String message) throws Exception
		{
			Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, text);
			run(root, "git", "add", "."); run(root, "git", "commit", "-q", "-m", message);
		}
		private String head() throws Exception { return run(root, "git", "rev-parse", "HEAD"); }
	}
}
