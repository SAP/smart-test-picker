// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

import static com.sap.oss.smarttestpicker.springdata.RepositoryEligibilityReason.AMBIGUOUS_REPOSITORY_METADATA;
import static com.sap.oss.smarttestpicker.springdata.RepositoryEligibilityReason.CONTEXT_MISMATCH;
import static com.sap.oss.smarttestpicker.springdata.RepositoryEligibilityReason.DUPLICATE_BEAN_ENCOUNTER;
import static com.sap.oss.smarttestpicker.springdata.RepositoryEligibilityReason.EXISTING_STP_ADVISOR;
import static com.sap.oss.smarttestpicker.springdata.RepositoryEligibilityReason.MISSING_FACTORY_METADATA;
import static com.sap.oss.smarttestpicker.springdata.RepositoryEligibilityReason.NON_ADVISED_REPOSITORY;
import static com.sap.oss.smarttestpicker.springdata.RepositoryEligibilityReason.REPOSITORY_INTERFACE_NOT_EXPOSED;
import static com.sap.oss.smarttestpicker.springdata.RepositoryEligibilityReason.STP_INFRASTRUCTURE_BEAN;
import static com.sap.oss.smarttestpicker.springdata.RepositoryEligibilityReason.UNSUPPORTED_PROXY_TYPE;
import static com.sap.oss.smarttestpicker.springdata.RepositoryMetadataProvenance.REPOSITORY_INFORMATION_VIA_PROXY_POST_PROCESSOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryEligibilityProcessorTest {
	@Test
	void classifiesUnsupportedMetadataAndProxyCasesWithoutChangingBean() {
		Fixture fixture = new Fixture("context");
		Object sampleProxy = proxy(RepositoryMetadataCollectorTest.SampleRepository.class, false, null);
		Object otherProxy = proxy(RepositoryMetadataCollectorTest.OtherRepository.class, false, null);

		assertEquals(MISSING_FACTORY_METADATA,
				fixture.processor.classify(sampleProxy, "missing", null).reason());
		assertEquals(NON_ADVISED_REPOSITORY, fixture.processor.classify(
				new RepositoryMetadataCollectorTest.FixtureCrudRepository<>(), "sampleRepository",
				fixture.metadata("sampleRepository", "context")).reason());
		assertEquals(REPOSITORY_INTERFACE_NOT_EXPOSED, fixture.processor.classify(otherProxy, "sampleRepository",
				fixture.metadata("sampleRepository", "context")).reason());

		RepositoryMetadata mismatchedContext = fixture.metadata("sampleRepository", "other-context");
		assertEquals(CONTEXT_MISMATCH,
				fixture.processor.classify(sampleProxy, "sampleRepository", mismatchedContext).reason());
	}

	@Test
	void conflictingMetadataIsAmbiguousRatherThanFirstMatch() {
		Fixture fixture = new Fixture("context");
		RepositoryMetadata original = fixture.metadata("sampleRepository", "context");
		assertTrue(fixture.metadataRegistry.register(original));
		assertFalse(fixture.metadataRegistry.register(new RepositoryMetadata("sampleRepository",
				RepositoryMetadataCollectorTest.OtherRepository.class.getName(),
				RepositoryMetadataCollectorTest.OtherEntity.class.getName(), List.of(),
				REPOSITORY_INFORMATION_VIA_PROXY_POST_PROCESSOR, "context")));
		Object bean = proxy(RepositoryMetadataCollectorTest.SampleRepository.class, false, null);
		assertEquals(AMBIGUOUS_REPOSITORY_METADATA,
				fixture.processor.classify(bean, "sampleRepository", original).reason());
	}

	@Test
	void detectsExactStpMarkerAndLeavesAdvisorChainAndReferenceUnchanged() {
		Fixture fixture = new Fixture("context");
		Object bean = proxy(RepositoryMetadataCollectorTest.SampleRepository.class, false, new MarkerAdvisor());
		Advised advised = (Advised) bean;
		Advisor[] before = advised.getAdvisors();
		RepositoryEligibility result = fixture.processor.classify(bean, "sampleRepository",
				fixture.metadata("sampleRepository", "context"));
		assertEquals(EXISTING_STP_ADVISOR, result.reason());
		assertSame(bean, fixture.processor.postProcessAfterInitialization(bean, "unregisteredRepository"));
		assertEquals(List.of(before), List.of(advised.getAdvisors()));
	}

	@Test
	void eligibleProductClassificationDoesNotInsertAdvisorDuringBeanPostProcessing() {
		Fixture fixture = new Fixture("context");
		fixture.metadataRegistry.register(fixture.metadata("sampleRepository", "context"));
		Object bean = proxy(RepositoryMetadataCollectorTest.SampleRepository.class, false, null);
		assertSame(bean, fixture.processor.postProcessAfterInitialization(bean, "sampleRepository"));
		assertEquals(0, ((Advised) bean).getAdvisors().length);
		assertTrue(fixture.eligibilityRegistry.find("sampleRepository").orElseThrow().eligible());
	}

	@Test
	void classBasedProxyIsInspectedButNotClaimed() {
		Fixture fixture = new Fixture("context");
		Object bean = proxy(RepositoryMetadataCollectorTest.SampleRepository.class, true, null);
		RepositoryEligibility result = fixture.processor.classify(bean, "sampleRepository",
				fixture.metadata("sampleRepository", "context"));
		assertEquals(RepositoryProxyKind.CLASS_BASED, result.proxyKind());
		assertEquals(UNSUPPORTED_PROXY_TYPE, result.reason());
	}

	@Test
	void identityRegistryIsIdempotentAndRejectsDifferentObjectForCanonicalName() {
		Fixture fixture = new Fixture("context");
		Object first = new Object();
		Object second = new Object();
		RepositoryEligibility result = fixture.result("repository");
		assertSame(result, fixture.eligibilityRegistry.register(first, result));
		assertSame(result, fixture.eligibilityRegistry.register(first, result));
		RepositoryEligibility rejected = fixture.eligibilityRegistry.register(second, result);
		assertFalse(rejected.eligible());
		assertEquals(DUPLICATE_BEAN_ENCOUNTER, rejected.reason());
		assertSame(rejected, fixture.eligibilityRegistry.find("repository").orElseThrow());
		assertEquals(1, fixture.eligibilityRegistry.entries().size());
		assertEquals(2, fixture.diagnostics.count(DUPLICATE_BEAN_ENCOUNTER));
	}

	@Test
	void infrastructureCandidateIsSkippedAndRegistryClearsOnClose() throws Exception {
		Fixture fixture = new Fixture("context");
		Object infrastructure = new RepositoryMetadataCollectorTest.FixtureCrudRepository<>();
		assertSame(infrastructure,
				fixture.processor.postProcessAfterInitialization(infrastructure, "infrastructureRepository"));
		assertEquals(1, fixture.diagnostics.count(STP_INFRASTRUCTURE_BEAN));
		assertTrue(fixture.eligibilityRegistry.entries().isEmpty());

		fixture.eligibilityRegistry.register(new Object(), fixture.result("repository"));
		fixture.eligibilityRegistry.destroy();
		assertTrue(fixture.eligibilityRegistry.entries().isEmpty());
	}

	@Test
	void diagnosticsAndEligibilityIterationAreDeterministic() {
		Fixture fixture = new Fixture("context");
		fixture.eligibilityRegistry.register(new Object(), fixture.result("zRepository"));
		fixture.eligibilityRegistry.register(new Object(), fixture.result("aRepository"));
		for (int index = 30; index >= 0; index--) {
			fixture.diagnostics.record(MISSING_FACTORY_METADATA, "repository-" + String.format("%02d", index));
		}
		assertEquals(List.of("aRepository", "zRepository"), fixture.eligibilityRegistry.entries().stream()
				.map(RepositoryEligibility::canonicalBeanName).toList());
		MetadataDiagnostics.EligibilityDiagnosticSnapshot snapshot = fixture.diagnostics.eligibilitySnapshots().stream()
				.filter(value -> value.reason() == MISSING_FACTORY_METADATA).findFirst().orElseThrow();
		assertEquals(31, snapshot.count());
		assertEquals(MetadataDiagnostics.SAMPLE_LIMIT, snapshot.canonicalBeanNames().size());
		assertEquals(snapshot.canonicalBeanNames().stream().sorted().toList(), snapshot.canonicalBeanNames());
	}

	private static Object proxy(Class<?> repositoryInterface, boolean classBased, Advisor advisor) {
		ProxyFactory factory = new ProxyFactory();
		factory.setTarget(new RepositoryMetadataCollectorTest.FixtureCrudRepository<>());
		factory.setInterfaces(repositoryInterface);
		factory.setProxyTargetClass(classBased);
		if (advisor != null) factory.addAdvisor(advisor);
		return factory.getProxy();
	}

	private static final class Fixture {
		private final MetadataDiagnostics diagnostics = new MetadataDiagnostics();
		private final RepositoryMetadataRegistry metadataRegistry =
				new RepositoryMetadataRegistry("context", diagnostics);
		private final RepositoryEligibilityRegistry eligibilityRegistry =
				new RepositoryEligibilityRegistry(diagnostics, metadataRegistry);
		private final RepositoryEligibilityProcessor processor = new RepositoryEligibilityProcessor(
				new DefaultListableBeanFactory(), metadataRegistry, eligibilityRegistry, diagnostics, "context");

		private Fixture(String ignoredContext) {
		}

		private RepositoryMetadata metadata(String beanName, String context) {
			return new RepositoryMetadata(beanName, RepositoryMetadataCollectorTest.SampleRepository.class.getName(),
					RepositoryMetadataCollectorTest.SampleEntity.class.getName(), List.of(),
					REPOSITORY_INFORMATION_VIA_PROXY_POST_PROCESSOR, context);
		}

		private RepositoryEligibility result(String beanName) {
			return new RepositoryEligibility(beanName,
					RepositoryMetadataCollectorTest.SampleRepository.class.getName(),
					RepositoryMetadataCollectorTest.SampleEntity.class.getName(), true,
					RepositoryEligibilityReason.ELIGIBLE, RepositoryProxyKind.JDK,
					List.of(RepositoryMetadataCollectorTest.SampleRepository.class.getName()), 0, List.of(), true,
					"context");
		}
	}

	private static final class MarkerAdvisor implements Advisor, StpCallerAdvisorMarker {
		private static final Advice ADVICE = new Advice() { };

		@Override
		public Advice getAdvice() {
			return ADVICE;
		}

		@Override
		public boolean isPerInstance() {
			return true;
		}
	}

}
