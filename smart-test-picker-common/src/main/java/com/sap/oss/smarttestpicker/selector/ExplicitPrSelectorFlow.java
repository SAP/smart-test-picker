// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;

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
			CoverageMap map = new CoverageMapCodec().deserialize(Files.readAllBytes(mapFile.toPath()));
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

	private static ExplicitPrSelectionResult error(String reason)
	{
		return ExplicitPrSelectionResult.failure(ExplicitPrSelectionStatus.ERROR, reason);
	}
	private static String safeMessage(Exception e)
	{
		return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
	}
}
