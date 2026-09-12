// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class JacocoExecutionDataSourceTest
{
	@Test
	void resetsBeforeEveryTestAndSnapshotsWithoutAccumulation()
	{
		FakeAgent agent = new FakeAgent();
		JacocoExecutionDataSource source = new JacocoExecutionDataSource(agent);

		source.startSession("A");
		agent.next = new byte[] { 1 };
		assertArrayEquals(new byte[] { 1 }, source.snapshotAndReset());
		source.startSession("B");
		agent.next = new byte[] { 2 };
		assertArrayEquals(new byte[] { 2 }, source.snapshotAndReset());

		assertEquals(List.of("session:A", "reset", "snapshot:true", "session:B", "reset", "snapshot:true"),
				agent.calls);
	}

	public static final class FakeAgent
	{
		final List<String> calls = new ArrayList<>();
		byte[] next = new byte[0];
		public void setSessionId(String id) { calls.add("session:" + id); }
		public void reset() { calls.add("reset"); }
		public byte[] getExecutionData(boolean reset) { calls.add("snapshot:" + reset); return next; }
	}
}
