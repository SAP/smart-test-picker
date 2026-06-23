// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.mapper;


/**
 * Per-class coverage metrics extracted from JaCoCo XML reports.
 *
 * <p>Stores LINE and BRANCH counter totals for a single class, aggregated
 * across all tests that cover it. Serialized into the coverage map JSON
 * under the {@code classMetrics} key.</p>
 */
public class ClassCoverageMetrics
{

	private int lineMissed;
	private int lineCovered;
	private int branchMissed;
	private int branchCovered;

	public ClassCoverageMetrics()
	{
	}

	public ClassCoverageMetrics(int lineMissed, int lineCovered, int branchMissed, int branchCovered)
	{
		this.lineMissed = lineMissed;
		this.lineCovered = lineCovered;
		this.branchMissed = branchMissed;
		this.branchCovered = branchCovered;
	}

	public double getLineCoveragePercent()
	{
		int total = lineMissed + lineCovered;
		return total > 0 ? (double) lineCovered / total * 100 : 0;
	}

	public double getBranchCoveragePercent()
	{
		int total = branchMissed + branchCovered;
		return total > 0 ? (double) branchCovered / total * 100 : 0;
	}

	/**
	 * Merges coverage from another test's view of the same class.
	 * Takes the best-case: max covered, min missed.
	 */
	public static ClassCoverageMetrics merge(ClassCoverageMetrics a, ClassCoverageMetrics b)
	{
		return new ClassCoverageMetrics(
				Math.min(a.lineMissed, b.lineMissed),
				Math.max(a.lineCovered, b.lineCovered),
				Math.min(a.branchMissed, b.branchMissed),
				Math.max(a.branchCovered, b.branchCovered)
		);
	}

	public int getLineMissed()
	{
		return lineMissed;
	}

	public void setLineMissed(int lineMissed)
	{
		this.lineMissed = lineMissed;
	}

	public int getLineCovered()
	{
		return lineCovered;
	}

	public void setLineCovered(int lineCovered)
	{
		this.lineCovered = lineCovered;
	}

	public int getBranchMissed()
	{
		return branchMissed;
	}

	public void setBranchMissed(int branchMissed)
	{
		this.branchMissed = branchMissed;
	}

	public int getBranchCovered()
	{
		return branchCovered;
	}

	public void setBranchCovered(int branchCovered)
	{
		this.branchCovered = branchCovered;
	}
}
