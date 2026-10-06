// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

public final class RemoteHooks {
	private RemoteHooks() { }
	public static void methodHit(String className, String methodName, String descriptor) {
		RemoteRecorder.methodHit(className, methodName, descriptor);
	}
}
