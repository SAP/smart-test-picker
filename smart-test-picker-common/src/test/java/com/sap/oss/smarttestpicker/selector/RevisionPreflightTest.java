// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.sap.oss.smarttestpicker.coverage.model.Completeness;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;

class RevisionPreflightTest
{
	@TempDir Path temporary;

	@Test void acceptsFreshMapAndExposesMapToHeadInterval() throws Exception
	{
		Repo repo = repo(); String map = repo.head(); String head = repo.commit("head");
		RevisionPreflightResult result = evaluate(repo, map, map, map, head, 1);
		assertEquals(RevisionPreflightStatus.ELIGIBLE, result.status());
		assertEquals(new SelectionRevisionInterval(map, head, 1), result.effectiveInterval().orElseThrow());
	}

	@Test void acceptsStaleMapAndCountsFromMapRatherThanIntegration() throws Exception
	{
		Repo repo = repo(); String map = repo.head(); String integration = repo.commit("integration");
		String head = repo.commit("pr head");
		RevisionPreflightResult result = evaluate(repo, map, integration, integration, head, 2);
		assertEquals(RevisionPreflightStatus.ELIGIBLE, result.status());
		assertEquals(new SelectionRevisionInterval(map, head, 2), result.effectiveInterval().orElseThrow());
	}

	@Test void providerBaseDoesNotDetermineEligibility() throws Exception
	{
		Repo repo = repo(); String map = repo.head(); String oldBase = repo.commit("old base");
		String integration = repo.commit("integration"); String head = repo.commit("pr head");
		assertEquals(RevisionPreflightStatus.ELIGIBLE,
				evaluate(repo, map, integration, oldBase, head, 10).status());
	}

	@Test void runsFullSuiteWhenMapExceedsExistingDistanceWindow() throws Exception
	{
		Repo repo = repo(); String map = repo.head();
		for (int i = 0; i < 9; i++) repo.commit("integration " + i);
		String integration = repo.head(); repo.commit("pr 10"); String head = repo.commit("pr 11");
		assertEquals(RevisionPreflightStatus.FULL_SUITE,
				evaluate(repo, map, integration, integration, head, 10).status());
	}

	@Test void runsFullSuiteForDivergentMapHistory() throws Exception
	{
		Repo repo = repo(); String common = repo.head();
		run(repo.root, "git", "checkout", "-q", "-b", "map-side"); String map = repo.commit("map");
		run(repo.root, "git", "checkout", "-q", "-b", "integration-side", common);
		String integration = repo.commit("integration"); String head = repo.commit("head");
		assertEquals(RevisionPreflightStatus.FULL_SUITE,
				evaluate(repo, map, integration, integration, head, 10).status());
	}

	@Test void rejectsHeadNotBasedOnIntegrationAsBaseOutOfDate() throws Exception
	{
		Repo repo = repo(); String common = repo.head(); String integration = repo.commit("integration");
		run(repo.root, "git", "checkout", "-q", "-b", "pr-side", common); String head = repo.commit("head");
		assertEquals(RevisionPreflightStatus.BASE_OUT_OF_DATE,
				evaluate(repo, common, integration, integration, head, 10).status());
	}

	@Test void errorsForEveryUnresolvedOrSymbolicRevision() throws Exception
	{
		Repo repo = repo(); String commit = repo.head();
		assertEquals(RevisionPreflightStatus.ERROR, evaluate(repo, "missing", commit, commit, commit, 10).status());
		assertEquals(RevisionPreflightStatus.ERROR, evaluate(repo, commit, "missing", commit, commit, 10).status());
		assertEquals(RevisionPreflightStatus.ERROR, evaluate(repo, commit, commit, "missing", commit, 10).status());
		assertEquals(RevisionPreflightStatus.ERROR, evaluate(repo, commit, commit, commit, "missing", 10).status());
		assertEquals(RevisionPreflightStatus.ERROR, evaluate(repo, commit, commit, commit, "HEAD", 10).status());
	}

	@Test void acceptsEqualRevisionsAtZeroDistance() throws Exception
	{
		Repo repo = repo(); String commit = repo.head();
		RevisionPreflightResult result = evaluate(repo, commit, commit, commit, commit, 0);
		assertEquals(RevisionPreflightStatus.ELIGIBLE, result.status());
		assertEquals(0, result.effectiveInterval().orElseThrow().commitDistance());
	}

	private static RevisionPreflightResult evaluate(Repo repo, String map, String integration, String base,
			String head, int maxDistance)
	{
		return new RevisionPreflight().evaluate(repo.root.toFile(),
				new RevisionPreflightRequest(map(map), integration, base, head, maxDistance));
	}

	private Repo repo() throws Exception
	{
		Path root = temporary.resolve("repo-" + System.nanoTime()); Files.createDirectories(root);
		run(root, "git", "init", "-q"); run(root, "git", "config", "user.email", "test@example.com");
		run(root, "git", "config", "user.name", "Test");
		Repo repo = new Repo(root); repo.commit("initial"); return repo;
	}

	private static CoverageMap map(String revision)
	{
		return new CoverageMap(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision(revision), Instant.EPOCH,
				new GeneratorProvenance("test", "test", "test", "17"), Map.of(), List.of(), List.of(),
				new Completeness(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()),
				new MapStatistics(0, 0, 0, 0, 0, 0), CoverageMapLifecycleState.PUBLISHED, null);
	}

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
		private String commit(String message) throws Exception
		{
			Files.writeString(root.resolve("history.txt"), message + System.nanoTime());
			run(root, "git", "add", "."); run(root, "git", "commit", "-q", "-m", message); return head();
		}
		private String head() throws Exception { return run(root, "git", "rev-parse", "HEAD"); }
	}
}
