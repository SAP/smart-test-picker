// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class RepositoryLifecycleConfiguration {
	@Bean
	RepositoryLifecycleRecorder repositoryLifecycleRecorder(Environment environment,
			ConfigurableListableBeanFactory beanFactory, ConfigurableApplicationContext applicationContext) {
		return new RepositoryLifecycleRecorder(environment, beanFactory, applicationContext.getId());
	}

	@Bean
	static BeanPostProcessor repositoryLifecycleFactoryObserver(RepositoryLifecycleRecorder recorder) {
		return recorder.factoryObserver();
	}

	@Bean
	static BeanPostProcessor repositoryLifecycleProductObserver(RepositoryLifecycleRecorder recorder) {
		return recorder.productObserver();
	}

	@Bean
	static BeanPostProcessor repositoryLifecycleEarlyProductObserver(RepositoryLifecycleRecorder recorder) {
		return recorder.earlyProductObserver();
	}

	@Bean
	SmartInitializingSingleton repositoryLifecycleSmartSingleton(RepositoryLifecycleRecorder recorder) {
		return () -> recorder.lifecyclePoint("SMART_INITIALIZING_SINGLETON");
	}

	@Bean
	ApplicationListener<ContextRefreshedEvent> repositoryLifecycleRefreshed(RepositoryLifecycleRecorder recorder) {
		return event -> recorder.lifecyclePoint("CONTEXT_REFRESHED_EVENT");
	}

	@Bean
	ApplicationListener<ContextClosedEvent> repositoryLifecycleClosed(RepositoryLifecycleRecorder recorder) {
		return event -> recorder.captureAll("CONTEXT_SHUTDOWN");
	}
}
