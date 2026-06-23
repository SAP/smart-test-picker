// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.mapper;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Filters test classes and test infrastructure classes from coverage map data.
 *
 * <p>JaCoCo records all classes loaded during test execution, including the test class itself,
 * test helpers, and test framework infrastructure. These are not production code and should
 * be excluded from the coverage map used for regression test selection.</p>
 */
public final class TestClassFilter
{

	private TestClassFilter()
	{
	}

	/**
	 * Returns true if the given fully qualified class name looks like a test class.
	 *
	 * <p>Matches by class name conventions (suffix/prefix) and by known test-related packages.</p>
	 */
	public static boolean isTestClass(String classFqn)
	{
		if (classFqn == null || classFqn.isEmpty())
			return false;

		String simpleName = classFqn.contains(".")
				? classFqn.substring(classFqn.lastIndexOf('.') + 1)
				: classFqn;

		if (simpleName.contains("$"))
		{
			simpleName = simpleName.substring(0, simpleName.indexOf('$'));
		}

		if (simpleName.endsWith("Test")
				|| simpleName.endsWith("Tests")
				|| simpleName.endsWith("Spec")
				|| simpleName.endsWith("IT")
				|| simpleName.endsWith("TestCase")
				|| simpleName.startsWith("Test"))
		{
			return true;
		}

		return classFqn.contains(".test.")
				|| classFqn.contains(".tests.")
				|| classFqn.contains(".testsrc.")
				|| classFqn.contains(".testframework.");
	}

	/**
	 * Removes test classes from the "classes" list and their methods from the "methods" list
	 * within a single test's coverage entry.
	 */
	public static void filterTestClasses(Map<String, List<String>> coverage)
	{
		if (coverage == null)
			return;

		List<String> classes = coverage.get("classes");
		if (classes == null)
			return;

		Set<String> removedClasses = classes.stream()
				.filter(TestClassFilter::isTestClass)
				.collect(Collectors.toSet());

		if (removedClasses.isEmpty())
			return;

		classes.removeAll(removedClasses);

		List<String> methods = coverage.get("methods");
		if (methods != null)
		{
			Iterator<String> it = methods.iterator();
			while (it.hasNext())
			{
				String method = it.next();
				int hash = method.indexOf('#');
				if (hash > 0)
				{
					String methodClass = method.substring(0, hash);
					if (removedClasses.contains(methodClass))
					{
						it.remove();
					}
				}
			}
		}
	}

	/**
	 * Applies test class filtering to all entries in a test mappings map.
	 */
	public static void filterAll(Map<String, Map<String, List<String>>> testMappings)
	{
		if (testMappings == null)
			return;

		for (Map<String, List<String>> coverage : testMappings.values())
		{
			filterTestClasses(coverage);
		}
	}
}
