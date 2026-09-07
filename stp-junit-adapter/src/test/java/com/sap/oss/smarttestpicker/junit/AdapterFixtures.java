// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.junit;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.model.Certainty;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.EvidenceSource;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

final class FixtureEvents {
	static RuntimeContextService runtime;

	private FixtureEvents() {
	}

	static void hit(String name) {
		hit("fixture.Target", name);
	}

	static void hit(String owner, String name) {
		runtime.record(new MethodHitEvent(new MethodIdentity(owner, name, "()V"),
				new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED)));
	}
}

class OrdinaryFixtureSuite {
	@BeforeEach
	void beforeEach() {
		FixtureEvents.hit("beforeEach");
	}

	@AfterEach
	void afterEach() {
		FixtureEvents.hit("afterEach");
	}

	@Test
	void successful() {
		FixtureEvents.hit("successful");
	}

	@Test
	void failed() {
		FixtureEvents.hit("failed");
		throw new IllegalStateException("synthetic failure");
	}

	@Test
	void aborted() {
		FixtureEvents.hit("aborted");
		Assumptions.abort("synthetic abort");
	}
}

class ParameterizedFixtureSuite {
	@ParameterizedTest(name = "value={0}")
	@ValueSource(strings = { "alpha", "beta" })
	void parameterized(String ignored) {
		FixtureEvents.hit("parameterized");
	}
}

class RepeatedFixtureSuite {
	@RepeatedTest(2)
	void repeated(RepetitionInfo ignored) {
		FixtureEvents.hit("repeated");
	}
}

class DynamicFixtureSuite {
	@TestFactory
	List<DynamicTest> dynamicTests() {
		return List.of(DynamicTest.dynamicTest("dynamic-a", () -> FixtureEvents.hit("dynamicA")),
				DynamicTest.dynamicTest("dynamic-b", () -> FixtureEvents.hit("dynamicB")));
	}
}

class TemplateFixtureSuite {
	@TestTemplate
	@ExtendWith(TwoInvocations.class)
	void template() {
		FixtureEvents.hit("template");
	}
}

class TwoInvocations implements TestTemplateInvocationContextProvider {
	@Override public boolean supportsTestTemplate(ExtensionContext context) { return true; }
	@Override public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
		return Stream.of(invocation("one"), invocation("two"));
	}
	private static TestTemplateInvocationContext invocation(String name) {
		return new TestTemplateInvocationContext() {
			@Override public String getDisplayName(int invocationIndex) { return name; }
		};
	}
}

class NestedFixtureSuite {
	@Nested
	class Inner {
		@Test
		void nestedLeaf() {
			FixtureEvents.hit("nestedLeaf");
		}
	}
}

class LifecycleFixtureSuite {
	@BeforeAll
	static void beforeAll() {
		FixtureEvents.hit("beforeAll");
	}

	@BeforeEach
	void beforeEach() {
		FixtureEvents.hit("lifecycleBeforeEach");
	}

	@Test
	void leaf() {
		FixtureEvents.hit("lifecycleLeaf");
	}

	@AfterEach
	void afterEach() {
		FixtureEvents.hit("lifecycleAfterEach");
	}

	@AfterAll
	static void afterAll() {
		FixtureEvents.hit("afterAll");
	}
}

abstract class InheritedLifecycleBase {
	@BeforeAll static void inheritedBeforeAll() { FixtureEvents.hit(InheritedLifecycleBase.class.getName(), "inheritedBeforeAll"); }
	@AfterAll static void inheritedAfterAll() { FixtureEvents.hit(InheritedLifecycleBase.class.getName(), "inheritedAfterAll"); }
}

class InheritedLifecycleFixtureSuite extends InheritedLifecycleBase {
	@Test void inheritedLeaf() { FixtureEvents.hit("inheritedLeaf"); }
}

class SharedContextFixtureSuite {
	@BeforeAll static void initializeSharedContextWithoutKnownConsumers() {
		FixtureEvents.runtime.beginUnboundedSharedContextSetup("cached-fixture");
		try { FixtureEvents.hit("sharedInitialization"); }
		finally { FixtureEvents.runtime.endUnboundedSharedContextSetup("cached-fixture"); }
	}
	@Test void consumer() { FixtureEvents.hit("sharedConsumer"); }
}

class SequentialFixtureSuite {
	@Test
	void first() {
		FixtureEvents.hit("firstOnly");
	}

	@Test
	void second() {
		FixtureEvents.hit("secondOnly");
	}
}

class OverloadedTestFixtureSuite {
	@Test void overloaded() { FixtureEvents.hit("overloadedZero"); }
	@Test void overloaded(TestInfo ignored) { FixtureEvents.hit("overloadedInfo"); }
}

@Execution(ExecutionMode.CONCURRENT)
class ParallelFixtureSuite {
	@Test void first() { FixtureEvents.hit("parallelFirst"); }
	@Test void second() { FixtureEvents.hit("parallelSecond"); }
}
