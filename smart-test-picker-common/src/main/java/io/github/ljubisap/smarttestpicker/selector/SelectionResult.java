// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.selector;

import java.util.Collections;
import java.util.Set;


/**
 * Immutable result of the test selection process.
 *
 * <p>Represents one of two outcomes:</p>
 * <ul>
 *   <li><b>Selected tests</b> — a specific set of test names to run
 *       (created via {@link #selected(Set)})</li>
 *   <li><b>Full suite required</b> — all tests must run, with a reason explaining why
 *       (created via {@link #fullSuite(String)})</li>
 * </ul>
 *
 * <p>When the full suite is required, {@link #getSelectedTests()} returns an empty set
 * and {@link #getReason()} explains why (e.g. "Coverage map file not found").</p>
 */
public class SelectionResult
{

	/** The set of selected test names (format: {@code TestClass#testMethod}). */
	private final Set<String> selectedTests;

	/** Whether the full test suite must be run instead of selected tests. */
	private final boolean fullSuiteRequired;

	/** Human-readable reason why the full suite is required (null if not required). */
	private final String reason;

	private SelectionResult(Set<String> selectedTests, boolean fullSuiteRequired, String reason)
	{
		this.selectedTests = selectedTests;
		this.fullSuiteRequired = fullSuiteRequired;
		this.reason = reason;
	}

	/**
	 * Creates a result with a specific set of selected tests.
	 *
	 * @param tests the test names to run (may be empty if no tests are impacted)
	 * @return a selection result with the given tests
	 */
	public static SelectionResult selected(Set<String> tests)
	{
		return new SelectionResult(tests, false, null);
	}

	/**
	 * Creates a result indicating that the full test suite must be run.
	 *
	 * @param reason human-readable explanation (e.g. "Coverage map has no metadata")
	 * @return a full-suite selection result
	 */
	public static SelectionResult fullSuite(String reason)
	{
		return new SelectionResult(Collections.emptySet(), true, reason);
	}

	/**
	 * Returns the set of selected test names.
	 * Empty if {@link #isFullSuiteRequired()} is {@code true} or if no tests are impacted.
	 *
	 * @return unmodifiable set of test names (format: {@code TestClass#testMethod})
	 */
	public Set<String> getSelectedTests()
	{
		return selectedTests;
	}

	/**
	 * Returns whether the full test suite should be run instead of selected tests.
	 *
	 * @return {@code true} if all tests must run, {@code false} for selective execution
	 */
	public boolean isFullSuiteRequired()
	{
		return fullSuiteRequired;
	}

	/**
	 * Returns the reason why the full suite is required.
	 *
	 * @return explanation string, or {@code null} if full suite is not required
	 */
	public String getReason()
	{
		return reason;
	}
}
