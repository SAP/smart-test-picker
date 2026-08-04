// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class RepositoryAdvisorInstallationRegistry implements SmartInitializingSingleton, DisposableBean {
	private final DefaultListableBeanFactory beanFactory;
	private final RepositoryEligibilityRegistry eligibilityRegistry;
	private final RepositoryMetadataRegistry metadataRegistry;
	private final MetadataDiagnostics diagnostics;
	private final RepositoryAdapterMetrics metrics;
	private final Map<String, Installation> installations = new TreeMap<>();
	private final IdentityHashMap<Object, String> processedBeans = new IdentityHashMap<>();
	private final Map<String, AdvisorInstallationResult> results = new TreeMap<>();
	private boolean lifecycleCompleted;

	RepositoryAdvisorInstallationRegistry(DefaultListableBeanFactory beanFactory,
			RepositoryEligibilityRegistry eligibilityRegistry, RepositoryMetadataRegistry metadataRegistry,
			MetadataDiagnostics diagnostics,
			RepositoryAdapterMetrics metrics) {
		this.beanFactory = beanFactory;
		this.eligibilityRegistry = eligibilityRegistry;
		this.metadataRegistry = metadataRegistry;
		this.diagnostics = diagnostics;
		this.metrics = metrics;
	}

	synchronized Installation insert(Object bean, RepositoryEligibility eligibility) {
		if (!eligibility.eligible() || processedBeans.containsKey(bean)
				|| installations.containsKey(eligibility.canonicalBeanName())) return null;
		Advised advised = (Advised) bean;
		ProxyStructuralFingerprint before = ProxyStructuralFingerprint.capture(bean, advised);
		StpCallerAdvisor advisor = new StpCallerAdvisor(eligibility, metrics);
		advised.addAdvisor(0, advisor);
		Installation installation = new Installation(eligibility, advised, advisor, before);
		installations.put(eligibility.canonicalBeanName(), installation);
		processedBeans.put(bean, eligibility.canonicalBeanName());
		results.put(eligibility.canonicalBeanName(), inserted(installation));
		return installation;
	}

	@Override
	public void afterSingletonsInstantiated() {
		List<RepositoryEligibilityRegistry.EligibleBean> candidates;
		synchronized (this) {
			if (lifecycleCompleted) return;
			lifecycleCompleted = true;
		}
		for (RepositoryMetadata metadata : metadataRegistry.entries()) {
			if (!metadataRegistry.isAmbiguous(metadata.canonicalBeanName())) {
				beanFactory.getBean(metadata.canonicalBeanName());
			}
		}
		candidates = eligibilityRegistry.closeInsertionPhaseAndEligibleBeans();
		for (RepositoryEligibilityRegistry.EligibleBean candidate : candidates) {
			RepositoryEligibility eligibility = candidate.eligibility();
			Object finalBean = beanFactory.getBean(eligibility.canonicalBeanName());
			if (finalBean != candidate.bean()) {
				diagnostics.record(AdvisorAuditReason.BEAN_IDENTITY_CHANGED, eligibility.canonicalBeanName());
				eligibilityRegistry.reject(eligibility.canonicalBeanName());
				continue;
			}
			Installation installation = insert(finalBean, eligibility);
			if (installation != null) audit(installation, finalBean);
		}
	}

	void audit(Installation installation) {
		Object lookedUp = beanFactory.getBean(installation.eligibility().canonicalBeanName());
		audit(installation, lookedUp);
	}

	void audit(Installation installation, Object lookedUp) {
		Audit audit = inspect(installation, lookedUp);
		if (audit.passed()) {
			putResult(audited(installation, audit));
			installation.advisor().callerAdvice().enableAfterAudit();
			return;
		}
		diagnostics.record(audit.reason(), installation.eligibility().canonicalBeanName());
		boolean restored;
		try {
			installation.advised().removeAdvisor(installation.advisor());
			restored = restored(installation);
		}
		catch (RuntimeException restorationFailure) {
			restored = false;
		}
		eligibilityRegistry.reject(installation.eligibility().canonicalBeanName());
		if (restored) {
			putResult(failed(installation, audit, AdvisorInstallationState.AUDIT_FAILED_RESTORED, audit.reason()));
			return;
		}
		putResult(failed(installation, audit, AdvisorInstallationState.AUDIT_FAILED_RESTORE_FAILED,
				AdvisorAuditReason.RESTORATION_FAILED));
		diagnostics.record(AdvisorAuditReason.RESTORATION_FAILED,
				installation.eligibility().canonicalBeanName());
		throw new IllegalStateException("STP Spring Data proxy restoration failed for "
				+ installation.eligibility().canonicalBeanName() + ": " + audit.reason());
	}

	private static Audit inspect(Installation installation, Object lookedUp) {
		ProxyStructuralFingerprint before = installation.before();
		if (lookedUp != before.bean()) return Audit.failure(AdvisorAuditReason.BEAN_IDENTITY_CHANGED);
		Advisor[] current = installation.advised().getAdvisors();
		long ownedCount = Arrays.stream(current).filter(value -> value == installation.advisor()).count();
		long markerCount = Arrays.stream(current).filter(value -> value instanceof StpCallerAdvisorMarker
				|| value.getAdvice() instanceof StpCallerAdvisorMarker).count();
		if (ownedCount != 1 || markerCount != 1) return Audit.failure(AdvisorAuditReason.STP_ADVISOR_COUNT);
		if (current.length == 0 || current[0] != installation.advisor()) {
			return Audit.failure(AdvisorAuditReason.STP_ADVISOR_POSITION);
		}
		List<Advisor> original = before.advisors();
		if (current.length != original.size() + 1) return Audit.failure(AdvisorAuditReason.EXISTING_ADVISORS_CHANGED);
		for (int index = 0; index < original.size(); index++) {
			if (current[index + 1] != original.get(index)) {
				return Audit.failure(AdvisorAuditReason.EXISTING_ADVISORS_CHANGED);
			}
		}
		List<Integer> afterCache = ProxyStructuralFingerprint.cachePositions(List.of(current));
		if (afterCache.stream().anyMatch(position -> position == 0)
				|| !afterCache.equals(before.cachePositions().stream().map(position -> position + 1).toList())) {
			return Audit.failure(AdvisorAuditReason.CACHE_ORDER_CHANGED);
		}
		ProxyStructuralFingerprint after = ProxyStructuralFingerprint.capture(lookedUp, installation.advised());
		if (after.proxyKind() != before.proxyKind()
				|| !after.exposedInterfaces().equals(before.exposedInterfaces())
				|| after.targetSource() != before.targetSource()
				|| after.proxyLayerCount() != before.proxyLayerCount()) {
			return Audit.failure(AdvisorAuditReason.PROXY_STRUCTURE_CHANGED);
		}
		return new Audit(true, AdvisorAuditReason.NONE, afterCache);
	}

	private static boolean restored(Installation installation) {
		ProxyStructuralFingerprint before = installation.before();
		Advised advised = installation.advised();
		ProxyStructuralFingerprint restored = ProxyStructuralFingerprint.capture(before.bean(), advised);
		Advisor[] current = advised.getAdvisors();
		if (current.length != before.advisors().size()) return false;
		for (int index = 0; index < current.length; index++) {
			if (current[index] != before.advisors().get(index)) return false;
		}
		return restored.proxyKind() == before.proxyKind()
				&& restored.exposedInterfaces().equals(before.exposedInterfaces())
				&& restored.targetSource() == before.targetSource()
				&& restored.proxyLayerCount() == before.proxyLayerCount()
				&& restored.cachePositions().equals(before.cachePositions());
	}

	private static AdvisorInstallationResult inserted(Installation value) {
		return result(value, AdvisorInstallationState.INSERTED_DISABLED, AdvisorAuditReason.NONE, false,
				false, false, false, false, false, List.of());
	}

	private static AdvisorInstallationResult audited(Installation value, Audit audit) {
		return result(value, AdvisorInstallationState.AUDIT_PASSED, AdvisorAuditReason.NONE, true,
				true, true, true, true, true, audit.cachePositionsAfter());
	}

	private static AdvisorInstallationResult failed(Installation value, Audit audit, AdvisorInstallationState state,
			AdvisorAuditReason reason) {
		return result(value, state, reason, audit.reason() != AdvisorAuditReason.BEAN_IDENTITY_CHANGED,
				audit.reason() != AdvisorAuditReason.PROXY_STRUCTURE_CHANGED,
				audit.reason() != AdvisorAuditReason.PROXY_STRUCTURE_CHANGED,
				audit.reason() != AdvisorAuditReason.PROXY_STRUCTURE_CHANGED,
				audit.reason() != AdvisorAuditReason.EXISTING_ADVISORS_CHANGED,
				audit.reason() != AdvisorAuditReason.PROXY_STRUCTURE_CHANGED, audit.cachePositionsAfter());
	}

	private static AdvisorInstallationResult result(Installation value, AdvisorInstallationState state,
			AdvisorAuditReason reason, boolean beanMatch, boolean proxyMatch, boolean interfacesMatch,
			boolean targetMatch, boolean advisorMatch, boolean depthMatch, List<Integer> cacheAfter) {
		RepositoryEligibility eligibility = value.eligibility();
		return new AdvisorInstallationResult(eligibility.canonicalBeanName(), eligibility.repositoryInterface(),
				eligibility.domainType(), state, reason, 0, value.before().cachePositions(), cacheAfter,
				beanMatch, proxyMatch, interfacesMatch, targetMatch, advisorMatch, depthMatch);
	}

	private synchronized void putResult(AdvisorInstallationResult result) {
		results.put(result.canonicalBeanName(), result);
	}

	synchronized List<AdvisorInstallationResult> results() {
		return List.copyOf(new ArrayList<>(results.values()));
	}

	synchronized Installation installation(String canonicalBeanName) {
		return installations.get(canonicalBeanName);
	}

	@Override
	public synchronized void destroy() {
		installations.clear();
		processedBeans.clear();
		results.clear();
		lifecycleCompleted = false;
	}

	record Installation(RepositoryEligibility eligibility, Advised advised, StpCallerAdvisor advisor,
			ProxyStructuralFingerprint before) {
	}

	private record Audit(boolean passed, AdvisorAuditReason reason, List<Integer> cachePositionsAfter) {
		private static Audit failure(AdvisorAuditReason reason) {
			return new Audit(false, reason, List.of());
		}
	}
}
