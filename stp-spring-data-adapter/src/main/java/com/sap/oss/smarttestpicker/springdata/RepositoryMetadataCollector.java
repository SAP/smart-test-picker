// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

final class RepositoryMetadataCollector implements BeanPostProcessor, Ordered {
	private final DefaultListableBeanFactory beanFactory;
	private final RepositoryMetadataRegistry registry;
	private final MetadataDiagnostics diagnostics;
	private final String contextIdentity;

	RepositoryMetadataCollector(DefaultListableBeanFactory beanFactory, RepositoryMetadataRegistry registry,
			MetadataDiagnostics diagnostics, String contextIdentity) {
		this.beanFactory = beanFactory;
		this.registry = registry;
		this.diagnostics = diagnostics;
		this.contextIdentity = contextIdentity;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		if (!(bean instanceof RepositoryFactoryBeanSupport<?, ?, ?> factory)) return bean;
		String canonicalName = canonicalName(beanName);
		List<String> aliases = aliases(canonicalName);
		try {
			factory.addRepositoryFactoryCustomizer(repositoryFactory -> {
				try {
					repositoryFactory.addRepositoryProxyPostProcessor((proxyFactory, information) ->
							capture(canonicalName, aliases, information));
				}
				catch (RuntimeException failure) {
					diagnostics.record(MetadataDiagnosticReason.FACTORY_CUSTOMIZER_FAILURE, canonicalName);
				}
			});
		}
		catch (RuntimeException failure) {
			diagnostics.record(MetadataDiagnosticReason.FACTORY_CUSTOMIZER_FAILURE, canonicalName);
		}
		return bean;
	}

	private void capture(String canonicalName, List<String> aliases, RepositoryInformation information) {
		try {
			Class<?> repositoryInterface = information.getRepositoryInterface();
			if (repositoryInterface == null || !repositoryInterface.isInterface()
					|| !Modifier.isPublic(repositoryInterface.getModifiers())
					|| !Repository.class.isAssignableFrom(repositoryInterface)) {
				diagnostics.record(MetadataDiagnosticReason.INVALID_REPOSITORY_INTERFACE, canonicalName);
				return;
			}
			Class<?> domainType = information.getDomainType();
			if (domainType == null) {
				diagnostics.record(MetadataDiagnosticReason.MISSING_DOMAIN_TYPE, canonicalName);
				return;
			}
			registry.register(new RepositoryMetadata(canonicalName, repositoryInterface.getName(), domainType.getName(),
					aliases, RepositoryMetadataProvenance.REPOSITORY_INFORMATION_VIA_PROXY_POST_PROCESSOR,
					contextIdentity));
		}
		catch (RuntimeException failure) {
			diagnostics.record(MetadataDiagnosticReason.FACTORY_CUSTOMIZER_FAILURE, canonicalName);
		}
	}

	private String canonicalName(String beanName) {
		return beanFactory.canonicalName(BeanFactoryUtils.transformedBeanName(beanName));
	}

	private List<String> aliases(String canonicalName) {
		TreeSet<String> aliases = new TreeSet<>(Arrays.asList(beanFactory.getAliases(canonicalName)));
		aliases.remove(canonicalName);
		return List.copyOf(aliases);
	}
}
