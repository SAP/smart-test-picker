// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.sap.oss.smarttestpicker.selector.SchemaV2SelectorFlow;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionResult;
import com.sap.oss.smarttestpicker.selector.ExplicitPrSelectionStatus;
import org.gradle.api.GradleException;


/**
 * Gradle task that selects tests impacted by code changes.
 *
 * <p>Thin Gradle wrapper over the authoritative schema-v2 selector flow.</p>
 *
 * <p>Output file: {@code build/selected-tests.json}</p>
 *
 * @see SelectionOutput
 */
public abstract class SelectTestsTask extends DefaultTask
{

	/** The coverage map JSON file (typically {@code build/test-coverage-map.json}). */
	@Internal
	public abstract RegularFileProperty getCoverageMapFile();

	/** Exact JSON array of schema-v2 TestIdentity strings for the current head. */
	@Internal
	public abstract RegularFileProperty getHeadTestInventoryFile();

	/** Output file listing selected tests (typically {@code build/selected-tests.json}). */
	@OutputFile
	public abstract RegularFileProperty getSelectedTestsFile();

	/** Maximum commit distance before the coverage map is considered stale. */
	@Input
	public abstract Property<Integer> getMaxCommitDistance();

	/**
	 * Glob patterns for files that trigger a full test suite when changed.
	 * When any changed file matches a pattern, FULL_SUITE is returned regardless
	 * of the coverage map analysis.
	 */
	@Input
	@Optional
	public abstract ListProperty<String> getFullSuiteTriggers();

	@Input @Optional public abstract Property<String> getIntegrationRevision();
	@Input @Optional public abstract Property<String> getPrBaseRevision();
	@Input @Optional public abstract Property<String> getPrHeadRevision();
	@Internal public abstract RegularFileProperty getExplicitResultFile();

	/**
	 * Main task action: delegates to the shared schema-v2 analyzer and selector.
	 */
	@TaskAction
	public void select()
	{
		boolean explicit = GradleExplicitPrSelection.requested(getIntegrationRevision().getOrNull(),
				getPrBaseRevision().getOrNull(), getPrHeadRevision().getOrNull());
		if (explicit)
		{
			if (!getIntegrationRevision().isPresent() || !getPrBaseRevision().isPresent()
					|| !getPrHeadRevision().isPresent())
				throw new GradleException("Explicit PR mode requires integrationRevision, prBaseRevision, and prHeadRevision");
			selectExplicit();
			return;
		}
		getExplicitResultFile().getAsFile().get().delete();
		SelectionOutput output = new SchemaV2SelectorFlow().select(
				getCoverageMapFile().getAsFile().get(),
				getHeadTestInventoryFile().getAsFile().getOrNull(),
				getProject().getProjectDir(),
				getMaxCommitDistance().get(),
				getFullSuiteTriggers().getOrElse(List.of()));

		getLogger().lifecycle("[SmartTestPicker] Status: {} \u2014 {}", output.getStatus(), output.getReason());

		write(getSelectedTestsFile().getAsFile().get(), output);
	}

	private void selectExplicit()
	{
		ExplicitPrSelectionResult result;
		try
		{
			result = GradleExplicitPrSelection.select(getCoverageMapFile().getAsFile().get(),
					getHeadTestInventoryFile().getAsFile().get(), getProject().getProjectDir(), getIntegrationRevision().get(),
					getPrBaseRevision().get(), getPrHeadRevision().get(), getMaxCommitDistance().get(),
					getFullSuiteTriggers().getOrElse(List.of()));
		}
		catch (Exception failure)
		{
			result = ExplicitPrSelectionResult.failure(ExplicitPrSelectionStatus.ERROR,
					"Explicit PR selection failed: " + failure.getMessage());
		}
		write(getExplicitResultFile().getAsFile().get(), GradleExplicitPrSelection.report(result));
		getLogger().lifecycle("[SmartTestPicker] Explicit PR status: {} — {}", result.status(), result.reason());
		if (result.status() != ExplicitPrSelectionStatus.SELECTION_RESULT)
		{
			getSelectedTestsFile().getAsFile().get().delete();
			if (result.status() == ExplicitPrSelectionStatus.ERROR) throw new GradleException(result.reason());
			return;
		}
		SelectionOutput output = result.output().orElseThrow();
		write(getSelectedTestsFile().getAsFile().get(), output);
	}

	private static void write(File outputFile, Object value)
	{
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		File parent = outputFile.getParentFile();
		if (parent != null) parent.mkdirs();
		try (FileWriter writer = new FileWriter(outputFile))
		{
			gson.toJson(value, writer);
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to write selected tests file", e);
		}
	}
}
