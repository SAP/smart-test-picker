// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;

/** Frozen inputs for explicit PR revision eligibility. The map revision is owned by the coverage map. */
public record RevisionPreflightRequest(CoverageMap coverageMap, String integrationRevision,
		String prBaseRevision, String prHeadRevision, int maxCommitDistance)
{
	public String mapRevision()
	{
		return coverageMap == null || coverageMap.revision() == null ? null : coverageMap.revision().value();
	}
}
