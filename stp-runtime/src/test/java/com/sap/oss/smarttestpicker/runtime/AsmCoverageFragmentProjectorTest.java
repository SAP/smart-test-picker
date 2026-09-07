// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidator;
import com.sap.oss.smarttestpicker.runtime.model.Certainty;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.EvidenceSource;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestExecutionStatus;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.TestResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AsmCoverageFragmentProjectorTest {
	private static final Evidence ASM = new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED);
	private final AsmCoverageFragmentProjector projector = new AsmCoverageFragmentProjector();

	@Test
	void mergesInvocationsRetainsFailedCoverageAndProjectsJvmMethodKinds() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		TestIdentity first = test("[engine:junit-jupiter]/[class:com.foo.PriceTest]/[test-template:calculates(java.lang.String)]/[test-template-invocation:#1]",
				"com.foo.PriceTest", "calculates", "java.lang.String");
		TestIdentity second = test("[engine:junit-jupiter]/[class:com.foo.PriceTest]/[test-template:calculates(java.lang.String)]/[test-template-invocation:#2]",
				"com.foo.PriceTest", "calculates", "java.lang.String");
		run(aggregator, first, TestExecutionStatus.SUCCESSFUL,
				method("com.foo.Price", "<init>", "()V"), method("com.foo.Price", "calculate", "(I)I"));
		run(aggregator, second, TestExecutionStatus.FAILED,
				method("com.foo.Price", "<clinit>", "()V"), method("com.foo.Price$Helper", "lambda$calculate$0", "()V"));

		var result = project(aggregator, CollectorIntegrity.healthy());
		var identity = new com.sap.oss.smarttestpicker.coverage.model.TestIdentity("com.foo.PriceTest", "calculates", "java.lang.String");
		var coverage = result.fragment().tests().get(identity);
		assertEquals(TestOutcome.FAIL, coverage.outcome());
		assertEquals(CollectionStatus.COLLECTED_WITH_COVERAGE, coverage.collectionStatus());
		assertEquals(java.util.Set.of(schemaMethod("com.foo.Price", "<init>", "()V"),
				schemaMethod("com.foo.Price", "<clinit>", "()V"), schemaMethod("com.foo.Price", "calculate", "(I)I"),
				schemaMethod("com.foo.Price$Helper", "lambda$calculate$0", "()V")), coverage.coveredMethods());
		assertTrue(result.fragment().collectionCompleted());
		assertTrue(CoverageMapValidator.validate(result.fragment()).isValid());
	}

	@Test
	void parameterizedLogicalTestWithRunnableAndSkippedInvocationsRetainsCollectedCoverage() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		TestIdentity runnable = test("[test-template-invocation:#1]", "com.foo.BufferTest", "works", "com.foo.Factory");
		TestIdentity skipped = test("[test-template-invocation:#2]", "com.foo.BufferTest", "works", "com.foo.Factory");
		run(aggregator, runnable, TestExecutionStatus.SUCCESSFUL, method("com.foo.Buffer", "read", "()V"));
		run(aggregator, skipped, TestExecutionStatus.ABORTED);

		var result = project(aggregator, CollectorIntegrity.healthy());
		var identity = new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(
				"com.foo.BufferTest", "works", "com.foo.Factory");
		assertTrue(result.fragment().unmapped().isEmpty());
		assertEquals(Set.of(schemaMethod("com.foo.Buffer", "read", "()V")),
				result.fragment().tests().get(identity).coveredMethods());
		assertTrue(result.fragment().collectionCompleted());
	}

	@Test
	void supportsNestedAndLegalJvmTestNamesAndMultipleLogicalTests() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		run(aggregator, test("one", "com.foo.OuterTest$Inner", "works !?#", ""), TestExecutionStatus.SUCCESSFUL,
				method("com.foo.RecordValue", "value", "()Ljava/lang/String;"));
		run(aggregator, test("two", "com.foo.OtherTest", "enum case", ""), TestExecutionStatus.SUCCESSFUL,
				method("com.foo.Kind", "values", "()[Lcom/foo/Kind;"));
		var fragment = project(aggregator, CollectorIntegrity.healthy()).fragment();
		assertEquals(2, fragment.tests().size());
		assertTrue(fragment.tests().containsKey(new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(
				"com.foo.OuterTest$Inner", "works !?#")));
	}

	@Test
	void preservesBridgeSyntheticRecordEnumNestedAndLambdaJvmMethodsWithoutFiltering() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		run(aggregator, test("kinds", "com.foo.KindsTest", "calls", ""), TestExecutionStatus.SUCCESSFUL,
				method("com.foo.Generic", "value", "()Ljava/lang/Object;"),
				method("com.foo.Generic", "value", "()Ljava/lang/String;"),
				method("com.foo.RecordValue", "name", "()Ljava/lang/String;"),
				method("com.foo.Kind", "values", "()[Lcom/foo/Kind;"),
				method("com.foo.Outer$Inner", "lambda$run$0", "(I)V"),
				method("com.foo.Outer$Inner", "access$000", "()V"));
		var coverage = project(aggregator, CollectorIntegrity.healthy()).fragment().tests().values().iterator().next();
		assertEquals(6, coverage.coveredMethods().size());
		assertEquals(2, coverage.coveredMethods().stream().filter(method -> method.methodName().equals("value")).count());
	}

	@Test
	void successfulZeroCoverageIsCollectedEmpty() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		run(aggregator, test("empty", "com.foo.EmptyTest", "empty", ""), TestExecutionStatus.SUCCESSFUL);
		var coverage = project(aggregator, CollectorIntegrity.healthy()).fragment().tests().values().iterator().next();
		assertEquals(CollectionStatus.COLLECTED_EMPTY, coverage.collectionStatus());
	}

	@Test
	void criticalCollectorFailureMakesIdentifiableTestsUnmappedAndFragmentIncomplete() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		run(aggregator, test("broken", "com.foo.BrokenTest", "broken", ""), TestExecutionStatus.SUCCESSFUL,
				method("com.foo.Service", "run", "()V"));
		var result = project(aggregator, new CollectorIntegrity(true, 1, 0, List.of("transformation-error")));
		assertTrue(result.fragment().tests().isEmpty());
		assertEquals(UnmappedReason.COLLECTION_FAILED, result.fragment().unmapped().get(0).reason());
		assertFalse(result.fragment().collectionCompleted());
	}

	@Test
	void descriptorAwareSchemaPreservesOverloadsAsDistinctEdges() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		run(aggregator, test("overloads", "com.foo.OverloadTest", "calls", ""), TestExecutionStatus.SUCCESSFUL,
				method("com.foo.Service", "call", "(I)V"), method("com.foo.Service", "call", "(Ljava/lang/String;)V"));
		var result = project(aggregator, CollectorIntegrity.healthy());
		var coverage = result.fragment().tests().values().iterator().next();
		assertEquals(Set.of(schemaMethod("com.foo.Service", "call", "(I)V"),
				schemaMethod("com.foo.Service", "call", "(Ljava/lang/String;)V")), coverage.coveredMethods());
		assertTrue(result.fragment().collectionCompleted());
		assertTrue(result.diagnostics().isEmpty());
	}

	@Test
	void overloadedDeclaredTestsRemainDistinctLogicalTests() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		run(aggregator, test("a", "com.foo.OverloadedTest", "test", "int"), TestExecutionStatus.SUCCESSFUL);
		run(aggregator, test("b", "com.foo.OverloadedTest", "test", "java.lang.String"), TestExecutionStatus.SUCCESSFUL);
		var result = project(aggregator, CollectorIntegrity.healthy());
		assertTrue(result.fragment().collectionCompleted());
		assertEquals(2, result.fragment().tests().size());
		assertTrue(result.fragment().tests().containsKey(new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(
				"com.foo.OverloadedTest", "test", "int")));
		assertTrue(result.fragment().tests().containsKey(new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(
				"com.foo.OverloadedTest", "test", "java.lang.String")));
	}

	@Test
	void absentLogicalOwnershipIsNeverInvented() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		run(aggregator, test("dynamic", null, null, null), TestExecutionStatus.SUCCESSFUL,
				method("com.foo.Service", "run", "()V"));
		var result = project(aggregator, CollectorIntegrity.healthy());
		assertTrue(result.fragment().tests().isEmpty());
		assertTrue(result.fragment().unmapped().isEmpty());
		assertFalse(result.fragment().collectionCompleted());
		assertTrue(result.diagnostics().get(0).startsWith("unsupported-identity:"));
	}

	@Test
	void serializationIsByteDeterministicAndUsesExplicitRevisionAndShard() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		run(aggregator, test("det", "com.foo.DeterministicTest", "works", ""), TestExecutionStatus.SUCCESSFUL,
				method("com.foo.Zed", "z", "()V"), method("com.foo.Alpha", "a", "()V"));
		var fragment = project(aggregator, CollectorIntegrity.healthy()).fragment();
		CoverageFragmentCodec codec = new CoverageFragmentCodec();
		assertArrayEquals(codec.serialize(fragment), codec.serialize(project(aggregator, CollectorIntegrity.healthy()).fragment()));
		assertEquals("revision-24", fragment.revision().value());
		assertEquals("shard-07", fragment.shardId().value());
	}

	@Test
	void unsupportedSetupIsObservableAndPreventsLocalCompletionWithoutInventingEdges() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run", "jvm");
		run(aggregator, test("safe", "com.foo.SafeTest", "works", ""), TestExecutionStatus.SUCCESSFUL,
				method("com.foo.Service", "work", "()V"));
		aggregator.recordSetupDiagnostic(new SetupDiagnostic(SetupDiagnostic.Kind.SHARED_CONTEXT_SETUP_UNSUPPORTED,
				SetupDiagnostic.Severity.ERROR, new MethodIdentity("com.foo.Config", "load", "()V"), "no bounded container"));
		var result = project(aggregator, CollectorIntegrity.healthy());
		assertFalse(result.fragment().collectionCompleted());
		assertTrue(result.fragment().setupScopes().isEmpty());
		assertTrue(result.diagnostics().stream().anyMatch(value -> value.contains("SHARED_CONTEXT_SETUP_UNSUPPORTED")));
	}

	@Test
	void supportedSetupIsSemanticallyEqualAcrossOneAndMultipleCollectorContexts() {
		RuntimeEventAggregator single = new RuntimeEventAggregator("run", "one");
		single.recordSetup("com.foo.FirstTest", false, method("com.foo.Config", "first", "()V"));
		single.recordSetup("com.foo.SecondTest", false, method("com.foo.Config", "second", "()V"));
		RuntimeEventAggregator shardOne = new RuntimeEventAggregator("run", "a");
		shardOne.recordSetup("com.foo.FirstTest", false, method("com.foo.Config", "first", "()V"));
		RuntimeEventAggregator shardTwo = new RuntimeEventAggregator("run", "b");
		shardTwo.recordSetup("com.foo.SecondTest", false, method("com.foo.Config", "second", "()V"));
		var expected = project(single, CollectorIntegrity.healthy()).fragment().setupScopes();
		var combined = new java.util.ArrayList<com.sap.oss.smarttestpicker.coverage.model.SetupScope>();
		combined.addAll(project(shardOne, CollectorIntegrity.healthy()).fragment().setupScopes());
		combined.addAll(project(shardTwo, CollectorIntegrity.healthy()).fragment().setupScopes());
		combined.sort(java.util.Comparator.comparing(com.sap.oss.smarttestpicker.coverage.model.SetupScope::id));
		assertEquals(expected, combined);
		assertEquals(2, expected.size());
		assertTrue(expected.stream().allMatch(scope -> scope.affectedContainers().size() == 1));
	}

	private FragmentProjectionResult project(RuntimeEventAggregator aggregator, CollectorIntegrity integrity) {
		return projector.project(aggregator.snapshot(), FragmentProjectionConfig.of("revision-24", "shard-07"), integrity);
	}

	private static void run(RuntimeEventAggregator aggregator, TestIdentity test, TestExecutionStatus status,
			MethodHitEvent... methods) {
		aggregator.beginTest(test);
		for (MethodHitEvent method : methods) aggregator.record(test, method);
		aggregator.endTest(test, status == TestExecutionStatus.SUCCESSFUL ? TestResult.successful()
				: new TestResult(status, status == TestExecutionStatus.FAILED ? "failure" : null, null));
	}

	private static TestIdentity test(String id, String className, String methodName, String parameters) {
		return new TestIdentity(id, id, className, methodName, parameters, "junit-jupiter", "run", "jvm");
	}

	private static MethodHitEvent method(String className, String name, String descriptor) {
		return new MethodHitEvent(new MethodIdentity(className, name, descriptor), ASM);
	}

	private static com.sap.oss.smarttestpicker.coverage.model.MethodIdentity schemaMethod(String className,
			String name, String descriptor) {
		return new com.sap.oss.smarttestpicker.coverage.model.MethodIdentity(className, name, descriptor);
	}
}
