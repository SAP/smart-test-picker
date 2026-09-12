// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentQualifier;
import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;

class ExecutableCoverageFragmentQualifierTest {
	private static final TestIdentity SONAR = new TestIdentity(
			"org.sonar.java.checks.helpers.ReassignmentFinderTest", "parameter_with_usage");
	private final ExecutableCoverageFragmentQualifier qualifier = new ExecutableCoverageFragmentQualifier();

	@Test
	void qualifiesMappedAndUnmappedFactsForMavenAndGradleWithoutUnioningCoverage() {
		CoverageFragment logicalA = fragment(Map.of(SONAR, covered("ClassA", "ClassB")), List.of());
		CoverageFragment logicalB = fragment(Map.of(SONAR, covered("ClassA", "ClassC")),
				List.of(new UnmappedTest(new TestIdentity("example.Skipped", "test"), UnmappedReason.SKIPPED)));
		ExecutionTarget common = ExecutionTarget.parse("maven:java-checks-common");
		ExecutionTarget checks = ExecutionTarget.parse("maven:java-checks");
		ExecutableCoverageFragment a = qualifier.qualify(logicalA, common);
		ExecutableCoverageFragment b = qualifier.qualify(logicalB, checks);
		ExecutableTestIdentity aId = a.tests().keySet().iterator().next();
		ExecutableTestIdentity bId = b.tests().keySet().iterator().next();

		assertEquals(aId.test(), bId.test());
		assertNotEquals(aId, bId);
		assertEquals("maven:java-checks-common::" + SONAR, aId.toString());
		assertEquals("maven:java-checks::" + SONAR, bId.toString());
		assertEquals(Set.of("ClassA", "ClassB"), a.tests().get(aId).coveredClasses());
		assertEquals(Set.of("ClassA", "ClassC"), b.tests().get(bId).coveredClasses());
		assertEquals(checks, b.unmapped().get(0).test().target());

		var gradle = qualifier.qualify(logicalA, ExecutionTarget.parse("gradle::spring-core:test"));
		assertNotEquals(aId, gradle.tests().keySet().iterator().next());
	}

	@Test
	void sameInputsAreStableAndRuntimeProducedFragmentRoundTripsExactly() {
		ExecutionTarget target = ExecutionTarget.parse("maven:java-checks");
		ExecutableCoverageFragment first = qualifier.qualify(fragment(Map.of(SONAR, covered("ClassA")), List.of()), target);
		ExecutableCoverageFragment second = qualifier.qualify(fragment(Map.of(SONAR, covered("ClassA")), List.of()), target);
		assertEquals(first.tests().keySet(), second.tests().keySet());
		byte[] bytes = new ExecutableCoverageFragmentCodec().serialize(first);
		ExecutableCoverageFragment decoded = new ExecutableCoverageFragmentCodec().deserialize(bytes);
		assertEquals(3, decoded.schemaVersion());
		assertEquals("revision-exact", decoded.revision().value());
		assertEquals("shard-exact", decoded.shardId().value());
		assertEquals(first, decoded);
		assertTrue(decoded.collectionCompleted());
	}

	@Test
	void configurationFailsClosedAndMismatchHasFocusedDiagnostic() {
		assertEquals(2, FragmentProjectionConfig.v2("r", "s").schemaVersion());
		assertTrue(assertThrows(IllegalArgumentException.class,
				() -> FragmentProjectionConfig.v3("r", "s", null)).getMessage().contains("requires an execution target"));
		for (String malformed : List.of("maven:/absolute", "maven:../module", "gradle:spring-core:test", "unknown:module"))
			assertTrue(assertThrows(IllegalArgumentException.class,
					() -> FragmentProjectionConfig.v3("r", "s", malformed)).getMessage().contains("Malformed execution target"));

		var executable = qualifier.qualify(fragment(Map.of(SONAR, covered("ClassA")), List.of()),
				ExecutionTarget.parse("maven:module-b"));
		assertTrue(assertThrows(IllegalArgumentException.class,
				() -> qualifier.requireTarget(executable, ExecutionTarget.parse("maven:module-a")))
				.getMessage().contains("Execution target mismatch"));
	}

	private static CoverageFragment fragment(Map<TestIdentity, TestCoverage> mapped, List<UnmappedTest> unmapped) {
		return new CoverageFragment(2, new CoverageMapRevision("revision-exact"), new ShardId("shard-exact"),
				mapped, unmapped, List.of(), true);
	}

	private static TestCoverage covered(String... classes) {
		return new TestCoverage(Set.of(classes), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_WITH_COVERAGE);
	}
}
