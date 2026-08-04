// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeEventAggregator;
import com.sap.oss.smarttestpicker.runtime.RuntimeJsonSerializer;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.dao.annotation.PersistenceExceptionTranslationAdvisor;
import org.springframework.dao.support.PersistenceExceptionTranslator;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sap.oss.smarttestpicker.springdata.AdvisorAuditReason.BEAN_IDENTITY_CHANGED;
import static com.sap.oss.smarttestpicker.springdata.AdvisorAuditReason.RESTORATION_FAILED;
import static com.sap.oss.smarttestpicker.springdata.AdvisorAuditReason.STP_ADVISOR_POSITION;
import static com.sap.oss.smarttestpicker.springdata.AdvisorInstallationState.AUDIT_FAILED_RESTORED;
import static com.sap.oss.smarttestpicker.springdata.AdvisorInstallationState.AUDIT_PASSED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryAdvisorInstallationTest {
	@Test
	void insertsOnlyAtSmartSingletonPhaseAndAuditPreservesCompleteStructure() {
		Fixture fixture = new Fixture("repository");
		Advisor first = passThroughAdvisor();
		Advisor cache = new DefaultPointcutAdvisor(new CacheInterceptor());
		Object bean = repositoryProxy(first, cache);
		Advised advised = (Advised) bean;
		Object targetSource = advised.getTargetSource();
		Class<?>[] interfaces = advised.getProxiedInterfaces();

		fixture.register(bean);
		assertArrayEquals(new Advisor[] { first, cache }, advised.getAdvisors());
		fixture.installations.afterSingletonsInstantiated();
		Advisor[] inserted = advised.getAdvisors();
		assertEquals(3, inserted.length);
		assertTrue(inserted[0] instanceof StpCallerAdvisor);
		assertSame(first, inserted[1]);
		assertSame(cache, inserted[2]);

		AdvisorInstallationResult result = fixture.installations.results().get(0);
		assertEquals(AUDIT_PASSED, result.state());
		assertEquals(0, result.advisorIndex());
		assertEquals(List.of(1), result.cacheAdvisorPositionsBefore());
		assertEquals(List.of(2), result.cacheAdvisorPositionsAfter());
		assertTrue(result.beanIdentityMatches());
		assertTrue(result.proxyKindMatches());
		assertTrue(result.interfacesMatch());
		assertTrue(result.targetSourceMatches());
		assertTrue(result.advisorOrderMatches());
		assertTrue(result.proxyLayerCountMatches());
		assertSame(bean, fixture.beanFactory.getBean("repository"));
		assertSame(targetSource, advised.getTargetSource());
		assertArrayEquals(interfaces, advised.getProxiedInterfaces());
	}

	@Test
	void finalPersistenceExceptionAdvisorIsFingerprintInputAndRemainsBehindStp() {
		Fixture fixture = new Fixture("repository");
		Advisor original = passThroughAdvisor();
		PersistenceExceptionTranslationAdvisor translation = new PersistenceExceptionTranslationAdvisor(
				(PersistenceExceptionTranslator) exception -> null, org.springframework.stereotype.Repository.class);
		Object bean = repositoryProxy(original, translation);
		Advised advised = (Advised) bean;
		fixture.register(bean);

		fixture.installations.afterSingletonsInstantiated();

		assertArrayEquals(new Advisor[] { fixture.installations.installation("repository").advisor(), original,
				translation }, advised.getAdvisors());
		assertEquals(AUDIT_PASSED, fixture.installations.results().get(0).state());
		assertTrue(fixture.installations.results().get(0).advisorOrderMatches());
	}

	@Test
	void repeatedLifecycleCallbackDoesNotInstallTwice() {
		Fixture fixture = new Fixture("repository");
		Object bean = repositoryProxy();
		fixture.register(bean);
		fixture.installations.afterSingletonsInstantiated();
		Advisor advisor = ((Advised) bean).getAdvisors()[0];
		fixture.installations.afterSingletonsInstantiated();
		assertArrayEquals(new Advisor[] { advisor }, ((Advised) bean).getAdvisors());
		assertEquals(1, fixture.installations.results().size());
	}

	@Test
	void repositoryCreatedAfterInsertionPhaseIsRejectedWithoutMutation() {
		Fixture fixture = new Fixture("lateRepository", false);
		fixture.installations.afterSingletonsInstantiated();
		fixture.registerMetadata();
		Object bean = repositoryProxy();
		fixture.register(bean);
		RepositoryEligibility result = fixture.eligibilityRegistry.find("lateRepository").orElseThrow();
		assertFalse(result.eligible());
		assertEquals(RepositoryEligibilityReason.REPOSITORY_CREATED_AFTER_INSERTION_PHASE, result.reason());
		assertEquals(0, ((Advised) bean).getAdvisors().length);
		assertEquals(1, fixture.diagnostics.count(
				RepositoryEligibilityReason.REPOSITORY_CREATED_AFTER_INSERTION_PHASE));
	}

	@Test
	void disabledAdviceProceedsOnceAndPreservesExactResultThrowableAndRuntime() throws Throwable {
		CallerBoundaryAdvice advice = new CallerBoundaryAdvice(eligibility("repository"),
				new RepositoryAdapterMetrics());
		assertFalse(advice.isEnabled());
		AtomicInteger calls = new AtomicInteger();
		Object expected = new Object();
		assertSame(expected, advice.invoke(invocation(() -> {
			calls.incrementAndGet();
			return expected;
		})));
		assertEquals(1, calls.get());

		Throwable expectedFailure = new IllegalStateException("same");
		Throwable actual = assertThrows(Throwable.class, () -> advice.invoke(invocation(() -> {
			throw expectedFailure;
		})));
		assertSame(expectedFailure, actual);

		RuntimeContextService runtime = new RuntimeContextService(new RuntimeEventAggregator("run", "jvm"));
		String before = new RuntimeJsonSerializer().serialize(runtime.aggregator());
		advice.invoke(invocation(() -> expected));
		assertEquals(before, new RuntimeJsonSerializer().serialize(runtime.aggregator()));
	}

	@Test
	void movedAdvisorFailsAuditAndRestoresOriginalFingerprint() {
		Fixture fixture = new Fixture("repository");
		Advisor original = passThroughAdvisor();
		Object bean = repositoryProxy(original);
		Advised advised = (Advised) bean;
		fixture.register(bean);
		fixture.installations.insert(bean, fixture.eligibility("repository"));
		var installation = fixture.installations.installation("repository");
		advised.removeAdvisor(installation.advisor());
		advised.addAdvisor(1, installation.advisor());

		fixture.installations.audit(installation, bean);
		assertArrayEquals(new Advisor[] { original }, advised.getAdvisors());
		assertEquals(AUDIT_FAILED_RESTORED, fixture.installations.results().get(0).state());
		assertEquals(STP_ADVISOR_POSITION, fixture.installations.results().get(0).reason());
		assertFalse(fixture.eligibilityRegistry.find("repository").orElseThrow().eligible());
	}

	@Test
	void laterBeanReplacementFailsAuditAndRestoresOriginalProxy() {
		Fixture fixture = new Fixture("repository");
		Object bean = repositoryProxy();
		fixture.register(bean);
		fixture.installations.insert(bean, fixture.eligibility("repository"));
		var installation = fixture.installations.installation("repository");
		fixture.installations.audit(installation, repositoryProxy());
		assertEquals(AUDIT_FAILED_RESTORED, fixture.installations.results().get(0).state());
		assertEquals(BEAN_IDENTITY_CHANGED, fixture.installations.results().get(0).reason());
		assertEquals(0, ((Advised) bean).getAdvisors().length);
	}

	@Test
	void missingOriginalAdvisorMakesRestorationFailureAbort() {
		Fixture fixture = new Fixture("repository");
		Advisor original = passThroughAdvisor();
		Object bean = repositoryProxy(original);
		Advised advised = (Advised) bean;
		fixture.register(bean);
		fixture.installations.insert(bean, fixture.eligibility("repository"));
		var installation = fixture.installations.installation("repository");
		advised.removeAdvisor(original);

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> fixture.installations.audit(installation, bean));
		assertTrue(failure.getMessage().contains("restoration failed"));
		assertEquals(AdvisorInstallationState.AUDIT_FAILED_RESTORE_FAILED,
				fixture.installations.results().get(0).state());
		assertEquals(RESTORATION_FAILED, fixture.installations.results().get(0).reason());
	}

	@Test
	void closeClearsStateAndAuditDiagnosticsAreDeterministicAndBounded() throws Exception {
		Fixture fixture = new Fixture("repository");
		Object bean = repositoryProxy();
		fixture.register(bean);
		fixture.installations.insert(bean, fixture.eligibility("repository"));
		for (int index = 30; index >= 0; index--) {
			fixture.diagnostics.record(BEAN_IDENTITY_CHANGED, "repository-" + String.format("%02d", index));
		}
		var diagnostic = fixture.diagnostics.auditSnapshots().stream()
				.filter(value -> value.reason() == BEAN_IDENTITY_CHANGED).findFirst().orElseThrow();
		assertEquals(31, diagnostic.count());
		assertEquals(MetadataDiagnostics.SAMPLE_LIMIT, diagnostic.canonicalBeanNames().size());
		assertEquals(diagnostic.canonicalBeanNames().stream().sorted().toList(), diagnostic.canonicalBeanNames());
		fixture.installations.destroy();
		assertTrue(fixture.installations.results().isEmpty());
	}

	private static Advisor passThroughAdvisor() {
		return new DefaultPointcutAdvisor((MethodInterceptor) MethodInvocation::proceed);
	}

	private static Object repositoryProxy(Advisor... advisors) {
		ProxyFactory factory = new ProxyFactory();
		factory.setTarget(new RepositoryMetadataCollectorTest.FixtureCrudRepository<>());
		factory.setInterfaces(RepositoryMetadataCollectorTest.SampleRepository.class);
		Arrays.stream(advisors).forEach(factory::addAdvisor);
		return factory.getProxy();
	}

	private static RepositoryEligibility eligibility(String name) {
		return new RepositoryEligibility(name,
				RepositoryMetadataCollectorTest.SampleRepository.class.getName(),
				RepositoryMetadataCollectorTest.SampleEntity.class.getName(), true,
				RepositoryEligibilityReason.ELIGIBLE, RepositoryProxyKind.JDK,
				List.of(RepositoryMetadataCollectorTest.SampleRepository.class.getName()), 0, List.of(), true,
				"context");
	}

	private static MethodInvocation invocation(ThrowingSupplier supplier) throws NoSuchMethodException {
		Method method = RepositoryAdvisorInstallationTest.class.getDeclaredMethod("sampleMethod");
		return new MethodInvocation() {
			@Override public Method getMethod() { return method; }
			@Override public Object[] getArguments() { return new Object[0]; }
			@Override public Object proceed() throws Throwable { return supplier.get(); }
			@Override public Object getThis() { return this; }
			@Override public AccessibleObject getStaticPart() { return method; }
		};
	}

	@SuppressWarnings("unused")
	private static void sampleMethod() {
	}

	@FunctionalInterface
	private interface ThrowingSupplier {
		Object get() throws Throwable;
	}

	private static final class Fixture {
		private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		private final MetadataDiagnostics diagnostics = new MetadataDiagnostics();
		private final RepositoryMetadataRegistry metadataRegistry =
				new RepositoryMetadataRegistry("context", diagnostics);
		private final RepositoryEligibilityRegistry eligibilityRegistry =
				new RepositoryEligibilityRegistry(diagnostics, metadataRegistry);
		private final RepositoryAdvisorInstallationRegistry installations =
				new RepositoryAdvisorInstallationRegistry(beanFactory, eligibilityRegistry, metadataRegistry, diagnostics,
						new RepositoryAdapterMetrics());
		private final String beanName;

		private Fixture(String beanName) {
			this(beanName, true);
		}

		private Fixture(String beanName, boolean registerMetadata) {
			this.beanName = beanName;
			if (registerMetadata) registerMetadata();
		}

		private void registerMetadata() {
			metadataRegistry.register(new RepositoryMetadata(beanName,
					RepositoryMetadataCollectorTest.SampleRepository.class.getName(),
					RepositoryMetadataCollectorTest.SampleEntity.class.getName(), List.of(),
					RepositoryMetadataProvenance.REPOSITORY_INFORMATION_VIA_PROXY_POST_PROCESSOR, "context"));
		}

		private void register(Object bean) {
			beanFactory.registerSingleton(beanName, bean);
			eligibilityRegistry.register(bean, eligibility(beanName));
		}

		private RepositoryEligibility eligibility(String name) {
			return RepositoryAdvisorInstallationTest.eligibility(name);
		}
	}
}
