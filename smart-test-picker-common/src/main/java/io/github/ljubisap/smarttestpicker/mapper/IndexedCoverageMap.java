// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.mapper;

import java.util.List;
import java.util.Map;


/**
 * Space-optimized coverage map format that uses integer indexes instead of repeated strings.
 *
 * <p>In a standard {@link CoverageMap}, every test entry contains full FQN strings for all
 * covered classes and methods. With thousands of tests covering the same classes, these
 * strings are duplicated many times. The indexed format stores each unique class and method
 * string exactly once in shared index arrays, and test mappings reference them by position.</p>
 *
 * <p>JSON structure:</p>
 * <pre>{@code
 * {
 *   "metadata": { ... },
 *   "classIndex": ["com.example.Foo", "com.example.Bar", ...],
 *   "methodIndex": ["com.example.Foo#doWork", "com.example.Bar#init", ...],
 *   "testMappings": {
 *     "MyTest#testSomething": { "classes": [0, 1], "methods": [0, 1] }
 *   }
 * }
 * }</pre>
 *
 * <p>Typical size reduction: 10-15x compared to plain JSON (e.g. 944MB to 67MB for
 * 5755 tests with 6206 unique classes).</p>
 *
 * @see CoverageMapReader
 * @see io.github.ljubisap.smarttestpicker.engine.CoverageMapEngine
 */
public class IndexedCoverageMap
{

	private CoverageMapMetadata metadata;
	private List<String> classIndex;
	private List<String> methodIndex;
	private Map<String, TestCoverageRef> testMappings;
	private Map<String, ClassCoverageMetrics> classMetrics;

	public IndexedCoverageMap()
	{
	}

	public IndexedCoverageMap(CoverageMapMetadata metadata, List<String> classIndex,
			List<String> methodIndex, Map<String, TestCoverageRef> testMappings)
	{
		this.metadata = metadata;
		this.classIndex = classIndex;
		this.methodIndex = methodIndex;
		this.testMappings = testMappings;
	}

	public CoverageMapMetadata getMetadata()
	{
		return metadata;
	}

	public void setMetadata(CoverageMapMetadata metadata)
	{
		this.metadata = metadata;
	}

	public List<String> getClassIndex()
	{
		return classIndex;
	}

	public void setClassIndex(List<String> classIndex)
	{
		this.classIndex = classIndex;
	}

	public List<String> getMethodIndex()
	{
		return methodIndex;
	}

	public void setMethodIndex(List<String> methodIndex)
	{
		this.methodIndex = methodIndex;
	}

	public Map<String, TestCoverageRef> getTestMappings()
	{
		return testMappings;
	}

	public void setTestMappings(Map<String, TestCoverageRef> testMappings)
	{
		this.testMappings = testMappings;
	}

	public Map<String, ClassCoverageMetrics> getClassMetrics()
	{
		return classMetrics;
	}

	public void setClassMetrics(Map<String, ClassCoverageMetrics> classMetrics)
	{
		this.classMetrics = classMetrics;
	}


	/** Per-test coverage data using integer references into the shared class/method indexes. */
	public static class TestCoverageRef
	{
		private List<Integer> classes;
		private List<Integer> methods;

		public TestCoverageRef()
		{
		}

		public TestCoverageRef(List<Integer> classes, List<Integer> methods)
		{
			this.classes = classes;
			this.methods = methods;
		}

		public List<Integer> getClasses()
		{
			return classes;
		}

		public void setClasses(List<Integer> classes)
		{
			this.classes = classes;
		}

		public List<Integer> getMethods()
		{
			return methods;
		}

		public void setMethods(List<Integer> methods)
		{
			this.methods = methods;
		}
	}
}
