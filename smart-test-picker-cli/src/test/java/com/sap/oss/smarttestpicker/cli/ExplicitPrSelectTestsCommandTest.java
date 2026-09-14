// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCompleteness;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventory;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.HeadTestInventory;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;

import picocli.CommandLine;

class ExplicitPrSelectTestsCommandTest
{
	private static final TestIdentity TEST = new TestIdentity("com.example.ServiceTest", "works");
	@TempDir Path temporary;

	@Test void freshAndStaleMapUseCompleteExplicitIntervalIndependentOfWorkspaceHead() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head();
		repo.commitFile("src/main/java/com/example/Service.java", "class Service { int r1; }", "R1");
		String r1 = repo.head();
		repo.commitFile("src/main/java/com/example/Pr.java", "class Pr {}", "R2"); String r2 = repo.head();
		repo.commitFile("src/main/java/com/example/Workspace.java", "class Workspace {}", "R3");

		JsonObject fresh = execute(repo, r1, r1, r1, r2, r2, 10, List.of(), false);
		assertEquals("NONE", status(fresh));
		JsonObject stale = execute(repo, r0, r1, r1, r2, r2, 10, List.of(), false);
		assertEquals("SELECTED", status(stale));
	}

	@Test void stalePrIsBaseOutOfDateWithoutSelectionOutput() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head(); repo.commitText("integration", "E"); String integration = repo.head();
		run(repo.root, "git", "checkout", "--detach", r0); repo.commitText("pr", "P"); String head = repo.head();
		JsonObject result = execute(repo, r0, integration, r0, head, head, 10, List.of(), false);
		assertEquals("BASE_OUT_OF_DATE", status(result));
		assertFalse(result.has("selectionOutput"));
	}

	@Test void inventoryMismatchAndRevisionlessInventoryAreErrors() throws Exception
	{
		Repo repo = repo(); String r1 = repo.head(); repo.commitText("head", "R2"); String r2 = repo.head();
		JsonObject mismatch = execute(repo, r1, r1, r1, r2, r1, 10, List.of(), false);
		assertEquals("ERROR", status(mismatch));
		assertTrue(mismatch.get("reason").getAsString().contains("HEAD_INVENTORY_REVISION_MISMATCH"));
		assertFalse(mismatch.has("selectionOutput"));
		JsonObject legacy = execute(repo, r1, r1, r1, r2, r2, 10, List.of(), true);
		assertEquals("ERROR", status(legacy));
		assertTrue(legacy.get("reason").getAsString().contains("HEAD_INVENTORY_REVISION_MISMATCH"));
	}

	@Test void tooOldAndDivergentMapsAreFullSuite() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head(); repo.commitText("r1", "R1"); String r1 = repo.head();
		repo.commitText("r2", "R2"); String r2 = repo.head();
		assertEquals("FULL_SUITE", status(execute(repo, r0, r1, r1, r2, r2, 1, List.of(), false)));
		run(repo.root, "git", "checkout", "--detach", r0); repo.commitText("side", "side");
		String divergent = repo.head(); run(repo.root, "git", "checkout", "--detach", r2);
		assertEquals("FULL_SUITE", status(execute(repo, divergent, r1, r1, r2, r2, 10, List.of(), false)));
	}

	@Test void divergentHeadIsBaseOutOfDateAndTriggerIsFullSuite() throws Exception
	{
		Repo repo = repo(); String base = repo.head();
		repo.commitFile("build.gradle", "plugins {}", "integration"); String integration = repo.head();
		repo.commitText("head", "head"); String head = repo.head();
		assertEquals("FULL_SUITE", status(execute(repo, base, integration, integration, head, head, 10,
				List.of("build.gradle"), false)));
		run(repo.root, "git", "checkout", "--detach", base); repo.commitText("side", "side"); String side = repo.head();
		JsonObject invalid = execute(repo, base, integration, integration, side, side, 10, List.of(), false);
		assertEquals("BASE_OUT_OF_DATE", status(invalid));
		assertFalse(invalid.has("selectionOutput"));
	}

	@Test void schemaThreeInventoryPreservesDistinctMavenModuleOwnership() throws Exception
	{
		Repo repo = repo(); String r0 = repo.head();
		repo.commitFile("module-a/src/main/java/com/example/ProductionA.java", "package com.example; class ProductionA { int changed; }", "R1");
		String r1 = repo.head();
		var a = new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, "module-a"), TEST);
		var b = new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, "module-b"), TEST);
		Set<ExecutableTestIdentity> tests = Set.of(a, b); ShardId shard = new ShardId("one");
		TestCoverage coverageA = new TestCoverage(Set.of("com.example.ProductionA"), Set.of(), TestOutcome.PASS,
				CollectionStatus.COLLECTED_WITH_COVERAGE);
		TestCoverage coverageB = new TestCoverage(Set.of("com.example.ProductionB"), Set.of(), TestOutcome.PASS,
				CollectionStatus.COLLECTED_WITH_COVERAGE);
		var complete = new ExecutableCompleteness(tests, tests, Set.of(shard), Set.of(shard), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
		var map = new ExecutableCoverageMap(3, new CoverageMapRevision(r0), Instant.EPOCH,
				new GeneratorProvenance("test", "test", "test", "17"), Map.of(a, coverageA, b, coverageB),
				List.of(), List.of(), complete, new MapStatistics(2, 2, 0, 0, 2, 0),
				CoverageMapLifecycleState.PUBLISHED, null);
		Path mapFile = temporary.resolve("executable-map.json");
		Files.write(mapFile, new ExecutableCoverageMapCodec().serialize(map));
		Path inventory = temporary.resolve("executable-inventory.json");
		new ExecutableHeadTestInventoryCodec().write(inventory.toFile(), ExecutableHeadTestInventory.atRevision(r1, tests));
		Path output = temporary.resolve("executable-output.json");
		assertEquals(0, new CommandLine(new SelectTestsCommand()).execute("--map", mapFile.toString(),
				"--head-inventory", inventory.toString(), "--project-dir", repo.root.toString(), "--output", output.toString(),
				"--integration-revision", r0, "--pr-base-revision", r0, "--pr-head-revision", r1));
		var selected = JsonParser.parseString(Files.readString(output)).getAsJsonObject()
				.getAsJsonObject("selectionOutput").getAsJsonArray("selectedExecutableTests");
		assertEquals(Set.of(a.toString(), b.toString()), java.util.stream.StreamSupport.stream(selected.spliterator(), false)
				.map(value -> value.getAsString()).collect(java.util.stream.Collectors.toSet()));
	}

	private JsonObject execute(Repo repo, String mapRevision, String integration, String base, String head,
			String inventoryRevision, int distance, List<String> triggers, boolean legacyInventory) throws Exception
	{
		Path map = temporary.resolve("map-" + System.nanoTime() + ".json");
		Files.write(map, new CoverageMapCodec().serialize(map(mapRevision)));
		Path inventory = temporary.resolve("inventory-" + System.nanoTime() + ".json");
		if (legacyInventory) Files.writeString(inventory, "[\"" + TEST + "\"]");
		else new HeadTestInventoryCodec().write(inventory.toFile(),
				HeadTestInventory.atRevision(inventoryRevision, List.of(TEST)));
		Path output = temporary.resolve("output-" + System.nanoTime() + ".json");
		java.util.ArrayList<String> args = new java.util.ArrayList<>(List.of("--map", map.toString(),
				"--head-inventory", inventory.toString(), "--project-dir", repo.root.toString(), "--output",
				output.toString(), "--integration-revision", integration, "--pr-base-revision", base,
				"--pr-head-revision", head, "--max-commit-distance", Integer.toString(distance)));
		for (String trigger : triggers) { args.add("--full-suite-trigger"); args.add(trigger); }
		assertEquals(0, new CommandLine(new SelectTestsCommand()).execute(args.toArray(String[]::new)));
		return JsonParser.parseString(Files.readString(output)).getAsJsonObject();
	}

	private static String status(JsonObject output) { return output.get("status").getAsString(); }
	private static CoverageMap map(String revision)
	{
		ShardId shard = new ShardId("one"); Set<TestIdentity> tests = Set.of(TEST);
		Completeness completeness = new Completeness(tests, tests, Set.of(shard), Set.of(shard), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of());
		TestCoverage coverage = new TestCoverage(Set.of("com.example.Service"), Set.of(), TestOutcome.PASS,
				CollectionStatus.COLLECTED_WITH_COVERAGE);
		return new CoverageMap(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision(revision), Instant.EPOCH,
				new GeneratorProvenance("test", "test", "test", "17"), Map.of(TEST, coverage), List.of(), List.of(),
				completeness, new MapStatistics(1, 1, 0, 0, 1, 1), CoverageMapLifecycleState.PUBLISHED, null);
	}

	private Repo repo() throws Exception
	{
		Path root = temporary.resolve("repo-" + System.nanoTime()); Files.createDirectories(root);
		run(root, "git", "init", "-q"); run(root, "git", "config", "user.email", "test@example.com");
		run(root, "git", "config", "user.name", "Test");
		Repo repo = new Repo(root); repo.commitFile("README.md", "R0", "R0"); return repo;
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
		private void commitText(String name, String message) throws Exception
		{
			commitFile(name + ".txt", message, message);
		}
		private void commitFile(String relative, String text, String message) throws Exception
		{
			Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, text);
			run(root, "git", "add", "."); run(root, "git", "commit", "-q", "-m", message);
		}
		private String head() throws Exception { return run(root, "git", "rev-parse", "HEAD"); }
	}
}
