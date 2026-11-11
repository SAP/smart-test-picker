// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.fixture;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Bounded real-JUnit process used by the agent-to-schema-v2 contract test. */
public final class AgentCoverageFragmentFixtureMain {
	private static Path productionJar;

	private AgentCoverageFragmentFixtureMain() {}

	public static void main(String[] args) {
		productionJar = Path.of(args[0]);
		LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
				.selectors(selectClass(FragmentSuite.class)).build());
		System.out.println("coverage-fragment-fixture-ok");
	}

	static class FragmentSuite {
		@Test
		void invokesInstrumentedProductionMethod() throws Exception {
			try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { productionJar.toUri().toURL() },
					ClassLoader.getSystemClassLoader()) {
				@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					if (name.equals("example.instrumented.Calculator")) {
						synchronized (getClassLoadingLock(name)) {
							Class<?> loaded = findLoadedClass(name);
							if (loaded == null) loaded = findClass(name);
							if (resolve) resolveClass(loaded);
							return loaded;
						}
					}
					return super.loadClass(name, resolve);
				}
			}) {
				Class<?> calculator = Class.forName("example.instrumented.Calculator", true, loader);
				Object instance = calculator.getConstructor().newInstance();
				Object result = calculator.getMethod("add", int.class, int.class).invoke(instance, 2, 3);
				if (!Integer.valueOf(5).equals(result)) throw new AssertionError(result);
				Object longResult = calculator.getMethod("add", long.class, long.class).invoke(instance, 2L, 3L);
				if (!Long.valueOf(5).equals(longResult)) throw new AssertionError(longResult);
			}
		}
	}
}
