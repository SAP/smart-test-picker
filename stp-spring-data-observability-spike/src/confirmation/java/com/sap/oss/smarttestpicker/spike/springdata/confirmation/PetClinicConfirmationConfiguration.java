// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

import org.springframework.aop.framework.Advised;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryMethodInvocationListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration(proxyBeanMethods = false)
class PetClinicConfirmationConfiguration {
	@Bean
	ConfirmationRecorder petClinicConfirmationRecorder(Environment environment) {
		return new ConfirmationRecorder(environment);
	}

	@Bean
	static BeanPostProcessor petClinicRepositoryDiagnostics(ConfirmationRecorder recorder,
			DefaultListableBeanFactory beanFactory) {
		Map<Class<?>, RepositoryInformation> metadata = new ConcurrentHashMap<>();
		return new BeanPostProcessor() {
			@Override
			public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
				if (!(bean instanceof RepositoryFactoryBeanSupport<?, ?, ?> factory)) return bean;
				String canonicalBeanName = beanFactory.canonicalName(beanName.startsWith("&") ? beanName.substring(1) : beanName);
				factory.addRepositoryFactoryCustomizer(repositoryFactory -> {
					repositoryFactory.addRepositoryProxyPostProcessor((proxyFactory, information) -> {
						metadata.put(information.getRepositoryInterface(), information);
					});
					repositoryFactory.addInvocationListener(invocation ->
							recordListener(recorder, canonicalBeanName, metadata, invocation));
				});
				return bean;
			}

			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
				if (!(bean instanceof Repository) || !(bean instanceof Advised advised)) return bean;
				Class<?> repositoryInterface = metadata.keySet().stream().filter(type -> type.isInstance(bean)).findFirst().orElse(null);
				if (repositoryInterface == null) return bean;
				RepositoryInformation information = metadata.get(repositoryInterface);
				for (int index = 0; index < advised.getAdvisors().length; index++) {
					var advisor = advised.getAdvisors()[index];
					recorder.add("advisor-order", "REGISTRATION", repositoryInterface, null, "<advisor-" + index + ">",
							"()V", information.getDomainType(), "REGISTERED", null, transactionActive(),
							beanFactory.canonicalName(beanName), advisor.getAdvice().getClass().getName());
				}
				return bean;
			}
		};
	}

	private static void recordListener(ConfirmationRecorder recorder, String beanName,
			Map<Class<?>, RepositoryInformation> metadata,
			RepositoryMethodInvocationListener.RepositoryMethodInvocation invocation) {
		RepositoryInformation information = metadata.get(invocation.getRepositoryInterface());
		var result = invocation.getResult();
		Throwable error = result == null ? null : result.getError();
		recorder.add("RepositoryMethodInvocationListener", "AFTER", invocation.getRepositoryInterface(),
				invocation.getMethod().getDeclaringClass(), invocation.getMethod().getName(),
				Descriptors.of(invocation.getMethod()), information == null ? null : information.getDomainType(),
				result == null ? null : result.getState().name(), error, transactionActive(), beanName,
				"RepositoryFactorySupport.addInvocationListener");
	}

	private static boolean transactionActive() {
		return TransactionSynchronizationManager.isActualTransactionActive();
	}
}
