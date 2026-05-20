// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.mapper;


/**
 * Metadata section of the coverage map JSON.
 *
 * <p>Stores the context in which the coverage map was generated:</p>
 * <ul>
 *   <li>{@code baseBranch} — the branch this map was generated on (e.g. "main")</li>
 *   <li>{@code commitId} — the Git commit SHA at generation time, used as the diff baseline</li>
 *   <li>{@code timestamp} — ISO-8601 timestamp of when the map was generated</li>
 * </ul>
 *
 * <p>The {@code commitId} is critical for change detection: the plugin runs
 * {@code git diff commitId..HEAD} to find what changed since the map was created.</p>
 */
public class CoverageMapMetadata
{

	/** Git branch name on which the coverage map was generated. */
	private String baseBranch;

	/** Git commit SHA at the time the map was generated — used as diff baseline. */
	private String commitId;

	/** ISO-8601 timestamp of when the map was generated. */
	private String timestamp;

	public CoverageMapMetadata()
	{
	}

	public CoverageMapMetadata(String baseBranch, String commitId, String timestamp)
	{
		this.baseBranch = baseBranch;
		this.commitId = commitId;
		this.timestamp = timestamp;
	}

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
}
