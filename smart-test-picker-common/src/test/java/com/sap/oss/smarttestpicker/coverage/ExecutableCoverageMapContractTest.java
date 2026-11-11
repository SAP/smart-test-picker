// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCollectionExpectation;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCollectionSummary;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCompleteness;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidationException;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableCoverageMapValidator;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutableCoverageMapContractTest
{
	private static final CoverageMapRevision REVISION = new CoverageMapRevision("abc123");
	private static final ShardId SHARD = new ShardId("01");
	private static final TestIdentity LOGICAL = TestIdentity.parse(
			"org.sonar.java.checks.ReassignmentFinderTest#parameter_with_usage");
	private static final ExecutableTestIdentity COMMON = executable("java-checks-common", LOGICAL);
	private static final ExecutableTestIdentity CHECKS = executable("java-checks", LOGICAL);

	@Test void sonarJavaDualOwnerCoverageRoundTripsWithoutUnion()
	{
		ExecutableCoverageMap original = map(Map.of(
				COMMON, covered("ClassA", "ClassB"), CHECKS, covered("ClassA", "ClassC")), List.of(),
				complete(Set.of(COMMON, CHECKS)));
		ExecutableCoverageMapCodec codec = new ExecutableCoverageMapCodec();
		byte[] bytes = codec.serialize(original); ExecutableCoverageMap decoded = codec.deserialize(bytes);
		assertArrayEquals(bytes, codec.serialize(decoded));
		assertEquals(2, decoded.tests().size());
		assertEquals(COMMON.test(), CHECKS.test());
		assertEquals(Set.of("ClassA", "ClassB"), decoded.tests().get(COMMON).coveredClasses());
		assertEquals(Set.of("ClassA", "ClassC"), decoded.tests().get(CHECKS).coveredClasses());
		assertNotEquals(decoded.tests().get(COMMON), decoded.tests().get(CHECKS));
	}

	@Test void completenessDetectsMissingExecutableOwnerAndAcceptsPositiveNonExecution()
	{
		ExecutableTestInventory inventory = new ExecutableTestInventory(REVISION, Set.of(COMMON, CHECKS));
		ExecutableCompleteness missing = ExecutableCompleteness.from(
				new ExecutableCollectionExpectation(inventory, Set.of(SHARD)),
				new ExecutableCollectionSummary(Set.of(COMMON), List.of(SHARD), Set.of()));
		assertEquals(Set.of(CHECKS), missing.missingTests()); assertFalse(missing.isComplete());
		ExecutableCompleteness accounted = ExecutableCompleteness.from(
				new ExecutableCollectionExpectation(inventory, Set.of(SHARD), Set.of(CHECKS)),
				new ExecutableCollectionSummary(Set.of(COMMON), List.of(SHARD), Set.of()));
		assertTrue(accounted.isComplete());
	}

	@Test void inventoryAllowsSameLogicalAcrossTargetsButRejectsSameExecutableDuplicate()
	{
		assertEquals(2, ExecutableTestInventory.from(REVISION, List.of(COMMON, CHECKS)).expectedTests().size());
		assertThrows(IllegalArgumentException.class,
				() -> ExecutableTestInventory.from(REVISION, List.of(COMMON, COMMON)));
		assertThrows(IllegalArgumentException.class,
				() -> ExecutableTestInventory.from(REVISION, java.util.Arrays.asList(COMMON, null)));
	}

	@Test void fragmentKeepsOwnersSeparateAndValidatesDisjointness()
	{
		ExecutableCoverageFragment fragment = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3,
				REVISION, SHARD, Map.of(COMMON, covered("ClassA"), CHECKS, covered("ClassB")), List.of(), List.of(), true);
		ExecutableCoverageFragmentCodec codec = new ExecutableCoverageFragmentCodec();
		assertEquals(fragment.tests(), codec.deserialize(codec.serialize(fragment)).tests());
		ExecutableCoverageFragment overlap = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3,
				REVISION, SHARD, Map.of(COMMON, covered("ClassA")),
				List.of(new ExecutableUnmappedTest(COMMON, UnmappedReason.FAILED)), List.of(), true);
		assertTrue(ExecutableCoverageMapValidator.validate(overlap).has(ValidationCode.TEST_MAPPED_AND_UNMAPPED));
	}

	@Test void duplicateExecutableIdentityOnWireIsRejected()
	{
		String identity = "maven:java-checks::com.example.Test#works";
		String coverage = "{\"classes\":[\"ClassA\"],\"methods\":[],\"outcome\":\"PASS\","
				+ "\"collectionStatus\":\"COLLECTED_WITH_COVERAGE\"}";
		String json = "{\"schemaVersion\":3,\"revision\":\"abc123\",\"shardId\":\"01\",\"tests\":{"
				+ "\"" + identity + "\":" + coverage + ",\"" + identity + "\":" + coverage
				+ "},\"unmapped\":[],\"setupScopes\":[],\"collection\":{\"completed\":true}}";
		assertThrows(IllegalArgumentException.class, () -> new ExecutableCoverageFragmentCodec()
				.deserialize(json.getBytes(StandardCharsets.UTF_8)));
	}

	@Test void ownerlessSchemaV3SetupScopeIsRejectedInsteadOfGuessed()
	{
		String json = "{\"schemaVersion\":3,\"revision\":\"abc123\",\"shardId\":\"01\","
				+ "\"tests\":{},\"unmapped\":[],\"setupScopes\":[{\"id\":\"scope\",\"type\":\"CONTAINER\","
				+ "\"coveredClasses\":[\"ProductionA\"],\"affectedContainers\":[\"example.SharedTest\"]}],"
				+ "\"collection\":{\"completed\":true}}";
		assertThrows(IllegalArgumentException.class,
				() -> new ExecutableCoverageFragmentCodec().deserialize(json.getBytes(StandardCharsets.UTF_8)));
	}

	@Test void v2AndV3ReadersRejectTheOtherVersion()
	{
		byte[] v3Map = new ExecutableCoverageMapCodec().serialize(map(Map.of(COMMON, covered("ClassA")), List.of(), complete(Set.of(COMMON))));
		assertThrows(CoverageMapValidationException.class, () -> new CoverageMapCodec().deserialize(v3Map));
		String v2Map = new String(v3Map, StandardCharsets.UTF_8).replace("\"schemaVersion\":3", "\"schemaVersion\":2");
		assertThrows(CoverageMapValidationException.class,
				() -> new ExecutableCoverageMapCodec().deserialize(v2Map.getBytes(StandardCharsets.UTF_8)));
		ExecutableCoverageFragment fragment = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3,
				REVISION, SHARD, Map.of(COMMON, covered("ClassA")), List.of(), List.of(), true);
		byte[] v3Fragment = new ExecutableCoverageFragmentCodec().serialize(fragment);
		assertThrows(CoverageMapValidationException.class, () -> new CoverageFragmentCodec().deserialize(v3Fragment));
		String v2Fragment = new String(v3Fragment, StandardCharsets.UTF_8).replace("\"schemaVersion\":3", "\"schemaVersion\":2");
		assertThrows(CoverageMapValidationException.class,
				() -> new ExecutableCoverageFragmentCodec().deserialize(v2Fragment.getBytes(StandardCharsets.UTF_8)));
	}

	@Test void publicationRequiresExactExecutableAccounting()
	{
		ExecutableCoverageMap source = map(Map.of(COMMON, covered("ClassA")), List.of(), complete(Set.of(COMMON)));
		ExecutableTestInventory inventory = new ExecutableTestInventory(REVISION, Set.of(COMMON, CHECKS));
		assertTrue(ExecutableCoverageMapPublication.alignExpectedInventory(source, inventory, Set.of(CHECKS))
				.completeness().isComplete());
		assertThrows(IllegalArgumentException.class,
				() -> ExecutableCoverageMapPublication.alignExpectedInventory(source, inventory, Set.of()));
	}

	private static ExecutableCoverageMap map(Map<ExecutableTestIdentity, TestCoverage> tests,
			List<ExecutableUnmappedTest> unmapped, ExecutableCompleteness completeness)
	{
		return new ExecutableCoverageMap(CoverageMapContract.SCHEMA_V3, REVISION, Instant.parse("2026-01-01T00:00:00Z"),
				new GeneratorProvenance("1", "1", "1", "17"), tests, unmapped, List.of(), completeness,
				new MapStatistics(completeness.expectedTests().size(), tests.size(), unmapped.size(), 0,
						tests.values().stream().mapToLong(value -> value.coveredClasses().size()).sum(), 0),
				CoverageMapLifecycleState.PUBLISHED, null);
	}
	private static ExecutableCompleteness complete(Set<ExecutableTestIdentity> tests)
	{
		ExecutableTestInventory inventory = new ExecutableTestInventory(REVISION, tests);
		return ExecutableCompleteness.from(new ExecutableCollectionExpectation(inventory, Set.of(SHARD)),
				new ExecutableCollectionSummary(tests, List.of(SHARD), Set.of()));
	}
	private static ExecutableTestIdentity executable(String module, TestIdentity test)
	{
		return new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, module), test);
	}
	private static TestCoverage covered(String... classes)
	{
		return new TestCoverage(Set.of(classes), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_WITH_COVERAGE);
	}
}
