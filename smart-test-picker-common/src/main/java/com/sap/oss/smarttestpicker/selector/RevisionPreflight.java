// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;

import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidator;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationResult;

/** Build-tool-neutral, read-only eligibility preflight for explicit PR commits. */
public final class RevisionPreflight
{
	public RevisionPreflightResult evaluate(File projectDir, RevisionPreflightRequest request)
	{
		if (projectDir == null || !projectDir.isDirectory()) return error("Git project directory unavailable");
		if (request == null) return error("Revision preflight request is missing");
		if (request.maxCommitDistance() < 0) return error("maxCommitDistance must not be negative");
		try
		{
			if (request.coverageMap() == null) return error("Validated schema-v2 coverage map is missing");
			ValidationResult validation = CoverageMapValidator.validate(request.coverageMap());
			if (!validation.isValid())
				return error("Coverage map validation failed: " + validation.errors().get(0).message());
			GitRevisionAccess git = new GitRevisionAccess(projectDir);
			String map = resolveFrozen(git, "mapRevision", request.mapRevision());
			String integration = resolveFrozen(git, "integrationRevision", request.integrationRevision());
			// Retained as validated provider provenance for backward-compatible callers. Eligibility is
			// determined by the frozen integration-to-source-head graph, not provider base metadata.
			resolveFrozen(git, "prBaseRevision", request.prBaseRevision());
			String head = resolveFrozen(git, "prHeadRevision", request.prHeadRevision());

			if (!git.isAncestor(map, integration))
				return result(RevisionPreflightStatus.FULL_SUITE,
						"Coverage map revision is not an ancestor of integration revision");
			if (!git.isAncestor(integration, head))
				return result(RevisionPreflightStatus.BASE_OUT_OF_DATE,
						"BASE_OUT_OF_DATE: integration revision is not an ancestor of PR head revision");
			int distance = git.commitDistance(map, head);
			if (distance < 0) return error("Invalid commit distance");
			if (distance > request.maxCommitDistance())
				return result(RevisionPreflightStatus.FULL_SUITE,
						"Coverage map revision is stale: " + distance + " commits");
			return new RevisionPreflightResult(RevisionPreflightStatus.ELIGIBLE, "ELIGIBLE",
					new SelectionRevisionInterval(map, head, distance));
		}
		catch (RuntimeException e)
		{
			return error("Invalid revision input: " + safeMessage(e));
		}
	}

	private static String resolveFrozen(GitRevisionAccess git, String name, String revision)
	{
		if (revision == null || revision.isBlank()) throw new IllegalArgumentException(name + " is missing");
		String resolved = git.resolveCommit(revision);
		if (!resolved.equalsIgnoreCase(revision))
			throw new IllegalArgumentException(name + " must be a full frozen commit ID");
		return resolved;
	}

	private static RevisionPreflightResult error(String reason)
	{
		return result(RevisionPreflightStatus.ERROR, reason);
	}
	private static RevisionPreflightResult result(RevisionPreflightStatus status, String reason)
	{
		return new RevisionPreflightResult(status, reason, null);
	}
	private static String safeMessage(RuntimeException e)
	{
		return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
	}
}
