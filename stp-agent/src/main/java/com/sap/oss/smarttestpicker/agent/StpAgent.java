// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.lang.instrument.Instrumentation;

public final class StpAgent {
	private StpAgent() {
	}

	public static void premain(String agentArgs, Instrumentation instrumentation) {
		long started = System.nanoTime();
		if (instrumentation == null) throw new NullPointerException("instrumentation");
		AgentConfiguration configuration = AgentConfiguration.parse(agentArgs);
		AgentOutputWriter.validate(configuration.output());
		AgentMetrics metrics = new AgentMetrics(started);
		AgentRuntime runtime = AgentRuntime.install(configuration, metrics);
		try {
			if (configuration.instrumentationEnabled()) {
				instrumentation.addTransformer(new ExecutorCallSiteTransformer(runtime::error), false);
				instrumentation.addTransformer(new MethodEntryClassFileTransformer(configuration, metrics,
						runtime.catalog(), runtime::error), false);
			} else {
				instrumentation.addTransformer(new NoOpClassFileTransformer(configuration, metrics), false);
			}
			Runtime.getRuntime().addShutdownHook(new Thread(runtime::writeOutput, "stp-agent-shutdown"));
		}
		catch (Throwable failure) {
			runtime.abortInitialization();
			throw failure;
		}
		if (configuration.debug()) {
			System.err.println("[stp-agent] mode="
					+ (configuration.instrumentationEnabled() ? "method-entry" : "debug-no-op")
					+ "; runId=" + configuration.runId());
		}
	}
}
