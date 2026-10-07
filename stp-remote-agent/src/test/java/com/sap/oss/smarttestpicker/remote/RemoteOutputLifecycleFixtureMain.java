// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

/** Child JVM used to prove the output file is finalized by the real agent shutdown hook. */
public final class RemoteOutputLifecycleFixtureMain {
	private RemoteOutputLifecycleFixtureMain() { }

	public static void main(String[] arguments) {
		try (var scope = RemoteTestContext.enter(new RemoteRequestIdentity("shutdown-suite", "shutdown-test", "shutdown-request"))) {
			RemoteRecorder.methodHit("org.springframework.samples.petclinic.vet.VetController", "showResourcesVetList", "()V");
		}
		System.out.println("OUTPUT_FIXTURE_READY");
	}
}
