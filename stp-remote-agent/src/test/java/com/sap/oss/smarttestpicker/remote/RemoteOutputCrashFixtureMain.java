// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.util.concurrent.CountDownLatch;

/** Child JVM deliberately held alive so the parent can terminate it without shutdown hooks. */
public final class RemoteOutputCrashFixtureMain {
	private RemoteOutputCrashFixtureMain() { }

	public static void main(String[] arguments) throws InterruptedException {
		if (arguments.length > 0 && arguments[0].equals("record")) {
			try (var scope = RemoteTestContext.enter(new RemoteRequestIdentity("crash-suite", "lost-test", "lost-request"))) {
				RemoteRecorder.methodHit("example.CrashFixture", "recordedBeforeCrash", "()V");
			}
		} else if (arguments.length > 0 && arguments[0].equals("checkpoint")) {
			try (var scope = RemoteTestContext.enter(new RemoteRequestIdentity("crash-suite", "saved-test", "saved-request"))) {
				RemoteRecorder.methodHit("example.CrashFixture", "persistedBeforeCrash", "()V");
			}
			RemoteRecorder.checkpointNow();
			try (var scope = RemoteTestContext.enter(new RemoteRequestIdentity("crash-suite", "lost-test", "lost-request"))) {
				RemoteRecorder.methodHit("example.CrashFixture", "lostBeforeCrash", "()V");
			}
		}
		System.out.println("CRASH_FIXTURE_READY:" + (arguments.length == 0 ? "reserve" : arguments[0]));
		System.out.flush();
		new CountDownLatch(1).await();
	}
}
