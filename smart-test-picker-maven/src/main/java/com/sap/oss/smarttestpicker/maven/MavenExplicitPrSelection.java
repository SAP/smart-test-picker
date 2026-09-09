// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import com.google.gson.GsonBuilder;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionResult;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionStatus;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectorFlow;
import com.sap.oss.smarttestpicker.selector.HeadTestInventory;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.SelectionContext;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;

/** Shared thin explicit-PR boundary for all Maven selector goals. */
final class MavenExplicitPrSelection
{
	private MavenExplicitPrSelection() {}

	static boolean requested(String integration, String base, String head)
	{
		return integration != null || base != null || head != null;
	}

	static ExplicitPrSelectionResult run(List<MavenProject> projects, File projectDir, File map,
			File inventoryFile, File reportFile, String integration, String base, String head,
			int maxDistance, List<String> triggers, Log log) throws MojoExecutionException
	{
		if (integration == null || base == null || head == null)
			throw new MojoExecutionException("Explicit PR mode requires smartTestPicker.integrationRevision, "
					+ "smartTestPicker.prBaseRevision, and smartTestPicker.prHeadRevision");
		ExplicitPrSelectionResult result;
		if (!MavenHeadTestInventory.generate(projects, inventoryFile, projectDir, head, log))
		{
			result = ExplicitPrSelectionResult.failure(ExplicitPrSelectionStatus.ERROR,
					"Explicit PR head inventory generation failed");
		}
		else
		{
			try
			{
				result = select(map, inventoryFile, projectDir, integration, base, head, maxDistance,
						triggers == null ? List.of() : triggers);
			}
			catch (Exception failure)
			{
				result = ExplicitPrSelectionResult.failure(ExplicitPrSelectionStatus.ERROR,
						"Explicit PR selection failed: " + failure.getMessage());
			}
		}
		try
		{
			File parent = reportFile.getParentFile(); if (parent != null) parent.mkdirs();
			try (FileWriter writer = new FileWriter(reportFile))
			{
				new GsonBuilder().setPrettyPrinting().create().toJson(report(result), writer);
			}
		}
		catch (Exception failure) { throw new MojoExecutionException("Cannot write explicit PR result", failure); }
		log.info("[SmartTestPicker] Explicit PR status: " + result.status() + " — " + result.reason());
		return result;
	}

	static ExplicitPrSelectionResult select(File map, File inventoryFile, File projectDir, String integration,
			String base, String head, int maxDistance, List<String> triggers) throws Exception
	{
		HeadTestInventory inventory = new HeadTestInventoryCodec().read(inventoryFile);
		return new ExplicitPrSelectorFlow().select(map, projectDir, inventory, integration, base, head,
				maxDistance, triggers);
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
