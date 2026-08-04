// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

public final class PetClinicConfirmationInitializer
		implements ApplicationContextInitializer<ConfigurableApplicationContext> {
	@Override
	public void initialize(ConfigurableApplicationContext applicationContext) {
		String mode = applicationContext.getEnvironment().getProperty("stp.confirmation.mode");
		BeanDefinitionRegistry registry = (BeanDefinitionRegistry) applicationContext.getBeanFactory();
		if ("acceptance".equals(mode)) {
			new AnnotatedBeanDefinitionReader(registry).register(PetClinicConfirmationConfiguration.class);
			if ("acceptance".equals(mode)) {
				new AnnotatedBeanDefinitionReader(registry).register(PetClinicAcceptanceAuditConfiguration.class);
			}
		}
		else if (mode != null && mode.startsWith("lifecycle-")) {
			new AnnotatedBeanDefinitionReader(registry).register(RepositoryLifecycleConfiguration.class);
		}
	}
}
