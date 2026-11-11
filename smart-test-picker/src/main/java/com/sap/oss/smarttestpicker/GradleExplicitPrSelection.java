// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.util.List;

import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionResult;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectorFlow;
import com.sap.oss.smarttestpicker.selector.HeadTestInventory;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.SelectionContext;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;

/** Testable thin Gradle delegation boundary; revision policy remains in common core. */
final class GradleExplicitPrSelection
{
	private GradleExplicitPrSelection() {}
	static boolean requested(String integration, String base, String head)
	{
		return integration != null || base != null || head != null;
	}

	static ExplicitPrSelectionResult select(File map, File inventoryFile, File projectDir,
			String integration, String base, String head, int distance, List<String> triggers) throws Exception
	{
		HeadTestInventory inventory = new HeadTestInventoryCodec().read(inventoryFile);
		return new ExplicitPrSelectorFlow().select(map, projectDir, inventory, integration, base, head,
				distance, triggers);
	}

	static Report report(ExplicitPrSelectionResult result)
	{
		SelectionOutput output = result.output().orElse(null);
		SelectionContext context = result.context().orElse(null);
		return new Report(result.status().name(), result.reason(), output,
				context == null ? null : context.revision().value(), context == null ? null : context.headRevision(),
				context == null ? null : context.changedPaths(), context == null ? null : context.changedClasses());
	}

	record Report(String status, String reason, SelectionOutput selectionOutput, String mapRevision,
			String headRevision, java.util.Set<String> changedPaths, java.util.Set<String> changedClasses) {}
}
