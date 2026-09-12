// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.util.Collection;
import java.util.Set;

import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestInventory;

/** Versioned head artifact's semantic executable inventory. */
public record ExecutableHeadTestInventory(String revision, Set<ExecutableTestIdentity> runnableTests)
{
	public ExecutableHeadTestInventory
	{
		ExecutableTestInventory checked = ExecutableTestInventory.from(new CoverageMapRevision(revision), runnableTests);
		runnableTests = checked.expectedTests();
	}

	public static ExecutableHeadTestInventory atRevision(String revision,
			Collection<ExecutableTestIdentity> tests)
	{
		ExecutableTestInventory checked = ExecutableTestInventory.from(new CoverageMapRevision(revision), tests);
		return new ExecutableHeadTestInventory(revision, checked.expectedTests());
	}
}
