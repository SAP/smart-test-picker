// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.util.Set;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableSelectionResult;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;

/** Production boundary from schema-v3 selection to one explicitly chosen build adapter. */
public final class ExecutableAssignmentOrchestrator
{
	@FunctionalInterface public interface AdapterTransport { void dispatch(byte[] assignment); }

	public ExecutableShardAssignment dispatch(ExecutableSelectionResult selection, CoverageMapRevision revision,
			ShardId shardId, BuildTool adapter, AdapterTransport transport)
	{
		if (selection == null || revision == null || shardId == null || adapter == null || transport == null)
			throw new IllegalArgumentException("selection, revision, shard, adapter, and transport are required");
		if (!selection.revision().equals(revision))
			throw new IllegalArgumentException("Executable selection revision mismatch");
		if (selection.selectedTests().stream().anyMatch(test -> test.target().buildTool() != adapter))
			throw new IllegalArgumentException("Executable selection target does not match " + adapter + " adapter");
		ExecutableShardAssignment assignment = new ExecutableShardAssignment(
				ExecutableShardAssignment.CURRENT_VERSION, revision, shardId, Set.copyOf(selection.selectedTests()));
		transport.dispatch(new ExecutableShardAssignmentCodec().serialize(assignment));
		return assignment;
	}
}
