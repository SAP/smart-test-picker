// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springdatae2e.tests;

import example.springdatae2e.app.FixtureApplication;
import example.springdatae2e.app.FixtureCounters;
import example.springdatae2e.app.SampleService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = FixtureApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpringDataRuntimeE2eTest {
	private final SampleService service;
	private final CacheManager cacheManager;
	private final ApplicationContext context;
	@Autowired SpringDataRuntimeE2eTest(SampleService service, CacheManager cacheManager,
			ApplicationContext context) {
		this.service = service;
		this.cacheManager = cacheManager;
		this.context = context;
	}
	@BeforeEach void resetCacheEvidence() {
		FixtureCounters.CACHED_EXECUTIONS.set(0);
		var cache = cacheManager.getCache("samples-by-name");
		if (cache != null) cache.clear();
	}
	@Test @Order(1) void cachedSuccessfulGraph() throws Exception {
		if (Boolean.getBoolean("stp.spring-data.enabled")) {
			assertEquals(true, context.containsBean(
					"com.sap.oss.smarttestpicker.springdata.internalAdvisorInstallationRegistry"));
			Advised repository = (Advised) context.getBean("sampleRepository");
			Object diagnostics = context.getBean(
					"com.sap.oss.smarttestpicker.springdata.internalMetadataDiagnostics");
			var snapshots = diagnostics.getClass().getDeclaredMethod("eligibilitySnapshots");
			snapshots.setAccessible(true);
			assertEquals(1, java.util.Arrays.stream(repository.getAdvisors())
					.filter(advisor -> advisor.getClass().getName().equals(
							"com.sap.oss.smarttestpicker.springdata.StpCallerAdvisor")).count(),
					String.valueOf(snapshots.invoke(diagnostics)));
		}
		service.cachedLookupTwice();
		assertEquals(1, FixtureCounters.CACHED_EXECUTIONS.get());
		FixtureCounters.CACHED_ASSERTED_EXECUTIONS.set(FixtureCounters.CACHED_EXECUTIONS.get());
	}
	@Test @Order(2) void failedRepositoryPreservesThrowable() {
		RuntimeException actual = assertThrows(RuntimeException.class, service::failRepository);
		assertSame(FixtureCounters.EXPECTED_FAILURE, actual);
	}
	@Test @Order(3) void sequentialAlpha() { service.alpha(); }
	@Test @Order(4) void sequentialBeta() throws Exception {
		service.beta();
		if (Boolean.getBoolean("stp.spring-data.enabled")) writeAdapterMetrics();
	}

	private void writeAdapterMetrics() throws Exception {
		Object metrics = context.getBean("com.sap.oss.smarttestpicker.springdata.internalRepositoryMetrics");
		var snapshotMethod = metrics.getClass().getDeclaredMethod("snapshot");
		snapshotMethod.setAccessible(true);
		Object snapshot = snapshotMethod.invoke(metrics);
		long invocations = metric(snapshot, "invocationsObserved");
		long recorded = metric(snapshot, "eventsRecorded");
		long nanos = metric(snapshot, "recordingNanos");
		Files.writeString(Path.of(System.getProperty("e2e.metrics.output")),
				"{\"invocationsObserved\":" + invocations + ",\"eventsRecorded\":" + recorded
						+ ",\"recordingNanos\":" + nanos + ",\"cachedUnderlyingExecutions\":"
						+ FixtureCounters.CACHED_ASSERTED_EXECUTIONS.get() + "}\n");
	}

	private static long metric(Object snapshot, String name) throws Exception {
		var accessor = snapshot.getClass().getDeclaredMethod(name);
		accessor.setAccessible(true);
		return (long) accessor.invoke(snapshot);
	}
}
