// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.lang.instrument.Instrumentation;

public final class RemoteStpAgent {
	private RemoteStpAgent() { }

	public static void premain(String arguments, Instrumentation instrumentation) {
		if (instrumentation == null) throw new NullPointerException("instrumentation");
		try {
			io.opentelemetry.context.Context.current();
		} catch (NoClassDefFoundError missingOpenTelemetry) {
			throw new IllegalStateException("stp-remote-agent requires the OpenTelemetry Context API; attach the OpenTelemetry Java agent first for automatic context propagation", missingOpenTelemetry);
		}
		RemoteAgentConfiguration configuration = RemoteAgentConfiguration.parse(arguments);
		RemoteRecorder.install(configuration.output());
		// Let narrowly scoped callback boundaries run before the method-entry hook.
		instrumentation.addTransformer(new RemoteMethodEntryTransformer(configuration), false);
		if (!Boolean.getBoolean("stp.remote.otel.contextOnly")) {
			instrumentation.addTransformer(new RemoteHttpBoundaryTransformer(), false);
			instrumentation.addTransformer(new RemoteExecutorCallSiteTransformer(configuration), false);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(RemoteRecorder::writeOutput, "stp-remote-agent-output"));
	}
}
