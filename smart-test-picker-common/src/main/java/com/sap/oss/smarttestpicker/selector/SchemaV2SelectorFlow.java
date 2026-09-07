// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Single adapter-facing schema-v2 selector flow. */
public final class SchemaV2SelectorFlow
{
	public SelectionOutput select(File mapFile, File inventoryFile, File projectDir,
			int maxCommitDistance, List<String> fullSuiteTriggers)
	{
		HeadTestInventory inventory;
		try
		{
			inventory = new HeadTestInventoryCodec().read(inventoryFile);
		}
		catch (IOException | RuntimeException e)
		{
			return new SelectionOutput("FULL_SUITE", e.getMessage(), List.of(), java.util.Map.of());
		}
		SelectionAnalysisResult analysis = new SchemaV2SelectionAnalyzer().analyze(mapFile, projectDir,
				inventory, maxCommitDistance, fullSuiteTriggers == null ? List.of() : fullSuiteTriggers);
		return new SchemaV2TestSelector().select(analysis);
	}
}
