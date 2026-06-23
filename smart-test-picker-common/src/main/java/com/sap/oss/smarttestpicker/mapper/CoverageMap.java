// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.mapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Top-level model for the coverage map JSON file.
 *
 * <p>Wraps the coverage metadata (branch, commit, timestamp) and the test-to-coverage
 * mappings into a single serializable structure. This is the primary data format
 * produced by {@code generateTestCoverageJson} and consumed by {@code selectTests}.</p>
 *
 * <p>JSON structure:</p>
 * <pre>{@code
 * {
 *   "metadata": { "baseBranch": "main", "commitId": "abc123", "timestamp": "..." },
 *   "testMappings": {
 *     "TestClass#method": { "classes": [...], "methods": [...] }
 *   }
 * }
 * }</pre>
 *
 * @see CoverageMapMetadata
 */
public class CoverageMap
{

	/** Metadata about when and where the coverage map was generated. */
	private CoverageMapMetadata metadata;

	/** Mapping from test name ({@code TestClass#method}) to its coverage data. */
	private Map<String, Map<String, List<String>>> testMappings;

	/** Per-class aggregated coverage metrics (LINE and BRANCH counters). */
	private Map<String, ClassCoverageMetrics> classMetrics;

	public CoverageMap()
	{
		this.testMappings = new HashMap<>();
	}

	public CoverageMap(CoverageMapMetadata metadata, Map<String, Map<String, List<String>>> testMappings)
	{
		this.metadata = metadata;
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

	public Map<String, Map<String, List<String>>> getTestMappings()
	{
		return testMappings;
	}

	public void setTestMappings(Map<String, Map<String, List<String>>> testMappings)
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
}
