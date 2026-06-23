// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.report;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ljubisap.smarttestpicker.mapper.ClassCoverageMetrics;


/**
 * Plain data carrier holding all information needed to render the HTML report.
 *
 * <p>This POJO is populated by {@code GenerateTestReportTask} and passed to
 * {@link HtmlReportGenerator} for rendering. It has no Gradle dependency,
 * making the generator fully unit-testable.</p>
 */
public class ReportData
{

	// --- Metadata from coverage map ---

	/** The base branch this coverage map was generated against (e.g. "main"). */
	private String baseBranch;

	/** The Git commit SHA at coverage map generation time — used as diff baseline. */
	private String commitId;

	/** ISO-8601 timestamp of when the coverage map was generated. */
	private String timestamp;

	// --- Live Git context ---

	/** The current Git branch name. */
	private String currentBranch;

	/** Number of commits between the coverage map's commitId and HEAD. */
	private int commitDistance;

	// --- Change detection results ---

	/** Set of changed production class FQNs (e.g. "org.example.Foo"). */
	private Set<String> changedClasses;

	/** Set of changed method FQNs (e.g. "org.example.Foo#bar"). */
	private Set<String> changedMethods;

	// --- Selection results ---

	/** Set of selected test names (e.g. "FooTest#testBar"). */
	private Set<String> selectedTests;

	/** Whether the full test suite is required (e.g. stale map, missing metadata). */
	private boolean fullSuite;

	/** Whether no tests were impacted (no production code changes detected). */
	private boolean none;

	/** Human-readable reason when full suite is required. */
	private String fullSuiteReason;

	/** Whether class-level selection is enabled (include entire test classes, not individual methods). */
	private boolean classLevelSelection;

	// --- Full coverage map data (for the matrix) ---

	/** Complete test-to-coverage mappings: testName -> { "classes": [...], "methods": [...] }. */
	private Map<String, Map<String, List<String>>> testMappings;

	/** Test class names found on disk but not in the coverage map (FQN → reason). */
	private Map<String, String> unmappedTests;

	/** Source coverage page links: classFQN → relative HTML path (e.g. "sources/org.example.Foo.html"). */
	private Map<String, String> sourceLinks;

	/** Per-class line coverage percentages: classFQN → percentage (0.0–100.0). */
	private Map<String, Double> classCoveragePercentages;

	/** Per-class aggregated coverage metrics from the coverage map (baseline). */
	private Map<String, ClassCoverageMetrics> classMetrics;

	/** Number of external chunk JS files generated (0 = inline mode for backward compatibility). */
	private int chunkCount;

	/** Total number of tests in the coverage map (used by chunk mode for page info). */
	private int totalTestCount;

	// --- Getters and Setters ---

	public String getBaseBranch()
	{
		return baseBranch;
	}

	public void setBaseBranch(String baseBranch)
	{
		this.baseBranch = baseBranch;
	}

	public String getCommitId()
	{
		return commitId;
	}

	public void setCommitId(String commitId)
	{
		this.commitId = commitId;
	}

	public String getTimestamp()
	{
		return timestamp;
	}

	public void setTimestamp(String timestamp)
	{
		this.timestamp = timestamp;
	}

	public String getCurrentBranch()
	{
		return currentBranch;
	}

	public void setCurrentBranch(String currentBranch)
	{
		this.currentBranch = currentBranch;
	}

	public int getCommitDistance()
	{
		return commitDistance;
	}

	public void setCommitDistance(int commitDistance)
	{
		this.commitDistance = commitDistance;
	}

	public Set<String> getChangedClasses()
	{
		return changedClasses;
	}

	public void setChangedClasses(Set<String> changedClasses)
	{
		this.changedClasses = changedClasses;
	}

	public Set<String> getChangedMethods()
	{
		return changedMethods;
	}

	public void setChangedMethods(Set<String> changedMethods)
	{
		this.changedMethods = changedMethods;
	}

	public Set<String> getSelectedTests()
	{
		return selectedTests;
	}

	public void setSelectedTests(Set<String> selectedTests)
	{
		this.selectedTests = selectedTests;
	}

	public boolean isFullSuite()
	{
		return fullSuite;
	}

	public void setFullSuite(boolean fullSuite)
	{
		this.fullSuite = fullSuite;
	}

	public boolean isNone()
	{
		return none;
	}

	public void setNone(boolean none)
	{
		this.none = none;
	}

	public String getFullSuiteReason()
	{
		return fullSuiteReason;
	}

	public void setFullSuiteReason(String fullSuiteReason)
	{
		this.fullSuiteReason = fullSuiteReason;
	}

	public boolean isClassLevelSelection()
	{
		return classLevelSelection;
	}

	public void setClassLevelSelection(boolean classLevelSelection)
	{
		this.classLevelSelection = classLevelSelection;
	}

	public Map<String, Map<String, List<String>>> getTestMappings()
	{
		return testMappings;
	}

	public void setTestMappings(Map<String, Map<String, List<String>>> testMappings)
	{
		this.testMappings = testMappings;
	}

	public Map<String, String> getUnmappedTests()
	{
		return unmappedTests;
	}

	public void setUnmappedTests(Map<String, String> unmappedTests)
	{
		this.unmappedTests = unmappedTests;
	}

	public Map<String, String> getSourceLinks()
	{
		return sourceLinks;
	}

	public void setSourceLinks(Map<String, String> sourceLinks)
	{
		this.sourceLinks = sourceLinks;
	}

	public Map<String, Double> getClassCoveragePercentages()
	{
		return classCoveragePercentages;
	}

	public void setClassCoveragePercentages(Map<String, Double> classCoveragePercentages)
	{
		this.classCoveragePercentages = classCoveragePercentages;
	}

	public Map<String, ClassCoverageMetrics> getClassMetrics()
	{
		return classMetrics;
	}

	public void setClassMetrics(Map<String, ClassCoverageMetrics> classMetrics)
	{
		this.classMetrics = classMetrics;
	}

	public int getChunkCount()
	{
		return chunkCount;
	}

	public void setChunkCount(int chunkCount)
	{
		this.chunkCount = chunkCount;
	}

	public int getTotalTestCount()
	{
		return totalTestCount;
	}

	public void setTotalTestCount(int totalTestCount)
	{
		this.totalTestCount = totalTestCount;
	}
}
