// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;

/** Explicit, disabled-by-default entry point for the experimental adapter. */
public final class StpSpringDataApplicationContextInitializer
		implements ApplicationContextInitializer<ConfigurableApplicationContext> {
	static final String STATUS_BEAN_NAME = "com.sap.oss.smarttestpicker.springdata.internalActivationStatus";
	static final String DIAGNOSTICS_BEAN_NAME = "com.sap.oss.smarttestpicker.springdata.internalMetadataDiagnostics";
	static final String REGISTRY_BEAN_NAME = "com.sap.oss.smarttestpicker.springdata.internalMetadataRegistry";
	static final String COLLECTOR_BEAN_NAME = "com.sap.oss.smarttestpicker.springdata.internalMetadataCollector";
	static final String ELIGIBILITY_REGISTRY_BEAN_NAME =
			"com.sap.oss.smarttestpicker.springdata.internalEligibilityRegistry";
	static final String ELIGIBILITY_PROCESSOR_BEAN_NAME =
			"com.sap.oss.smarttestpicker.springdata.internalEligibilityProcessor";
	static final String INSTALLATION_REGISTRY_BEAN_NAME =
			"com.sap.oss.smarttestpicker.springdata.internalAdvisorInstallationRegistry";
	static final String METRICS_BEAN_NAME = "com.sap.oss.smarttestpicker.springdata.internalRepositoryMetrics";
	private static final String OWNER_ATTRIBUTE = "com.sap.oss.smarttestpicker.springdata.owner";
	private static final String OWNER = StpSpringDataApplicationContextInitializer.class.getName();
	private static final String SPRING_DATA_MARKER = "org.springframework.data.repository.Repository";

	@Override
	public void initialize(ConfigurableApplicationContext applicationContext) {
		String configured = applicationContext.getEnvironment().getProperty(SpringDataAdapterProperties.ENABLED);
		if (configured == null || configured.equals("false")) return;
		if (!configured.equals("true")) {
			throw new IllegalArgumentException(SpringDataAdapterProperties.ENABLED
					+ " must be exactly 'true' or 'false', but was '" + configured + "'");
		}

		ClassLoader classLoader = applicationContext.getClassLoader();
		if (!ClassUtils.isPresent(SPRING_DATA_MARKER, classLoader)) {
			registerStatus(applicationContext, AdapterActivationStatus.State.DISABLED_NO_SPRING_DATA);
			return;
		}
		if (RuntimeContextRegistry.current().isEmpty()) {
			registerStatus(applicationContext, AdapterActivationStatus.State.DISABLED_NO_RUNTIME);
			return;
		}
		registerStatus(applicationContext, AdapterActivationStatus.State.ACTIVE);
		registerActiveInfrastructure(applicationContext);
	}

	private static void registerActiveInfrastructure(ConfigurableApplicationContext applicationContext) {
		BeanDefinitionRegistry registry = registry(applicationContext);
		registerOwned(registry, DIAGNOSTICS_BEAN_NAME, new RootBeanDefinition(MetadataDiagnostics.class));
		registerOwned(registry, METRICS_BEAN_NAME, new RootBeanDefinition(RepositoryAdapterMetrics.class));

		RootBeanDefinition metadataRegistry = new RootBeanDefinition(RepositoryMetadataRegistry.class);
		metadataRegistry.getConstructorArgumentValues().addIndexedArgumentValue(0, applicationContext.getId());
		metadataRegistry.getConstructorArgumentValues().addIndexedArgumentValue(1,
				new RuntimeBeanReference(DIAGNOSTICS_BEAN_NAME));
		registerOwned(registry, REGISTRY_BEAN_NAME, metadataRegistry);

		RootBeanDefinition eligibilityRegistry = new RootBeanDefinition(RepositoryEligibilityRegistry.class);
		eligibilityRegistry.getConstructorArgumentValues().addIndexedArgumentValue(0,
				new RuntimeBeanReference(DIAGNOSTICS_BEAN_NAME));
		eligibilityRegistry.getConstructorArgumentValues().addIndexedArgumentValue(1,
				new RuntimeBeanReference(REGISTRY_BEAN_NAME));
		registerOwned(registry, ELIGIBILITY_REGISTRY_BEAN_NAME, eligibilityRegistry);

		RootBeanDefinition collector = new RootBeanDefinition(RepositoryMetadataCollector.class);
		collector.getConstructorArgumentValues().addIndexedArgumentValue(0, applicationContext.getBeanFactory());
		collector.getConstructorArgumentValues().addIndexedArgumentValue(1,
				new RuntimeBeanReference(REGISTRY_BEAN_NAME));
		collector.getConstructorArgumentValues().addIndexedArgumentValue(2,
				new RuntimeBeanReference(DIAGNOSTICS_BEAN_NAME));
		collector.getConstructorArgumentValues().addIndexedArgumentValue(3, applicationContext.getId());
		registerOwned(registry, COLLECTOR_BEAN_NAME, collector);

		RootBeanDefinition eligibilityProcessor = new RootBeanDefinition(RepositoryEligibilityProcessor.class);
		eligibilityProcessor.getConstructorArgumentValues().addIndexedArgumentValue(0,
				applicationContext.getBeanFactory());
		eligibilityProcessor.getConstructorArgumentValues().addIndexedArgumentValue(1,
				new RuntimeBeanReference(REGISTRY_BEAN_NAME));
		eligibilityProcessor.getConstructorArgumentValues().addIndexedArgumentValue(2,
				new RuntimeBeanReference(ELIGIBILITY_REGISTRY_BEAN_NAME));
		eligibilityProcessor.getConstructorArgumentValues().addIndexedArgumentValue(3,
				new RuntimeBeanReference(DIAGNOSTICS_BEAN_NAME));
		eligibilityProcessor.getConstructorArgumentValues().addIndexedArgumentValue(4, applicationContext.getId());
		registerOwned(registry, ELIGIBILITY_PROCESSOR_BEAN_NAME, eligibilityProcessor);

		RootBeanDefinition installationRegistry = new RootBeanDefinition(RepositoryAdvisorInstallationRegistry.class);
		installationRegistry.getConstructorArgumentValues().addIndexedArgumentValue(0,
				applicationContext.getBeanFactory());
		installationRegistry.getConstructorArgumentValues().addIndexedArgumentValue(1,
				new RuntimeBeanReference(ELIGIBILITY_REGISTRY_BEAN_NAME));
		installationRegistry.getConstructorArgumentValues().addIndexedArgumentValue(2,
				new RuntimeBeanReference(REGISTRY_BEAN_NAME));
		installationRegistry.getConstructorArgumentValues().addIndexedArgumentValue(3,
				new RuntimeBeanReference(DIAGNOSTICS_BEAN_NAME));
		installationRegistry.getConstructorArgumentValues().addIndexedArgumentValue(4,
				new RuntimeBeanReference(METRICS_BEAN_NAME));
		registerOwned(registry, INSTALLATION_REGISTRY_BEAN_NAME, installationRegistry);

	}

	private static void registerStatus(ConfigurableApplicationContext applicationContext,
			AdapterActivationStatus.State state) {
		RootBeanDefinition definition = new RootBeanDefinition(AdapterActivationStatus.class,
				() -> new AdapterActivationStatus(state));
		registerOwned(registry(applicationContext), STATUS_BEAN_NAME, definition);
	}

	private static BeanDefinitionRegistry registry(ConfigurableApplicationContext applicationContext) {
		if (applicationContext.getBeanFactory() instanceof BeanDefinitionRegistry registry) return registry;
		throw new IllegalStateException("application bean factory is not a BeanDefinitionRegistry");
	}

	private static void registerOwned(BeanDefinitionRegistry registry, String beanName, RootBeanDefinition definition) {
		if (registry.containsBeanDefinition(beanName)) {
			BeanDefinition existing = registry.getBeanDefinition(beanName);
			if (OWNER.equals(existing.getAttribute(OWNER_ATTRIBUTE))) return;
			throw new IllegalStateException("reserved STP bean name collision: " + beanName);
		}
		definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
		definition.setAttribute(OWNER_ATTRIBUTE, OWNER);
		registry.registerBeanDefinition(beanName, definition);
	}
}
