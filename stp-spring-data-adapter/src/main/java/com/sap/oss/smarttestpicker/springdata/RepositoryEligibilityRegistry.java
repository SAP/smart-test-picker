// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.springframework.beans.factory.DisposableBean;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

final class RepositoryEligibilityRegistry implements DisposableBean {
	private final MetadataDiagnostics diagnostics;
	private final RepositoryMetadataRegistry metadataRegistry;
	private final Map<String, RepositoryEligibility> byCanonicalName = new TreeMap<>();
	private final IdentityHashMap<Object, String> processedIdentities = new IdentityHashMap<>();
	private boolean insertionPhaseClosed;

	RepositoryEligibilityRegistry(MetadataDiagnostics diagnostics, RepositoryMetadataRegistry metadataRegistry) {
		this.diagnostics = diagnostics;
		this.metadataRegistry = metadataRegistry;
	}

	synchronized RepositoryEligibility register(Object bean, RepositoryEligibility result) {
		String processedName = processedIdentities.get(bean);
		if (processedName != null) {
			diagnostics.record(RepositoryEligibilityReason.DUPLICATE_BEAN_ENCOUNTER, result.canonicalBeanName());
			return byCanonicalName.getOrDefault(processedName, result);
		}
		RepositoryEligibility existing = byCanonicalName.get(result.canonicalBeanName());
		if (existing != null) {
			diagnostics.record(RepositoryEligibilityReason.DUPLICATE_BEAN_ENCOUNTER, result.canonicalBeanName());
			RepositoryEligibility rejected = new RepositoryEligibility(existing.canonicalBeanName(),
					existing.repositoryInterface(), existing.domainType(), false,
					RepositoryEligibilityReason.DUPLICATE_BEAN_ENCOUNTER, existing.proxyKind(),
					existing.exposedInterfaces(), existing.advisorCount(), existing.advisorTypes(),
					existing.targetSourcePresent(), existing.contextIdentity());
			processedIdentities.put(bean, result.canonicalBeanName());
			byCanonicalName.put(result.canonicalBeanName(), rejected);
			return rejected;
		}
		if (insertionPhaseClosed && result.eligible()) {
			diagnostics.record(RepositoryEligibilityReason.REPOSITORY_CREATED_AFTER_INSERTION_PHASE,
					result.canonicalBeanName());
			RepositoryEligibility rejected = new RepositoryEligibility(result.canonicalBeanName(),
					result.repositoryInterface(), result.domainType(), false,
					RepositoryEligibilityReason.REPOSITORY_CREATED_AFTER_INSERTION_PHASE, result.proxyKind(),
					result.exposedInterfaces(), result.advisorCount(), result.advisorTypes(),
					result.targetSourcePresent(), result.contextIdentity());
			processedIdentities.put(bean, result.canonicalBeanName());
			byCanonicalName.put(result.canonicalBeanName(), rejected);
			return rejected;
		}
		processedIdentities.put(bean, result.canonicalBeanName());
		byCanonicalName.put(result.canonicalBeanName(), result);
		return result;
	}

	synchronized List<EligibleBean> closeInsertionPhaseAndEligibleBeans() {
		if (insertionPhaseClosed) return List.of();
		insertionPhaseClosed = true;
		List<EligibleBean> result = new ArrayList<>();
		for (Map.Entry<Object, String> entry : processedIdentities.entrySet()) {
			RepositoryEligibility eligibility = byCanonicalName.get(entry.getValue());
			if (eligibility != null && eligibility.eligible()) result.add(new EligibleBean(entry.getKey(), eligibility));
		}
		result.sort(java.util.Comparator.comparing(value -> value.eligibility().canonicalBeanName()));
		return List.copyOf(result);
	}

	synchronized Optional<RepositoryEligibility> find(String beanNameOrAlias) {
		String canonicalName = metadataRegistry.find(beanNameOrAlias)
				.map(RepositoryMetadata::canonicalBeanName).orElse(beanNameOrAlias);
		return Optional.ofNullable(byCanonicalName.get(canonicalName));
	}

	synchronized List<RepositoryEligibility> entries() {
		return List.copyOf(new ArrayList<>(byCanonicalName.values()));
	}

	synchronized void reject(String canonicalBeanName) {
		RepositoryEligibility existing = byCanonicalName.get(canonicalBeanName);
		if (existing == null || !existing.eligible()) return;
		byCanonicalName.put(canonicalBeanName, new RepositoryEligibility(existing.canonicalBeanName(),
				existing.repositoryInterface(), existing.domainType(), false,
				RepositoryEligibilityReason.UNSUPPORTED_PROXY_TYPE, existing.proxyKind(), existing.exposedInterfaces(),
				existing.advisorCount(), existing.advisorTypes(), existing.targetSourcePresent(),
				existing.contextIdentity()));
	}

	@Override
	public synchronized void destroy() {
		processedIdentities.clear();
		byCanonicalName.clear();
		insertionPhaseClosed = false;
	}

	record EligibleBean(Object bean, RepositoryEligibility eligibility) {
	}
}
