// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.List;

import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.Completeness;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.MethodIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageMapCodec;

/** Build-tool-neutral schema-v2 selection flow for frozen PR commits. */
public final class ExplicitPrSelectorFlow
{
	public ExplicitPrSelectionResult select(File mapFile, File projectDir, HeadTestInventory prHeadInventory,
			String integrationRevision, String prBaseRevision, String prHeadRevision, int maxCommitDistance,
			List<String> fullSuiteTriggers)
	{
		try
		{
			if (mapFile == null || !mapFile.isFile()) return error("Coverage map not found");
			if (prHeadInventory == null) return error("Authoritative PR-head test inventory unavailable");
			CoverageMap map = selectionMap(Files.readAllBytes(mapFile.toPath()));
			RevisionPreflightResult preflight = new RevisionPreflight().evaluate(projectDir,
					new RevisionPreflightRequest(map, integrationRevision, prBaseRevision, prHeadRevision,
							maxCommitDistance));
			if (preflight.status() == RevisionPreflightStatus.BASE_OUT_OF_DATE)
				return ExplicitPrSelectionResult.failure(ExplicitPrSelectionStatus.BASE_OUT_OF_DATE,
						preflight.reason());
			if (preflight.status() == RevisionPreflightStatus.ERROR) return error(preflight.reason());
			String inventoryRevision = prHeadInventory.revision();
			if (inventoryRevision == null || inventoryRevision.isBlank())
				return error("HEAD_INVENTORY_REVISION_MISMATCH: inventory revision is missing");
			GitRevisionAccess git = new GitRevisionAccess(projectDir);
			String resolvedInventory;
			try { resolvedInventory = git.resolveCommit(inventoryRevision); }
			catch (RuntimeException invalidInventory)
			{
				return error("HEAD_INVENTORY_REVISION_MISMATCH: inventory revision cannot be resolved");
			}
			if (!resolvedInventory.equalsIgnoreCase(inventoryRevision))
				return error("HEAD_INVENTORY_REVISION_MISMATCH: inventory revision must be a full frozen commit ID");
			String resolvedHead = git.resolveCommit(prHeadRevision);
			if (!resolvedInventory.equalsIgnoreCase(resolvedHead))
				return error("HEAD_INVENTORY_REVISION_MISMATCH: inventory revision does not equal prHeadRevision");
			SchemaV2TestSelector selector = new SchemaV2TestSelector();
			if (preflight.status() == RevisionPreflightStatus.FULL_SUITE)
				return ExplicitPrSelectionResult.selection(
						selector.select(SelectionAnalysisResult.runAll(preflight.reason())), null);

			SelectionRevisionInterval interval = preflight.effectiveInterval().orElseThrow();
			SelectionAnalysisResult analysis = new SchemaV2SelectionAnalyzer().analyzeExplicit(map, projectDir,
					prHeadInventory, interval, fullSuiteTriggers == null ? List.of() : fullSuiteTriggers);
			return ExplicitPrSelectionResult.selection(selector.select(analysis), analysis.context().orElse(null));
		}
		catch (Exception e)
		{
			return error("Explicit PR selection failed: " + safeMessage(e));
		}
	}

	/** Project task-aware schema-v3 occurrences onto the selector's logical test identity boundary. */
	private static CoverageMap selectionMap(byte[] bytes)
	{
		int schema = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
				.getAsJsonObject().get("schemaVersion").getAsInt();
		if (schema != CoverageMapContract.SCHEMA_V3) return new CoverageMapCodec().deserialize(bytes);
		ExecutableCoverageMap source = new ExecutableCoverageMapCodec().deserialize(bytes);
		Map<TestIdentity, TestCoverage> tests = new TreeMap<>();
		source.tests().forEach((identity, coverage) -> tests.merge(identity.test(), coverage,
				ExplicitPrSelectorFlow::mergeCoverage));
		Map<TestIdentity, UnmappedTest> unmapped = new TreeMap<>();
		source.unmapped().forEach(value -> unmapped.putIfAbsent(value.test().test(),
				new UnmappedTest(value.test().test(), value.reason())));
		unmapped.keySet().forEach(tests::remove);
		Set<TestIdentity> expected = logical(source.completeness().expectedTests());
		Set<TestIdentity> reported = logical(source.completeness().reportedTests());
		Completeness completeness = new Completeness(expected, reported,
				source.completeness().expectedShards(), source.completeness().completedShards(),
				logical(source.completeness().missingTests()), logical(source.completeness().unexpectedTests()),
				source.completeness().missingShards(), source.completeness().duplicateShards(),
				logical(source.completeness().duplicateTests()));
		long classEdges = tests.values().stream().mapToLong(value -> value.coveredClasses().size()).sum();
		long methodEdges = tests.values().stream().mapToLong(value -> value.coveredMethods().size()).sum();
		List<com.sap.oss.smarttestpicker.coverage.model.SetupScope> scopes = source.setupScopes().stream()
				.map(scope -> new com.sap.oss.smarttestpicker.coverage.model.SetupScope(
						scope.owner() + "::" + scope.id(), scope.type(), scope.coveredClasses(),
						scope.affectedContainers(), scope.owner())).toList();
		return new CoverageMap(CoverageMapContract.SCHEMA_VERSION, source.revision(), source.generatedAt(),
				source.generator(), tests, new ArrayList<>(unmapped.values()), scopes, completeness,
				new MapStatistics(expected.size(), tests.size(), unmapped.size(), source.setupScopes().size(),
						classEdges, methodEdges), source.lifecycleState(), source.methodCoverageReference());
	}

	private static Set<TestIdentity> logical(Set<com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity> values)
	{
		TreeSet<TestIdentity> result = new TreeSet<>();
		values.forEach(value -> result.add(value.test()));
		return result;
	}

	private static TestCoverage mergeCoverage(TestCoverage left, TestCoverage right)
	{
		TreeSet<String> classes = new TreeSet<>(left.coveredClasses()); classes.addAll(right.coveredClasses());
		TreeSet<MethodIdentity> methods = new TreeSet<>(left.coveredMethods()); methods.addAll(right.coveredMethods());
		CollectionStatus status = classes.isEmpty() ? CollectionStatus.COLLECTED_EMPTY
				: CollectionStatus.COLLECTED_WITH_COVERAGE;
		return new TestCoverage(classes, methods, left.outcome(), status);
	}

	private static ExplicitPrSelectionResult error(String reason)
	{
		return ExplicitPrSelectionResult.failure(ExplicitPrSelectionStatus.ERROR, reason);
	}
	private static String safeMessage(Exception e)
	{
		return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
	}
}
