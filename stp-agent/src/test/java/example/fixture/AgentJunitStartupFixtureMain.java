// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

import java.util.ServiceLoader;

/** Isolated-JVM proof that premain owns the runtime before listener construction. */
public final class AgentJunitStartupFixtureMain {
	private AgentJunitStartupFixtureMain() {
	}

	public static void main(String[] args) {
		var runtime = RuntimeContextRegistry.current().orElseThrow(() ->
				new IllegalStateException("premain did not install the runtime before application main"));
		TestExecutionListener listener = ServiceLoader.load(TestExecutionListener.class).stream()
				.filter(provider -> provider.type().getName().equals(
						"com.sap.oss.smarttestpicker.junit.StpRuntimeTestExecutionListener"))
				.findFirst().orElseThrow(() -> new IllegalStateException("STP listener provider is missing")).get();
		TestDescriptor descriptor = new AbstractTestDescriptor(
				UniqueId.forEngine("synthetic").append("test", "premain-before-listener"), "startup ordering") {
			@Override
			public Type getType() {
				return Type.TEST;
			}
		};
		TestIdentifier test = TestIdentifier.from(descriptor);
		listener.executionStarted(test);
		if (runtime.currentTest().isEmpty()) {
			throw new IllegalStateException("service-loaded listener did not resolve the premain runtime");
		}
		listener.executionFinished(test, TestExecutionResult.successful());
		if (runtime.currentTest().isPresent()) {
			throw new IllegalStateException("service-loaded listener did not close the test context");
		}
		System.out.println("premain-before-listener-ok");
	}
}
