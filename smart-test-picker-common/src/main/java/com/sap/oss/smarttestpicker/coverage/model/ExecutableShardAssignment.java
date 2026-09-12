// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Versioned artifact payload assigning executable occurrences to one shard. */
public record ExecutableShardAssignment(int version, CoverageMapRevision revision, ShardId shardId,
		Set<ExecutableTestIdentity> tests)
{
	public static final int CURRENT_VERSION = 1;
	public ExecutableShardAssignment
	{
		if (version != CURRENT_VERSION) throw new IllegalArgumentException("Unsupported executable assignment version: " + version);
		if (revision == null) throw new IllegalArgumentException("revision is required");
		if (shardId == null) throw new IllegalArgumentException("shardId is required");
		Objects.requireNonNull(tests, "tests");
		if (tests.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("assignment contains null identity");
		tests = Collections.unmodifiableSet(new TreeSet<>(tests));
	}
}
