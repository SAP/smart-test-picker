// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;

public final class AgentFixtureMain {
	private AgentFixtureMain() {
	}

	public static void main(String[] args) throws Exception {
		Class<?> runtimeType = Class.forName("com.sap.oss.smarttestpicker.runtime.model.TestIdentity");
		if (runtimeType.getClassLoader() != AgentFixtureMain.class.getClassLoader()) {
			throw new IllegalStateException("agent runtime is not visible to the application classloader");
		}
		if (RuntimeContextRegistry.current().isEmpty()) {
			throw new IllegalStateException("agent runtime registry is not visible to the application classloader");
		}
		System.out.println("fixture-ok");
	}
}
