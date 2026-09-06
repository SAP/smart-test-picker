// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.junit;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeEventAggregator;
import com.sap.oss.smarttestpicker.runtime.RuntimeJsonSerializer;
import com.sap.oss.smarttestpicker.runtime.AsmCoverageFragmentProjector;
import com.sap.oss.smarttestpicker.runtime.CollectorIntegrity;
import com.sap.oss.smarttestpicker.runtime.FragmentProjectionConfig;
import com.sap.oss.smarttestpicker.runtime.model.Certainty;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.EvidenceSource;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class StpRuntimeTestExecutionListenerTest {
	private static final RuntimeJsonSerializer JSON = new RuntimeJsonSerializer();

	@Test
	void recordsSuccessfulFailedAndAbortedResultsAndAlwaysCleansUp() {
		Run run = execute(OrdinaryFixtureSuite.class);
		String json = run.json();
		assertTrue(json.contains("\"status\":\"SUCCESSFUL\""));
		assertTrue(json.contains("\"status\":\"FAILED\""));
		assertTrue(json.contains("\"status\":\"ABORTED\""));
		assertTrue(json.contains("\"failureType\":\"java.lang.IllegalStateException\""));
		assertTrue(json.contains("\"failureMessage\":\"synthetic failure\""));
		assertFalse(json.contains("at com."));
		assertTrue(run.runtime.currentTest().isEmpty());
	}

	@Test
	void parameterizedInvocationsAreSeparateAndMatchGoldenOutput() throws IOException {
		Run run = execute(ParameterizedFixtureSuite.class);
		String expected;
		try (var stream = getClass().getResourceAsStream("/golden/two-parameterized-invocations.json")) {
			expected = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertEquals(expected, run.json());
		assertEquals(2, occurrences(run.json(), "\"testId\""));
		assertEquals(2, occurrences(run.json(), "\"testMethod\": \"parameterized\""));
	}

	@Test
	void repeatedInvocationsAreSeparate() {
		String json = execute(RepeatedFixtureSuite.class).json();
		assertTrue(json.contains("[test-template-invocation:#1]"));
		assertTrue(json.contains("[test-template-invocation:#2]"));
		assertEquals(2, occurrences(json, "fixture.Target#repeated()V"));
	}

	@Test
	void dynamicTestsAreSeparate() {
		Run run = execute(DynamicFixtureSuite.class);
		String json = run.json();
		assertTrue(json.contains("dynamic-a"));
		assertTrue(json.contains("dynamic-b"));
		assertEquals(2, occurrences(json, "\"testId\""));
		var fragment = project(run);
		var factory = new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(
				DynamicFixtureSuite.class.getName(), "dynamicTests");
		assertEquals(1, fragment.tests().size());
		assertEquals(2, fragment.tests().get(factory).coveredMethods().size());
	}

	@Test
	void testTemplateInvocationsCollapseToDeclaredMethod() {
		var fragment = project(execute(TemplateFixtureSuite.class));
		assertEquals(1, fragment.tests().size());
		assertTrue(fragment.tests().containsKey(new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(
				TemplateFixtureSuite.class.getName(), "template")));
	}

	@Test
	void overloadedJunitMethodsRemainDistinctByMethodSourceParameterTypes() {
		var fragment = project(execute(OverloadedTestFixtureSuite.class));
		assertEquals(2, fragment.tests().size());
		assertTrue(fragment.tests().containsKey(new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(
				OverloadedTestFixtureSuite.class.getName(), "overloaded")));
		assertTrue(fragment.tests().containsKey(new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(
				OverloadedTestFixtureSuite.class.getName(), "overloaded", "org.junit.jupiter.api.TestInfo")));
	}

	@Test
	void parallelJunitLeavesRetainIndependentOwnership() {
		var fragment = project(executeParallel(ParallelFixtureSuite.class));
		assertEquals(2, fragment.tests().size());
		assertTrue(fragment.collectionCompleted());
		assertTrue(fragment.tests().values().stream().allMatch(coverage -> coverage.coveredMethods().size() == 1));
	}

	@Test
	void nestedLeafKeepsItsAuthoritativeUniqueId() {
		String json = execute(NestedFixtureSuite.class).json();
		assertTrue(json.contains("[nested-class:Inner]/[method:nestedLeaf()]"));
		assertTrue(json.contains("\"testClass\": \"com.sap.oss.smarttestpicker.junit.NestedFixtureSuite$Inner\""));
	}

	@Test
	void containersDoNotOpenContextsAndBeforeEachAndAfterEachAreInsideLeafInterval() {
		String json = execute(LifecycleFixtureSuite.class).json();
		assertEquals(1, occurrences(json, "\"testId\""));
		assertTrue(json.contains("fixture.Target#lifecycleBeforeEach()V"));
		assertTrue(json.contains("fixture.Target#lifecycleAfterEach()V"));
		assertFalse(json.contains("fixture.Target#afterAll()V\",\"evidence"));
		assertFalse(testDependencySection(json).contains("fixture.Target#beforeAll()V"));
		assertFalse(testDependencySection(json).contains("fixture.Target#afterAll()V"));
	}

	@Test
	void beforeAllIsRepresentedOnlyForItsActualContainer() {
		Run run = execute(LifecycleFixtureSuite.class);
		var fragment = project(run);
		assertEquals(1, fragment.setupScopes().size());
		assertEquals(LifecycleFixtureSuite.class.getName(), fragment.setupScopes().get(0)
				.affectedContainers().iterator().next().binaryName());
		assertEquals(java.util.Set.of("fixture.Target"), fragment.setupScopes().get(0).coveredClasses());
		assertTrue(run.runtime.aggregator().snapshot().setup().get(0).methods().stream()
				.anyMatch(method -> method.methodName().equals("afterAll")));
		assertFalse(testDependencySection(run.json()).contains("fixture.Target#afterAll()V"));
	}

	@Test
	void eventsDuringTestAreAttributedAndEventsAfterCompletionAreLate() {
		Run run = execute(SequentialFixtureSuite.class);
		run.runtime.record(method("afterLauncher"));
		String json = run.json();
		assertTrue(json.contains("fixture.Target#firstOnly()V"));
		assertTrue(json.contains("fixture.Target#secondOnly()V"));
		assertTrue(json.contains("\"reason\":\"NO_ACTIVE_TEST\""));
		assertTrue(json.contains("fixture.Target#afterLauncher()V"));
	}

	@Test
	void sequentialTestsDoNotLeakEvents() {
		String json = execute(SequentialFixtureSuite.class).json();
		int first = json.indexOf("[method:first()]");
		int second = json.indexOf("[method:second()]");
		if (second < first) {
			int swap = first;
			first = second;
			second = swap;
		}
		String firstSection = json.substring(first, second);
		String secondSection = json.substring(second);
		assertTrue(firstSection.contains(firstSection.contains("[method:first()]") ? "firstOnly" : "secondOnly"));
		assertFalse(firstSection.contains(firstSection.contains("[method:first()]") ? "secondOnly" : "firstOnly"));
		assertTrue(secondSection.contains(secondSection.contains("[method:first()]") ? "firstOnly" : "secondOnly"));
	}

	@Test
	void serviceLoaderRegistrationUsesRuntimeOwnedScopedRegistry() {
		assertTrue(ServiceLoader.load(TestExecutionListener.class).stream()
				.anyMatch(provider -> provider.type() == StpRuntimeTestExecutionListener.class));
		RuntimeContextService runtime = newRuntime();
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime)) {
			StpRuntimeTestExecutionListener listener = new StpRuntimeTestExecutionListener();
			assertEquals(runtime, RuntimeContextRegistry.current().orElseThrow());
			assertFalse(listener == null);
		}
		assertTrue(RuntimeContextRegistry.current().isEmpty());
	}

	@Test
	void noArgumentListenerConstructedWithoutAgentRuntimeRemainsNoOp() {
		assertTrue(RuntimeContextRegistry.current().isEmpty());
		StpRuntimeTestExecutionListener listener = new StpRuntimeTestExecutionListener();
		RuntimeContextService runtime = newRuntime();
		try (RuntimeContextRegistry.Registration ignored = RuntimeContextRegistry.install(runtime)) {
			TestIdentifier identifier = TestIdentifier.from(new AbstractTestDescriptor(
					UniqueId.forEngine("synthetic").append("test", "constructed-too-early"), "too early") {
				@Override
				public Type getType() {
					return Type.TEST;
				}
			});
			listener.executionStarted(identifier);
			listener.executionFinished(identifier, TestExecutionResult.successful());
			assertTrue(runtime.currentTest().isEmpty());
			assertTrue(JSON.serialize(runtime.aggregator()).contains("\"tests\": []"));
		}
	}

	@Test
	void supportsLeafIdentifierWithoutMethodSourceAndDoesNotReactivateFinishedTest() {
		RuntimeContextService runtime = newRuntime();
		StpRuntimeTestExecutionListener listener = new StpRuntimeTestExecutionListener(runtime);
		UniqueId id = UniqueId.forEngine("synthetic").append("test", "without-source");
		TestDescriptor descriptor = new AbstractTestDescriptor(id, "without source") {
			@Override
			public Type getType() {
				return Type.TEST;
			}
		};
		TestIdentifier identifier = TestIdentifier.from(descriptor);
		listener.executionStarted(identifier);
		listener.executionFinished(identifier, TestExecutionResult.successful());
		listener.executionStarted(identifier);

		String json = JSON.serialize(runtime.aggregator());
		assertTrue(runtime.currentTest().isEmpty());
		assertTrue(json.contains("\"testClass\": null"));
		assertTrue(json.contains("\"testMethod\": null"));
		assertEquals(1, occurrences(json, "\"testId\""));
	}

	private static Run execute(Class<?> fixture) {
		RuntimeContextService runtime = newRuntime();
		FixtureEvents.runtime = runtime;
		StpRuntimeTestExecutionListener listener = new StpRuntimeTestExecutionListener(runtime);
		Launcher launcher = LauncherFactory.create();
		launcher.registerTestExecutionListeners(listener);
		launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(fixture)).build());
		return new Run(runtime);
	}

	private static Run executeParallel(Class<?> fixture) {
		RuntimeContextService runtime = newRuntime();
		FixtureEvents.runtime = runtime;
		Launcher launcher = LauncherFactory.create();
		launcher.registerTestExecutionListeners(new StpRuntimeTestExecutionListener(runtime));
		launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(fixture))
				.configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
				.configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent").build());
		return new Run(runtime);
	}

	private static RuntimeContextService newRuntime() {
		return new RuntimeContextService(new RuntimeEventAggregator("run-1", "jvm-1"));
	}

	private static MethodHitEvent method(String name) {
		return new MethodHitEvent(new MethodIdentity("fixture.Target", name, "()V"),
				new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED));
	}

	private static com.sap.oss.smarttestpicker.coverage.model.CoverageFragment project(Run run) {
		return new AsmCoverageFragmentProjector().project(run.runtime.aggregator().snapshot(),
				FragmentProjectionConfig.of("revision-24", "shard-1"), CollectorIntegrity.healthy()).fragment();
	}

	private static String testDependencySection(String json) {
		return json.substring(json.indexOf("\"methods\""), json.indexOf("\"unattributedEvents\""));
	}

	private static int occurrences(String value, String needle) {
		return (value.length() - value.replace(needle, "").length()) / needle.length();
	}

	private record Run(RuntimeContextService runtime) {
		String json() {
			return JSON.serialize(runtime.aggregator());
		}
	}
}
