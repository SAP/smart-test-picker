// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.selector;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ljubisap.smarttestpicker.mapper.CoverageMap;
import io.github.ljubisap.smarttestpicker.mapper.CoverageMapReader;


/**
 * Selects which tests to run based on code changes and a coverage map.
 *
 * <p>This is the core test selection engine. Given a set of changed classes and/or methods
 * (from {@link io.github.ljubisap.smarttestpicker.change.GitChangeDetector}) and a coverage
 * map JSON file, it determines which tests are impacted by the changes.</p>
 *
 * <p>Selection strategy:</p>
 * <ul>
 *   <li>If method-level info is available for a changed class, only tests covering those
 *       specific methods are selected (precise matching)</li>
 *   <li>If no method-level info is available (e.g. git couldn't determine which methods changed),
 *       all tests covering the changed class are selected (class-level fallback)</li>
 *   <li>If the coverage map is missing, invalid, or has no metadata, the full test suite is run</li>
 * </ul>
 *
 * @see SelectionResult
 * @see io.github.ljubisap.smarttestpicker.mapper.CoverageMap
 */
public class TestSelector
{

	/**
	 * Select tests based on changed classes and/or methods and coverage map.
	 * <p>
	 * When method-level info is available for a class (i.e. changedMethods contains entries
	 * for that class), only method-level matching is used for that class. Class-level fallback
	 * is only applied for changed classes where no method-level info could be extracted from the diff.
	 */
	public SelectionResult selectTests(File coverageMapFile, Set<String> changedClasses, Set<String> changedMethods)
	{
		if (coverageMapFile == null || !coverageMapFile.exists())
		{
			return SelectionResult.fullSuite("Coverage map file not found");
		}

		CoverageMap coverageMap = loadCoverageMap(coverageMapFile);
		if (coverageMap == null)
		{
			return SelectionResult.fullSuite("Failed to parse coverage map");
		}

		if (coverageMap.getMetadata() == null)
		{
			return SelectionResult.fullSuite("Coverage map has no metadata");
		}

		if (coverageMap.getTestMappings() == null || coverageMap.getTestMappings().isEmpty())
		{
			return SelectionResult.fullSuite("Coverage map has no test mappings");
		}

		if (changedClasses.isEmpty() && changedMethods.isEmpty())
		{
			return SelectionResult.selected(Set.of());
		}

		// Determine which classes have method-level info from the diff.
		// For these classes, we use ONLY method-level matching (more precise).
		// For classes without method-level info, we fall back to class-level matching.
		Set<String> classesWithMethodInfo = new HashSet<>();
		for (String method : changedMethods)
		{
			int hashIdx = method.indexOf('#');
			if (hashIdx > 0)
			{
				classesWithMethodInfo.add(method.substring(0, hashIdx));
			}
		}

		// Classes that need class-level fallback: changed but no method-level info available
		Set<String> classLevelOnlyClasses = new HashSet<>(changedClasses);
		classLevelOnlyClasses.removeAll(classesWithMethodInfo);

		Set<String> selectedTests = new HashSet<>();

		for (Map.Entry<String, Map<String, List<String>>> entry : coverageMap.getTestMappings().entrySet())
		{
			String testName = entry.getKey();
			Map<String, List<String>> coverage = entry.getValue();

			// Method-level matching (precise)
			if (!changedMethods.isEmpty())
			{
				List<String> coveredMethods = coverage.get("methods");
				if (coveredMethods != null)
				{
					for (String coveredMethod : coveredMethods)
					{
						if (changedMethods.contains(coveredMethod))
						{
							selectedTests.add(testName);
							break;
						}
					}
				}
			}

			// Class-level fallback — only for classes where method detection was not possible
			if (!selectedTests.contains(testName) && !classLevelOnlyClasses.isEmpty())
			{
				List<String> coveredClasses = coverage.get("classes");
				if (coveredClasses != null)
				{
					for (String coveredClass : coveredClasses)
					{
						if (classLevelOnlyClasses.contains(coveredClass))
						{
							selectedTests.add(testName);
							break;
						}
					}
				}
			}
		}

		return SelectionResult.selected(selectedTests);
	}

	/**
	 * Class-level only selection (backward compatible).
	 */
	public SelectionResult selectTests(File coverageMapFile, Set<String> changedClasses)
	{
		return selectTests(coverageMapFile, changedClasses, Set.of());
	}

	/**
	 * Loads and deserializes a coverage map JSON file.
	 *
	 * @param file the JSON file containing the coverage map
	 * @return the parsed {@link CoverageMap}, or {@code null} if parsing fails
	 */
	CoverageMap loadCoverageMap(File file)
	{
		try
		{
			return CoverageMapReader.load(file);
		}
		catch (IOException e)
		{
			return null;
		}
	}
}
