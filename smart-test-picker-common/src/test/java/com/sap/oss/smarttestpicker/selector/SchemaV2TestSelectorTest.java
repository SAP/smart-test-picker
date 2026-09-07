// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.SetupScopeType;
import com.sap.oss.smarttestpicker.coverage.model.TestContainer;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;

class SchemaV2TestSelectorTest
{
	private static final SchemaV2TestSelector SELECTOR = new SchemaV2TestSelector();
	private static final TestIdentity A = test("com.example.ATest", "a");
	private static final TestIdentity B = test("com.example.BTest", "b");
	private static final TestIdentity C = test("com.example.CTest", "c");

	@Test void selectsExactClassEdgesForPassAndFailWithDeterministicDeduplication()
	{
		Map<TestIdentity, TestCoverage> tests = Map.of(
				B, coverage(TestOutcome.FAIL, "com.example.Service", "com.example.Other"),
				A, coverage(TestOutcome.PASS, "com.example.Service"),
				C, coverage(TestOutcome.PASS, "com.example.ServiceExtra"));
		SelectionOutput output = select(map(tests, List.of(), List.of()), Set.of("com.example.Service"), Set.of(A, B, C));
		assertOutput(output, "SELECTED", List.of(A.toString(), B.toString()));
	}

	@Test void zeroEdgeAndCollectedEmptyDirectCoverageYieldNone()
	{
		CoverageMap map = map(Map.of(A, empty()), List.of(), List.of());
		assertOutput(select(map, Set.of("com.example.Service"), Set.of(A)), "NONE", List.of());
		assertOutput(select(map, Set.of("com.example.Unknown"), Set.of(A)), "NONE", List.of());
	}

	@Test void methodCoverageDoesNotCreateAnEdge()
	{
		TestCoverage methodOnlyForAnotherClass = new TestCoverage(Set.of("com.example.Other"),
				Set.of(new com.sap.oss.smarttestpicker.coverage.model.MethodIdentity(
						"com.example.Service", "work", "()V")), TestOutcome.PASS,
				CollectionStatus.COLLECTED_WITH_COVERAGE);
		assertOutput(select(map(Map.of(A, methodOnlyForAnotherClass), List.of(), List.of()),
				Set.of("com.example.Service"), Set.of(A)), "NONE", List.of());
	}

	@Test void setupExpansionIsExactBoundedSupportsAllTypesAndDeduplicates()
	{
		TestIdentity nested = test("com.example.OuterTest$Nested", "case");
		TestIdentity parent = test("com.example.OuterTest", "parent");
		List<SetupScope> scopes = List.of(
				scope("container", SetupScopeType.CONTAINER, "com.example.Config", "com.example.ATest", "com.example.BTest"),
				scope("nested", SetupScopeType.NESTED_CONTAINER, "com.example.Config", "com.example.OuterTest$Nested"),
				scope("reserved", SetupScopeType.SHARED_CONTEXT, "com.example.Config", "com.example.ATest"),
				scope("irrelevant", SetupScopeType.FRAMEWORK_SETUP, "com.example.Other", "com.example.OuterTest"));
		CoverageMap map = map(Map.of(A, coverage(TestOutcome.PASS, "com.example.Config"), B, empty()), List.of(), scopes);
		SelectionOutput output = select(map, Set.of("com.example.Config"), Set.of(parent, nested, A, B));
		assertOutput(output, "SELECTED", List.of(A.toString(), B.toString(), nested.toString()));
	}

	@Test void setupCanSelectUnmappedNewChangedAndCollectedEmptyHeadTests()
	{
		TestIdentity unmapped = test("com.example.ScopeTest", "unmapped");
		TestIdentity fresh = test("com.example.ScopeTest", "new");
		TestIdentity changed = test("com.example.ScopeTest", "changed");
		CoverageMap map = map(Map.of(A, empty()), List.of(new UnmappedTest(unmapped, UnmappedReason.FAILED)),
				List.of(scope("s", SetupScopeType.INHERITED_SETUP, "com.example.Config", "com.example.ScopeTest")));
		SelectionOutput output = SELECTOR.select(context(map, Set.of("com.example.Config"), Set.of(A, unmapped, fresh, changed),
				Set.of(fresh), Set.of(), Set.of(changed)));
		assertOutput(output, "SELECTED", List.of(changed.toString(), fresh.toString(), unmapped.toString()));
	}

	@Test void absentAffectedContainerFailsOpenWithoutPartialSelection()
	{
		CoverageMap map = map(Map.of(A, coverage(TestOutcome.PASS, "com.example.Config")), List.of(),
				List.of(scope("bad", SetupScopeType.CONTAINER, "com.example.Config", "com.example.MissingTest")));
		assertOutput(select(map, Set.of("com.example.Config"), Set.of(A)), "FULL_SUITE", List.of());
	}

