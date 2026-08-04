// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

final class RepositoryEligibilityProcessor implements BeanPostProcessor, Ordered {
	private static final String STP_PACKAGE = "com.sap.oss.smarttestpicker.springdata.";
	private final DefaultListableBeanFactory beanFactory;
	private final RepositoryMetadataRegistry metadataRegistry;
	private final RepositoryEligibilityRegistry eligibilityRegistry;
	private final MetadataDiagnostics diagnostics;
	private final String contextIdentity;

	RepositoryEligibilityProcessor(DefaultListableBeanFactory beanFactory, RepositoryMetadataRegistry metadataRegistry,
			RepositoryEligibilityRegistry eligibilityRegistry, MetadataDiagnostics diagnostics, String contextIdentity) {
		this.beanFactory = beanFactory;
		this.metadataRegistry = metadataRegistry;
		this.eligibilityRegistry = eligibilityRegistry;
		this.diagnostics = diagnostics;
		this.contextIdentity = contextIdentity;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (bean instanceof RepositoryFactoryBeanSupport<?, ?, ?>) return bean;
		String canonicalName = canonicalName(beanName);
		Optional<RepositoryMetadata> metadata = metadataRegistry.find(canonicalName);
		if (isInfrastructure(bean, canonicalName)) {
			if (metadata.isPresent() || bean instanceof Repository<?, ?>) {
				diagnostics.record(RepositoryEligibilityReason.STP_INFRASTRUCTURE_BEAN, canonicalName);
			}
			return bean;
		}
		if (metadata.isEmpty() && !(bean instanceof Repository<?, ?>)) return bean;
		RepositoryEligibility result = classify(bean, canonicalName, metadata.orElse(null));
		eligibilityRegistry.register(bean, result);
		if (!result.eligible()) diagnostics.record(result.reason(), canonicalName);
		return bean;
	}

	RepositoryEligibility classify(Object bean, String canonicalName, RepositoryMetadata metadata) {
		if (metadata == null) return unsupported(canonicalName, "", "",
				RepositoryEligibilityReason.MISSING_FACTORY_METADATA, bean);
		if (!canonicalName.equals(metadata.canonicalBeanName())) {
			return unsupported(canonicalName, metadata.repositoryInterface(), metadata.domainType(),
					RepositoryEligibilityReason.AMBIGUOUS_REPOSITORY_METADATA, bean);
		}
		if (!contextIdentity.equals(metadata.contextIdentity())) {
			return unsupported(canonicalName, metadata.repositoryInterface(), metadata.domainType(),
					RepositoryEligibilityReason.CONTEXT_MISMATCH, bean);
		}
		if (metadataRegistry.isAmbiguous(canonicalName)) {
			return unsupported(canonicalName, metadata.repositoryInterface(), metadata.domainType(),
					RepositoryEligibilityReason.AMBIGUOUS_REPOSITORY_METADATA, bean);
		}
		if (!(bean instanceof Advised advised)) {
			return unsupported(canonicalName, metadata.repositoryInterface(), metadata.domainType(),
					RepositoryEligibilityReason.NON_ADVISED_REPOSITORY, bean);
		}
		ProxySnapshot snapshot = snapshot(bean, advised);
		if (!snapshot.exposedInterfaces().contains(metadata.repositoryInterface())) {
			return result(canonicalName, metadata, false,
					RepositoryEligibilityReason.REPOSITORY_INTERFACE_NOT_EXPOSED, snapshot);
		}
		if (snapshot.hasStpMarker()) {
			return result(canonicalName, metadata, false, RepositoryEligibilityReason.EXISTING_STP_ADVISOR, snapshot);
		}
		if (!AopUtils.isAopProxy(bean) || snapshot.proxyKind() != RepositoryProxyKind.JDK
				|| !snapshot.targetSourcePresent()) {
			return result(canonicalName, metadata, false, RepositoryEligibilityReason.UNSUPPORTED_PROXY_TYPE, snapshot);
		}
		return result(canonicalName, metadata, true, RepositoryEligibilityReason.ELIGIBLE, snapshot);
	}

	private RepositoryEligibility unsupported(String canonicalName, String repositoryInterface, String domainType,
			RepositoryEligibilityReason reason, Object bean) {
		ProxySnapshot snapshot = bean instanceof Advised advised ? snapshot(bean, advised)
				: new ProxySnapshot(RepositoryProxyKind.NOT_AOP_PROXY, List.of(), 0, List.of(), false, false);
		return new RepositoryEligibility(canonicalName, repositoryInterface, domainType, false, reason,
				snapshot.proxyKind(), snapshot.exposedInterfaces(), snapshot.advisorCount(), snapshot.advisorTypes(),
				snapshot.targetSourcePresent(), contextIdentity);
	}

	private RepositoryEligibility result(String canonicalName, RepositoryMetadata metadata, boolean eligible,
			RepositoryEligibilityReason reason, ProxySnapshot snapshot) {
		return new RepositoryEligibility(canonicalName, metadata.repositoryInterface(), metadata.domainType(), eligible,
				reason, snapshot.proxyKind(), snapshot.exposedInterfaces(), snapshot.advisorCount(),
				snapshot.advisorTypes(), snapshot.targetSourcePresent(), contextIdentity);
	}

	private static ProxySnapshot snapshot(Object bean, Advised advised) {
		RepositoryProxyKind kind = AopUtils.isJdkDynamicProxy(bean) ? RepositoryProxyKind.JDK
				: AopUtils.isCglibProxy(bean) ? RepositoryProxyKind.CLASS_BASED : RepositoryProxyKind.NOT_AOP_PROXY;
		List<String> interfaces = Arrays.stream(advised.getProxiedInterfaces()).map(Class::getName).sorted().toList();
		Advisor[] advisors = advised.getAdvisors();
		List<String> advisorTypes = Arrays.stream(advisors).map(value -> value.getClass().getName()).sorted().toList();
		boolean marker = Arrays.stream(advisors).anyMatch(value -> value instanceof StpCallerAdvisorMarker
				|| value.getAdvice() instanceof StpCallerAdvisorMarker);
		return new ProxySnapshot(kind, interfaces, advisors.length, advisorTypes, advised.getTargetSource() != null,
				marker);
	}

	private String canonicalName(String beanName) {
		return beanFactory.canonicalName(BeanFactoryUtils.transformedBeanName(beanName));
	}

	private static boolean isInfrastructure(Object bean, String canonicalName) {
		return canonicalName.startsWith(STP_PACKAGE) || bean.getClass().getName().startsWith(STP_PACKAGE);
	}

	private record ProxySnapshot(RepositoryProxyKind proxyKind, List<String> exposedInterfaces, int advisorCount,
			List<String> advisorTypes, boolean targetSourcePresent, boolean hasStpMarker) {
	}
}
