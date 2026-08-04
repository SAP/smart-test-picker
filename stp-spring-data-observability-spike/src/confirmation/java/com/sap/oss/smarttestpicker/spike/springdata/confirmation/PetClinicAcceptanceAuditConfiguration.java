// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;
import org.springframework.data.repository.Repository;

@Configuration(proxyBeanMethods = false)
class PetClinicAcceptanceAuditConfiguration {
	@Bean
	AcceptanceAuditRecorder acceptanceAuditRecorder(Environment environment,
			ConfigurableListableBeanFactory beanFactory,
			@Qualifier("vetRepository") Repository<?, ?> injectedVetRepository) {
		return new AcceptanceAuditRecorder(environment, beanFactory, injectedVetRepository);
	}

	@Bean
	ApplicationListener<ContextRefreshedEvent> acceptanceAuditListener(AcceptanceAuditRecorder recorder) {
		return event -> recorder.capture();
	}
}
