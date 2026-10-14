// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

public final class RemoteHooks {
	private RemoteHooks() { }

	/**
	 * Hot-path entry hook injected into instrumented methods. Carries a single precomputed
	 * {@code long} method id (FNV-1a, assigned at class-transform time) instead of three strings,
	 * so the per-call cost on bulk loops is a long push plus one coverage-set add.
	 */
	public static void methodHit(long methodId) {
		RemoteRecorder.recordId(methodId);
	}
}
