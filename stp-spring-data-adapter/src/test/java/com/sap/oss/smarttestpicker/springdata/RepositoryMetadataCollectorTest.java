// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeEventAggregator;
import com.sap.oss.smarttestpicker.runtime.RuntimeJsonSerializer;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sap.oss.smarttestpicker.springdata.MetadataDiagnosticReason.ALIAS_CONFLICT;
import static com.sap.oss.smarttestpicker.springdata.AdvisorInstallationState.AUDIT_PASSED;
import static com.sap.oss.smarttestpicker.springdata.MetadataDiagnosticReason.CONFLICTING_METADATA;
import static com.sap.oss.smarttestpicker.springdata.MetadataDiagnosticReason.DUPLICATE_EQUAL_METADATA;
import static com.sap.oss.smarttestpicker.springdata.RepositoryMetadataProvenance.REPOSITORY_INFORMATION_VIA_PROXY_POST_PROCESSOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryMetadataCollectorTest {
	@Test
	void collectsCanonicalImmutableMetadataFromRealInheritedCrudRepositoryFactory() {
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime());
				GenericApplicationContext context = repositoryContext("metadata-context", "owners", "zOwners")) {
			RepositoryMetadataRegistry registry = registry(context);
			List<com.sap.oss.smarttestpicker.springdata.RepositoryMetadata> entries = registry.entries();
			assertEquals(1, entries.size());
			var metadata = entries.get(0);
			assertEquals("sampleRepository", metadata.canonicalBeanName());
			assertEquals(SampleRepository.class.getName(), metadata.repositoryInterface());
			assertEquals(SampleEntity.class.getName(), metadata.domainType());
			assertEquals(List.of("owners", "zOwners"), metadata.aliases());
			assertEquals(REPOSITORY_INFORMATION_VIA_PROXY_POST_PROCESSOR, metadata.provenance());
			assertEquals("metadata-context", metadata.contextIdentity());
			assertEquals(metadata, registry.find("sampleRepository").orElseThrow());
			assertEquals(metadata, registry.find("owners").orElseThrow());
			assertEquals(1, registry.entries().size(), "aliases do not create duplicate metadata entries");
			assertFalse(metadata.aliases() instanceof ArrayList);
			context.getBean(SampleRepository.class); // create and classify the FactoryBean product
			RepositoryEligibilityRegistry eligibility = context.getBean(
					StpSpringDataApplicationContextInitializer.ELIGIBILITY_REGISTRY_BEAN_NAME,
					RepositoryEligibilityRegistry.class);
			RepositoryEligibility result = eligibility.find("owners").orElseThrow();
			assertTrue(result.eligible());
			assertEquals(RepositoryEligibilityReason.ELIGIBLE, result.reason());
			assertEquals(RepositoryProxyKind.JDK, result.proxyKind());
			assertTrue(result.exposedInterfaces().contains(SampleRepository.class.getName()));
			SampleRepository firstLookup = context.getBean(SampleRepository.class);
			assertSame(firstLookup, context.getBean(SampleRepository.class));
			assertEquals(1, java.util.Arrays.stream(((Advised) firstLookup).getAdvisors())
					.filter(advisor -> advisor instanceof StpCallerAdvisorMarker).count());
			RepositoryAdvisorInstallationRegistry installations = context.getBean(
					StpSpringDataApplicationContextInitializer.INSTALLATION_REGISTRY_BEAN_NAME,
					RepositoryAdvisorInstallationRegistry.class);
			assertEquals(AUDIT_PASSED, installations.results().get(0).state());
		}
	}

	@Test
	void duplicateEqualRegistrationIsIdempotentAndDiagnosed() {
		MetadataDiagnostics diagnostics = new MetadataDiagnostics();
		RepositoryMetadataRegistry registry = new RepositoryMetadataRegistry("context", diagnostics);
		var metadata = metadata("repository", SampleRepository.class.getName(), SampleEntity.class.getName(),
				List.of("owners"), "context");
		assertTrue(registry.register(metadata));
		assertTrue(registry.register(metadata));
		assertEquals(1, registry.entries().size());
		assertEquals(1, count(diagnostics, DUPLICATE_EQUAL_METADATA));
	}

	@Test
	void conflictingInterfaceDomainAndContextAreRejectedWithoutFirstValueSelection() {
		MetadataDiagnostics diagnostics = new MetadataDiagnostics();
		RepositoryMetadataRegistry registry = new RepositoryMetadataRegistry("context", diagnostics);
		assertTrue(registry.register(metadata("repository", SampleRepository.class.getName(),
				SampleEntity.class.getName(), List.of(), "context")));
		assertFalse(registry.register(metadata("repository", OtherRepository.class.getName(),
				SampleEntity.class.getName(), List.of(), "context")));
		assertFalse(registry.register(metadata("repository", SampleRepository.class.getName(),
				OtherEntity.class.getName(), List.of(), "context")));
		assertFalse(registry.register(metadata("other", OtherRepository.class.getName(),
				OtherEntity.class.getName(), List.of(), "another-context")));
		assertEquals(3, count(diagnostics, CONFLICTING_METADATA));
		assertEquals(SampleRepository.class.getName(), registry.entries().get(0).repositoryInterface());
	}

	@Test
	void inconsistentAliasOwnershipRejectsSecondCanonicalEntry() {
		MetadataDiagnostics diagnostics = new MetadataDiagnostics();
		RepositoryMetadataRegistry registry = new RepositoryMetadataRegistry("context", diagnostics);
		assertTrue(registry.register(metadata("aRepository", SampleRepository.class.getName(),
				SampleEntity.class.getName(), List.of("shared"), "context")));
		assertFalse(registry.register(metadata("bRepository", OtherRepository.class.getName(),
				OtherEntity.class.getName(), List.of("shared"), "context")));
		assertEquals(1, count(diagnostics, ALIAS_CONFLICT));
		assertEquals(List.of("aRepository"), registry.entries().stream()
				.map(com.sap.oss.smarttestpicker.springdata.RepositoryMetadata::canonicalBeanName).toList());
	}

	@Test
	void metadataIsIsolatedByContextAndClearedOnClose() {
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime())) {
			RepositoryMetadataRegistry firstRegistry;
			try (GenericApplicationContext first = repositoryContext("first-context")) {
				firstRegistry = registry(first);
				assertEquals("first-context", firstRegistry.entries().get(0).contextIdentity());
			}
			assertTrue(firstRegistry.entries().isEmpty());
			try (GenericApplicationContext second = repositoryContext("second-context")) {
				assertEquals("second-context", registry(second).entries().get(0).contextIdentity());
			}
		}
	}

	@Test
	void registryRetainsNoRepositoryBeanAndAuditedAdvisorRecordsTerminalEvent() throws Exception {
		RuntimeContextService runtime = runtime();
		TestIdentity test = new TestIdentity("test", "test", "example.Test", "test", "junit-jupiter",
				"run-1", "jvm-1");
		runtime.beginTest(test);
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime);
				GenericApplicationContext context = repositoryContext("behavior-context")) {
			SampleRepository repository = context.getBean(SampleRepository.class);
			SampleEntity entity = new SampleEntity(1L);
			assertSame(entity, repository.save(entity));

			Advised advised = (Advised) repository;
			assertEquals(1, java.util.Arrays.stream(advised.getAdvisors())
					.filter(advisor -> advisor instanceof StpCallerAdvisorMarker).count());
			RepositoryMetadataRegistry registry = registry(context);
			for (Field field : RepositoryMetadataRegistry.class.getDeclaredFields()) {
				field.setAccessible(true);
				assertFalse(field.get(registry) == repository);
				if (field.get(registry) instanceof Map<?, ?> map) {
					assertTrue(map.values().stream().noneMatch(value -> value == repository));
				}
			}
			String json = new RuntimeJsonSerializer().serialize(runtime.aggregator());
			assertTrue(json.contains("\"repositoryKind\":\"SPRING_DATA_PROXY\""));
			assertTrue(json.contains("\"methodName\":\"save\""));
			assertTrue(json.contains("\"jvmDescriptor\":\"(Ljava/lang/Object;)Ljava/lang/Object;\""));
			assertTrue(json.contains("\"outcome\":\"SUCCEEDED\""));
		}
	}

	@Test
	void metadataAndDiagnosticsHaveDeterministicBoundedOrdering() {
		MetadataDiagnostics diagnostics = new MetadataDiagnostics();
		RepositoryMetadataRegistry registry = new RepositoryMetadataRegistry("context", diagnostics);
		assertTrue(registry.register(metadata("zRepository", OtherRepository.class.getName(),
				OtherEntity.class.getName(), List.of(), "context")));
		assertTrue(registry.register(metadata("aRepository", SampleRepository.class.getName(),
				SampleEntity.class.getName(), List.of(), "context")));
		for (int index = 30; index >= 0; index--) {
			diagnostics.record(CONFLICTING_METADATA, "repository-" + String.format("%02d", index));
		}
		assertEquals(List.of("aRepository", "zRepository"), registry.entries().stream()
				.map(com.sap.oss.smarttestpicker.springdata.RepositoryMetadata::canonicalBeanName).toList());
		var snapshot = diagnostics.snapshots().stream().filter(value -> value.reason() == CONFLICTING_METADATA)
				.findFirst().orElseThrow();
		assertEquals(31, snapshot.count());
		assertEquals(MetadataDiagnostics.SAMPLE_LIMIT, snapshot.canonicalBeanNames().size());
		assertEquals(snapshot.canonicalBeanNames().stream().sorted().toList(), snapshot.canonicalBeanNames());
	}

	private static GenericApplicationContext repositoryContext(String id, String... aliases) {
		GenericApplicationContext context = new GenericApplicationContext();
		context.setId(id);
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
				Map.of(SpringDataAdapterProperties.ENABLED, "true")));
		new StpSpringDataApplicationContextInitializer().initialize(context);
		context.registerBeanDefinition("sampleRepository", new RootBeanDefinition(RealFactoryBean.class,
				RealFactoryBean::new));
		for (String alias : aliases) context.registerAlias("sampleRepository", alias);
		context.registerBean(RepositoryConsumer.class,
				() -> new RepositoryConsumer(context.getBean(SampleRepository.class)));
		context.refresh();
		return context;
	}

	private static RepositoryMetadataRegistry registry(GenericApplicationContext context) {
		return context.getBean(StpSpringDataApplicationContextInitializer.REGISTRY_BEAN_NAME,
				RepositoryMetadataRegistry.class);
	}

	private static RuntimeContextService runtime() {
		return new RuntimeContextService(new RuntimeEventAggregator("run-1", "jvm-1"));
	}

	private static com.sap.oss.smarttestpicker.springdata.RepositoryMetadata metadata(String beanName,
			String repositoryInterface, String domainType, List<String> aliases, String contextIdentity) {
		return new com.sap.oss.smarttestpicker.springdata.RepositoryMetadata(beanName, repositoryInterface, domainType,
				aliases, REPOSITORY_INFORMATION_VIA_PROXY_POST_PROCESSOR, contextIdentity);
	}

	private static long count(MetadataDiagnostics diagnostics, MetadataDiagnosticReason reason) {
		return diagnostics.snapshots().stream().filter(value -> value.reason() == reason)
				.mapToLong(MetadataDiagnostics.DiagnosticSnapshot::count).sum();
	}

	public static final class SampleEntity {
		private final Long id;

		SampleEntity(Long id) {
			this.id = id;
		}

		Long id() {
			return id;
		}
	}

	public static final class OtherEntity {
	}

	public interface SampleRepository extends CrudRepository<SampleEntity, Long> {
	}

	public interface OtherRepository extends CrudRepository<OtherEntity, Long> {
	}

	static final class RealFactoryBean extends RepositoryFactoryBeanSupport<SampleRepository, SampleEntity, Long> {
		RealFactoryBean() {
			super(SampleRepository.class);
		}

		@Override
		protected RepositoryFactorySupport createRepositoryFactory() {
			return new FixtureRepositoryFactory();
		}
	}

	static final class FixtureRepositoryFactory extends RepositoryFactorySupport {
		@Override
		protected Object getTargetRepository(RepositoryInformation information) {
			return new FixtureCrudRepository<>();
		}

		@Override
		protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
			return FixtureCrudRepository.class;
		}
	}

	static class FixtureCrudRepository<T, ID> implements CrudRepository<T, ID> {
		@Override
		public <S extends T> S save(S entity) {
			return entity;
		}

		@Override
		public <S extends T> Iterable<S> saveAll(Iterable<S> entities) {
			return entities;
		}

		@Override
		public Optional<T> findById(ID id) {
			return Optional.empty();
		}

		@Override
		public boolean existsById(ID id) {
			return false;
		}

		@Override
		public Iterable<T> findAll() {
			return Collections.emptyList();
		}

		@Override
		public Iterable<T> findAllById(Iterable<ID> ids) {
			return Collections.emptyList();
		}

		@Override
		public long count() {
			return 0;
		}

		@Override
		public void deleteById(ID id) {
		}

		@Override
		public void delete(T entity) {
		}

		@Override
		public void deleteAllById(Iterable<? extends ID> ids) {
		}

		@Override
		public void deleteAll(Iterable<? extends T> entities) {
		}

		@Override
		public void deleteAll() {
		}
	}

	record RepositoryConsumer(SampleRepository repository) {
	}
}
