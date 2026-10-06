// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.lang.instrument.Instrumentation;

public final class RemoteStpAgent {
	private RemoteStpAgent() { }

	public static void premain(String arguments, Instrumentation instrumentation) {
		if (instrumentation == null) throw new NullPointerException("instrumentation");
		RemoteAgentConfiguration configuration = RemoteAgentConfiguration.parse(arguments);
		RemoteRecorder.install(configuration.output());
		instrumentation.addTransformer(new RemoteHttpBoundaryTransformer(), false);
		instrumentation.addTransformer(new RemoteSpringMvcCallableTransformer(), false);
		instrumentation.addTransformer(new RemoteSpringAsyncTransformer(), false);
		instrumentation.addTransformer(new RemoteExecutorCallSiteTransformer(configuration), false);
		instrumentation.addTransformer(new RemoteMethodEntryTransformer(configuration), false);
		Runtime.getRuntime().addShutdownHook(new Thread(RemoteRecorder::writeOutput, "stp-remote-agent-output"));
	}
}
