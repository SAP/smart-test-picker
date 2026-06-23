// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.List;
import java.util.Map;


/**
 * JSON-serializable output of the selectTests task.
 *
 * <p>Written to {@code build/selected-tests.json} by {@code SelectTestsTask}
 * and consumed by {@code GenerateTestReportTask} and {@code smartTest}.</p>
 *
 * <p>JSON structure:</p>
 * <pre>{@code
 * {
 *   "status": "SELECTED",
 *   "reason": "6 tests selected out of 52 total",
 *   "selectedTests": ["TestClass#method", ...],
 *   "unmappedTests": ["NewTestClass", ...]
 * }
 * }</pre>
 */
public class SelectionOutput
{

	/** Status: "SELECTED", "FULL_SUITE", or "NONE". */
	private String status;

	/** Human-readable reason for the selection outcome. */
	private String reason;

	/** Test names selected from the coverage map ({@code TestClass#method}). */
	private List<String> selectedTests;

	/** Test class names found on disk but not in the coverage map (FQN → reason). */
	private Map<String, String> unmappedTests;

	/** Changed production class FQNs detected by git diff. */
	private List<String> changedClasses;

	public SelectionOutput()
	{
	}

	public SelectionOutput(String status, String reason, List<String> selectedTests, Map<String, String> unmappedTests)
	{
		this.status = status;
		this.reason = reason;
		this.selectedTests = selectedTests;
		this.unmappedTests = unmappedTests;
	}

	public String getStatus()
	{
		return status;
	}

	public void setStatus(String status)
	{
		this.status = status;
	}

	public String getReason()
	{
		return reason;
	}

	public void setReason(String reason)
	{
		this.reason = reason;
	}

	public List<String> getSelectedTests()
	{
		return selectedTests;
	}

	public void setSelectedTests(List<String> selectedTests)
	{
		this.selectedTests = selectedTests;
	}

	public Map<String, String> getUnmappedTests()
	{
		return unmappedTests;
	}

	public void setUnmappedTests(Map<String, String> unmappedTests)
	{
		this.unmappedTests = unmappedTests;
	}

	public List<String> getChangedClasses()
	{
		return changedClasses;
	}

	public void setChangedClasses(List<String> changedClasses)
	{
		this.changedClasses = changedClasses;
	}
}
