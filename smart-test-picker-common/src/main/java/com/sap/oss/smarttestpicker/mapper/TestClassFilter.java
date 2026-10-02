// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.mapper;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Filters coverage classes using build-provided class-output ownership.
 *
 * <p>A package or class name does not identify its source set. Production code is allowed to
 * use names such as {@code com.example.test.Foo}; callers must provide classes discovered in
 * configured production or test output directories.</p>
 */
public final class TestClassFilter
{

	private TestClassFilter()
	{
	}

	/**
	 * Returns whether the exact binary class belongs to a configured test output.
	 */
	public static boolean isTestClass(String classFqn, Set<String> testOutputClasses)
	{
		return classFqn != null && testOutputClasses != null && testOutputClasses.contains(classFqn);
	}

	/**
	 * Removes test classes from the "classes" list and their methods from the "methods" list
	 * within a single test's coverage entry.
	 */
	public static void filterTestClasses(Map<String, List<String>> coverage,
			Set<String> testOutputClasses)
	{
		filterClasses(coverage, classFqn -> !isTestClass(classFqn, testOutputClasses));
	}

	/**
	 * Retains only exact classes owned by configured production outputs and their methods.
	 * An empty set means that an ownership manifest was unavailable, so the already-scoped
	 * JaCoCo report remains unchanged for compatibility with older report directories.
	 */
	public static void retainProductionClasses(Map<String, List<String>> coverage,
			Set<String> productionOutputClasses)
	{
		if (productionOutputClasses == null || productionOutputClasses.isEmpty())
			return;
		filterClasses(coverage, productionOutputClasses::contains);
	}

	private static void filterClasses(Map<String, List<String>> coverage,
			java.util.function.Predicate<String> keepClass)
	{
		if (coverage == null)
			return;

		List<String> classes = coverage.get("classes");
		if (classes == null)
			return;

		Set<String> removedClasses = new java.util.HashSet<>();
		for (String classFqn : classes)
			if (!keepClass.test(classFqn)) removedClasses.add(classFqn);
		if (removedClasses.isEmpty())
			return;

		classes.removeAll(removedClasses);
		List<String> methods = coverage.get("methods");
		if (methods == null)
			return;

		Iterator<String> it = methods.iterator();
		while (it.hasNext())
		{
			String method = it.next();
			int hash = method.indexOf('#');
			if (hash > 0 && removedClasses.contains(method.substring(0, hash)))
				it.remove();
		}
	}
}