	@Test void everyUnmappedReasonIsAlwaysSelectedAtHeadAndDeletedOtherwise()
	{
		for (UnmappedReason reason : UnmappedReason.values())
		{
			TestIdentity identity = test("com.example." + reason + "Test", "case");
			CoverageMap map = map(Map.of(), List.of(new UnmappedTest(identity, reason)), List.of());
			SelectionOutput selected = select(map, Set.of(), Set.of(identity));
			assertOutput(selected, "SELECTED", List.of(identity.toString()));
			assertEquals(reason.name(), selected.getUnmappedTests().get(identity.toString()));
			assertOutput(SELECTOR.select(context(map, Set.of(), Set.of(), Set.of(), Set.of(identity), Set.of())), "NONE", List.of());
		}
	}

	@Test void unionsNewChangedAndExcludesDeletedIncludingRenameAndMappedHeadIntersection()
	{
		TestIdentity old = test("com.example.OldTest", "case");
		TestIdentity fresh = test("com.example.NewTest", "case");
		TestIdentity overloaded = new TestIdentity("com.example.NewTest", "case", "java.lang.String");
		TestIdentity parameterized = new TestIdentity("com.example.ParamTest", "declared", "int");
		CoverageMap map = map(Map.of(old, coverage(TestOutcome.PASS, "com.example.Service")), List.of(), List.of());
		SelectionOutput output = SELECTOR.select(context(map, Set.of("com.example.Service"), Set.of(fresh, overloaded, parameterized),
				Set.of(fresh, overloaded), Set.of(old), Set.of(parameterized)));
		assertOutput(output, "SELECTED", List.of(fresh.toString(), overloaded.toString(), parameterized.toString()));
	}

	@Test void newOnlyAndChangedOnlyAreSelectedAndSafeEmptyIsNone()
	{
		CoverageMap empty = map(Map.of(), List.of(), List.of());
		assertOutput(SELECTOR.select(context(empty, Set.of(), Set.of(A), Set.of(A), Set.of(), Set.of())), "SELECTED", List.of(A.toString()));
		assertOutput(SELECTOR.select(context(empty, Set.of(), Set.of(B), Set.of(), Set.of(), Set.of(B))), "SELECTED", List.of(B.toString()));
		assertOutput(select(empty, Set.of(), Set.of()), "NONE", List.of());
	}

	@Test void analysisRunAllPropagatesAndNullContextFailsOpen()
	{
		assertOutput(SELECTOR.select(SelectionAnalysisResult.runAll("unsafe ingress")), "FULL_SUITE", List.of());
		assertEquals("unsafe ingress", SELECTOR.select(SelectionAnalysisResult.runAll("unsafe ingress")).getReason());
		assertOutput(SELECTOR.select((SelectionContext) null), "FULL_SUITE", List.of());
	}

	private static SelectionOutput select(CoverageMap map, Set<String> changed, Set<TestIdentity> head)
	{
		return SELECTOR.select(context(map, changed, head, Set.of(), Set.of(), Set.of()));
	}

	private static SelectionContext context(CoverageMap map, Set<String> changed, Set<TestIdentity> head,
			Set<TestIdentity> added, Set<TestIdentity> deleted, Set<TestIdentity> modified)
	{
		return new SelectionContext(map, map.revision(), "head", changed, Set.of(), head, added, deleted, modified);
	}

	private static CoverageMap map(Map<TestIdentity, TestCoverage> tests, List<UnmappedTest> unmapped, List<SetupScope> scopes)
	{
		return new CoverageMap(2, new CoverageMapRevision("revision"), Instant.EPOCH, null, tests, unmapped, scopes,
				null, null, CoverageMapLifecycleState.PUBLISHED, null);
	}

	private static TestCoverage coverage(TestOutcome outcome, String... classes)
	{
		return new TestCoverage(Set.of(classes), Set.of(), outcome, CollectionStatus.COLLECTED_WITH_COVERAGE);
	}

	private static TestCoverage empty()
	{
		return new TestCoverage(Set.of(), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_EMPTY);
	}

	private static SetupScope scope(String id, SetupScopeType type, String coveredClass, String... containers)
	{
		Set<TestContainer> affected = java.util.Arrays.stream(containers).map(TestContainer::new).collect(java.util.stream.Collectors.toSet());
		return new SetupScope(id, type, Set.of(coveredClass), affected);
	}

	private static TestIdentity test(String className, String method) { return new TestIdentity(className, method); }
	private static void assertOutput(SelectionOutput output, String status, List<String> selected)
	{
		assertEquals(status, output.getStatus());
		assertEquals(selected, output.getSelectedTests());
	}
}
