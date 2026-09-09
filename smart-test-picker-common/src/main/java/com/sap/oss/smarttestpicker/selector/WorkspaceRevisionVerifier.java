// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;

/** Read-only safety boundary used before a build adapter binds generated artifacts to a revision. */
public final class WorkspaceRevisionVerifier
{
	private WorkspaceRevisionVerifier() {}

	public static String requireHead(File projectDir, String expectedRevision)
	{
		if (expectedRevision == null || expectedRevision.isBlank())
			throw new IllegalArgumentException("prHeadRevision is missing");
		GitRevisionAccess git = new GitRevisionAccess(projectDir);
		String expected = git.resolveCommit(expectedRevision);
		if (!expected.equalsIgnoreCase(expectedRevision))
			throw new IllegalArgumentException("prHeadRevision must be a full frozen commit ID");
		String workspaceHead = git.resolveCommit("HEAD");
		if (!workspaceHead.equalsIgnoreCase(expected))
			throw new IllegalStateException("Workspace HEAD does not equal prHeadRevision: "
					+ workspaceHead + " != " + expected);
		return expected;
	}
}
