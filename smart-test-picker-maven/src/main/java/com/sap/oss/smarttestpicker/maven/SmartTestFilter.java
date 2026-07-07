// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import com.sap.oss.smarttestpicker.engine.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.sap.oss.smarttestpicker.selector.SelectionOutput;


/**
 * Converts a {@link SelectionOutput} into a Surefire-compatible includes file.
 *
 * <p>Surefire's {@code includesFile} parameter reads one test pattern per line.
 * This class writes patterns that match the selection output:</p>
 * <ul>
 *   <li>FULL_SUITE — no file written (Surefire runs everything)</li>
 *   <li>NONE + no unmapped — sentinel pattern that matches nothing</li>
 *   <li>SELECTED — selected tests + unmapped test classes</li>
 * </ul>
 */
public class SmartTestFilter
{

	/**
	 * Writes a Surefire includes file from the selection output.
	 * If the status is FULL_SUITE, no file is written (Surefire runs everything by default).
	 *
	 * @param output              the selection output
	 * @param includesFile        the file to write patterns to
	 * @param classLevelSelection whether to use class-level patterns
	 */
	public static void writeIncludesFile(SelectionOutput output, File includesFile,
			boolean classLevelSelection) throws IOException
	{
		if (output == null || "FULL_SUITE".equals(output.getStatus()))
		{
			List<String> defaultPatterns = List.of(
					"**/*Test.java", "**/*Tests.java", "**/*TestCase.java");
			FileUtils.ensureParentDirExists(includesFile);
			Files.write(includesFile.toPath(), defaultPatterns);
			return;
		}

		List<String> lines = new ArrayList<>();

		if ("NONE".equals(output.getStatus()))
		{
			if (output.getUnmappedTests() == null || output.getUnmappedTests().isEmpty())
			{
				lines.add("__no_tests_to_run__");
			}
			else
			{
				for (String fqn : output.getUnmappedTests().keySet())
				{
					lines.add(fqn.replace('.', '/') + ".java");
				}
			}
		}
		else
		{
			// SELECTED — include entire affected test classes so all methods run
			List<String> selectedTests = output.getSelectedTests() != null
					? output.getSelectedTests() : List.of();

			Set<String> classNames = new LinkedHashSet<>();
			for (String test : selectedTests)
			{
				int hash = test.indexOf('#');
				String className = hash > 0 ? test.substring(0, hash) : test;
				classNames.add(className);
			}

			for (String className : classNames)
			{
				lines.add(className);
			}

			if (output.getUnmappedTests() != null)
			{
				for (String fqn : output.getUnmappedTests().keySet())
				{
					lines.add(fqn.replace('.', '/') + ".java");
				}
			}
		}

			FileUtils.ensureParentDirExists(includesFile);
		Files.write(includesFile.toPath(), lines);
	}
}
