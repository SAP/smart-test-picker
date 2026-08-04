// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeEventAggregator;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static com.sap.oss.smarttestpicker.springdata.AdapterActivationStatus.State.ACTIVE;
import static com.sap.oss.smarttestpicker.springdata.AdapterActivationStatus.State.DISABLED_NO_RUNTIME;
import static com.sap.oss.smarttestpicker.springdata.AdapterActivationStatus.State.DISABLED_NO_SPRING_DATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StpSpringDataApplicationContextInitializerTest {
	private final StpSpringDataApplicationContextInitializer initializer =
			new StpSpringDataApplicationContextInitializer();

	@Test
	void disabledByDefaultRegistersNothing() {
		try (GenericApplicationContext context = context(null, getClass().getClassLoader())) {
			initializer.initialize(context);
			assertFalse(context.containsBeanDefinition(StpSpringDataApplicationContextInitializer.STATUS_BEAN_NAME));
			assertEquals(0, context.getBeanDefinitionCount());
		}
	}

	@Test
	void explicitFalseRegistersNothing() {
		try (GenericApplicationContext context = context("false", getClass().getClassLoader())) {
			initializer.initialize(context);
			assertEquals(0, context.getBeanDefinitionCount());
		}
	}

	@Test
	void enabledWithInstalledRuntimeRegistersOnlyInertActiveStatus() {
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime());
				GenericApplicationContext context = context("true", getClass().getClassLoader())) {
			initializer.initialize(context);
			assertEquals(ACTIVE, status(context).state());
			assertEquals(8, context.getBeanDefinitionCount());
			assertTrue(context.containsBeanDefinition(StpSpringDataApplicationContextInitializer.COLLECTOR_BEAN_NAME));
			assertTrue(context.containsBeanDefinition(
					StpSpringDataApplicationContextInitializer.ELIGIBILITY_PROCESSOR_BEAN_NAME));
		}
	}

	@Test
	void enabledWithoutRuntimeDisablesWithOneBoundedDiagnostic() {
		try (GenericApplicationContext context = context("true", getClass().getClassLoader())) {
			initializer.initialize(context);
			assertEquals(DISABLED_NO_RUNTIME, status(context).state());
			assertInertInfrastructureOnly(context);
		}
	}

	@Test
	void malformedEnablementFailsClearly() {
		try (GenericApplicationContext context = context("yes", getClass().getClassLoader())) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> initializer.initialize(context));
			assertTrue(failure.getMessage().contains("must be exactly 'true' or 'false'"));
			assertEquals(0, context.getBeanDefinitionCount());
		}
	}

	@Test
	void duplicateInitializerInvocationDoesNotDuplicateInfrastructure() {
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime());
				GenericApplicationContext context = context("true", getClass().getClassLoader())) {
			initializer.initialize(context);
			initializer.initialize(context);
			assertEquals(8, context.getBeanDefinitionCount());
			assertEquals(ACTIVE, status(context).state());
		}
	}

	@Test
	void reservedBeanNameCollisionFailsWithoutOverwrite() {
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime());
				GenericApplicationContext context = context("true", getClass().getClassLoader())) {
			RootBeanDefinition userDefinition = new RootBeanDefinition(String.class, () -> "user-owned");
			context.registerBeanDefinition(StpSpringDataApplicationContextInitializer.STATUS_BEAN_NAME, userDefinition);
			IllegalStateException failure = assertThrows(IllegalStateException.class,
					() -> initializer.initialize(context));
			assertTrue(failure.getMessage().contains("reserved STP bean name collision"));
			assertEquals(userDefinition,
					context.getBeanDefinition(StpSpringDataApplicationContextInitializer.STATUS_BEAN_NAME));
		}
	}

	@Test
	void springDataAbsentWhileDisabledIsACompleteNoOp() {
		try (GenericApplicationContext context = context(null, withoutSpringData())) {
			initializer.initialize(context);
			assertEquals(0, context.getBeanDefinitionCount());
		}
	}

	@Test
	void springDataAbsentWhileEnabledDisablesCleanlyBeforeRuntimeLookup() {
		try (GenericApplicationContext context = context("true", withoutSpringData())) {
			initializer.initialize(context);
			assertEquals(DISABLED_NO_SPRING_DATA, status(context).state());
			assertInertInfrastructureOnly(context);
		}
	}

	@Test
	void springFactoriesDiscoversTheSinglePublicInitializer() {
		List<ApplicationContextInitializer> initializers = SpringFactoriesLoader.loadFactories(
				ApplicationContextInitializer.class, getClass().getClassLoader());
		assertEquals(1, initializers.stream()
				.filter(StpSpringDataApplicationContextInitializer.class::isInstance).count());
	}

	@Test
	void adapterJarContainsOnlyAdapterClassesAndDiscoveryMetadata() throws IOException {
		Path jarPath = Path.of(System.getProperty("stp.spring-data-adapter.jar"));
		try (JarFile jar = new JarFile(jarPath.toFile())) {
			assertTrue(jar.getEntry("META-INF/spring.factories") != null);
			assertTrue(jar.getEntry("com/sap/oss/smarttestpicker/springdata/"
					+ "StpSpringDataApplicationContextInitializer.class") != null);
			assertFalse(jar.stream().anyMatch(entry -> entry.getName().startsWith("org/springframework/")
					|| entry.getName().startsWith("com/sap/oss/smarttestpicker/runtime/")
					|| entry.getName().startsWith("org/objectweb/asm/")
					|| entry.getName().contains("/test/") || entry.getName().endsWith("Test.class")));
		}
	}

	private static void assertInertInfrastructureOnly(GenericApplicationContext context) {
		assertEquals(1, context.getBeanDefinitionCount());
		BeanDefinition definition = context.getBeanDefinition(
				StpSpringDataApplicationContextInitializer.STATUS_BEAN_NAME);
		assertEquals(BeanDefinition.ROLE_INFRASTRUCTURE, definition.getRole());
		if (!context.isActive()) context.refresh();
		Object status = context.getBean(StpSpringDataApplicationContextInitializer.STATUS_BEAN_NAME);
		assertInstanceOf(AdapterActivationStatus.class, status);
		assertFalse(status instanceof BeanPostProcessor);
		assertFalse(status instanceof Advisor);
	}

	private static AdapterActivationStatus status(GenericApplicationContext context) {
		if (!context.isActive()) context.refresh();
		return context.getBean(StpSpringDataApplicationContextInitializer.STATUS_BEAN_NAME,
				AdapterActivationStatus.class);
	}

	private static GenericApplicationContext context(String enabled, ClassLoader classLoader) {
		GenericApplicationContext context = new GenericApplicationContext();
		context.setClassLoader(classLoader);
		if (enabled != null) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
					Map.of(SpringDataAdapterProperties.ENABLED, enabled)));
		}
		return context;
	}

	private static RuntimeContextService runtime() {
		return new RuntimeContextService(new RuntimeEventAggregator("run-1", "jvm-1"));
	}

	private static ClassLoader withoutSpringData() {
		return new ClassLoader(StpSpringDataApplicationContextInitializerTest.class.getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.startsWith("org.springframework.data.")) throw new ClassNotFoundException(name);
				return super.loadClass(name, resolve);
			}
		};
	}
}
