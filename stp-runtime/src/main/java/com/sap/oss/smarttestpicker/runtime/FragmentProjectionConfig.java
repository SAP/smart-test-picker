// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;

/** Externally supplied fragment binding; the collector never discovers either value. */
public record FragmentProjectionConfig(CoverageMapRevision revision, ShardId shardId) {
	public FragmentProjectionConfig {
		if (revision == null) throw new IllegalArgumentException("revision is required");
		if (shardId == null) throw new IllegalArgumentException("shardId is required");
	}

	public static FragmentProjectionConfig of(String revision, String shardId) {
		return new FragmentProjectionConfig(new CoverageMapRevision(revision), new ShardId(shardId));
	}
}
