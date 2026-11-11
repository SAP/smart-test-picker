// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CollectionSummary;
import com.sap.oss.smarttestpicker.coverage.model.CollectionExpectation;
import com.sap.oss.smarttestpicker.coverage.model.Completeness;
import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.MethodCoverageReference;
import com.sap.oss.smarttestpicker.coverage.model.MethodIdentity;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.SetupScopeType;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestContainer;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestInventory;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidationException;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidator;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCategory;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoverageMapContractTest
{
	private static final TestIdentity FOO = TestIdentity.parse("com.foo.FooTest#a");
	private static final TestIdentity BAR = TestIdentity.parse("com.foo.BarTest#b");
	private static final TestIdentity BAZ = TestIdentity.parse("com.foo.OuterTest$Inner#nestedTest");

	@Test void contractVersionsHaveOneCoreAuthority()
	{
		assertEquals(2, CoverageMapContract.SCHEMA_VERSION);
		assertEquals(1, ExecutionPlanContract.VERSION);
	}

	@Test void testIdentityUsesFqnAndBinaryNestedName()
	{
		assertEquals("com.foo.OuterTest$Inner#nestedTest", BAZ.toString());
		assertThrows(IllegalArgumentException.class, () -> TestIdentity.parse("FooTest#test"));
	}

	@Test void methodIdentityValidatesParsesSortsAndKeepsOverloadsDistinct()
	{
		MethodIdentity noArgs = method("com.foo.Service#doIt()V");
		MethodIdentity stringArg = method("com.foo.Service#doIt(Ljava/lang/String;)V");
		assertEquals("com.foo.Service#doIt()V", noArgs.toString());
		assertEquals(2, new java.util.TreeSet<>(Set.of(noArgs, stringArg)).size());
		assertEquals(method("com.foo.Type#<init>(Ljava/lang/String;)V"),
				new MethodIdentity("com.foo.Type", "<init>", "(Ljava/lang/String;)V"));
		assertEquals(method("com.foo.Type#<clinit>()V"), new MethodIdentity("com.foo.Type", "<clinit>", "()V"));
		for (String malformed : List.of("com.foo.Service#doIt", "com.foo.Service#doIt(V)V",
				"com.foo.Service#doIt(Ljava.lang.String;)V", "com.foo.Service#bad/name()V"))
			assertThrows(IllegalArgumentException.class, () -> MethodIdentity.parse(malformed));
	}

	@Test void testIdentityLosslesslySupportsJvmAndKotlinMethodNames()
	{
		List<String> names = List.of(
				"testSomething",
				"Register reflection hints for Kotlin data class",
				"multiple  spaces",
				"punctuation!?+-=():,'\"@%^&*|~`{}<>",
				"foo#bar");
		for (String name : names)
		{
			TestIdentity identity = new TestIdentity("com.foo.MyTests", name);
			assertEquals(identity, TestIdentity.parse(identity.toString()));
			assertEquals(identity.hashCode(), TestIdentity.parse(identity.toString()).hashCode());
		}

		TestIdentity springCore = TestIdentity.parse("org.springframework.aot.hint.BindingReflectionHintsRegistrarKotlinTests#Register reflection hints for Kotlin data class");
		assertEquals("Register reflection hints for Kotlin data class", springCore.methodName());
		assertEquals(2, Set.of(new TestIdentity("com.foo.MyTests", "foo bar"),
				new TestIdentity("com.foo.MyTests", "foo_bar")).size());
		assertThrows(IllegalArgumentException.class, () -> new TestIdentity("com.foo.MyTests", ""));
		assertThrows(IllegalArgumentException.class, () -> new TestIdentity("com.foo.MyTests", "line\nbreak"));
		for (String forbidden : List.of("has.dot", "has;semicolon", "has[bracket", "has/slash"))
			assertThrows(IllegalArgumentException.class, () -> new TestIdentity("com.foo.MyTests", forbidden));
	}

	@Test void extendedTestIdentitiesRoundTripThroughFragmentAndCoverageMap()
	{
		TestIdentity kotlin = new TestIdentity("com.foo.MyTests", "Register reflection hints for Kotlin data class");
		TestIdentity punctuation = new TestIdentity("com.foo.OuterTests$Nested", "foo#bar !?");
		Map<TestIdentity, TestCoverage> tests = Map.of(kotlin, covered("com.foo.Service"),
				punctuation, covered("com.foo.Other"));

		CoverageFragment fragment = new CoverageFragment(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision("abc123"),
				new ShardId("0"), tests, List.of(), List.of(), true);
		CoverageFragment decodedFragment = new CoverageFragmentCodec().deserialize(
				new CoverageFragmentCodec().serialize(fragment));
		assertEquals(tests, decodedFragment.tests());

		CoverageMap original = map(tests, List.of(), List.of());
		CoverageMapCodec codec = new CoverageMapCodec();
		byte[] serialized = codec.serialize(original);
		CoverageMap decodedMap = codec.deserialize(serialized);
		assertEquals(tests, decodedMap.tests());
		assertArrayEquals(serialized, codec.serialize(decodedMap));
	}

	@Test void constructedMapIsImmutable()
	{
		Map<TestIdentity, TestCoverage> mutable = new HashMap<>(); mutable.put(FOO, covered("com.foo.Service"));
		CoverageMap map = map(mutable, List.of(), List.of()); mutable.clear();
		assertEquals(1, map.tests().size()); assertThrows(UnsupportedOperationException.class, () -> map.tests().clear());
	}

	@Test void deterministicSerializationRoundTripAndChecksum()
	{
		CoverageMapCodec codec = new CoverageMapCodec(); CoverageMap first = sampleMap();
		byte[] one = codec.serialize(first); byte[] two = codec.serialize(first);
		assertArrayEquals(one, two);
		assertEquals(checksum(one), checksum(two));
		CoverageMap decoded = codec.deserialize(one);
		assertArrayEquals(one, codec.serialize(decoded));
		assertEquals(first.tests(), decoded.tests());
		JsonObject json = JsonParser.parseString(new String(one, StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(List.of("com.foo.BarTest#b", "com.foo.FooTest#a"), json.getAsJsonArray("testIndex").asList().stream().map(e -> e.getAsString()).toList());
	}

	@Test void candidateWithoutIndependentInventoryRoundTripsButIsNotPublishable()
	{
		CoverageMap published = sampleMap();
		CoverageMap candidate = new CoverageMap(published.schemaVersion(), published.revision(), published.generatedAt(),
				published.generator(), published.tests(), published.unmapped(), published.setupScopes(), null,
				published.statistics(), CoverageMapLifecycleState.CANDIDATE, null);
		CoverageMapCodec codec = new CoverageMapCodec();
		CoverageMap decoded = codec.deserialize(codec.serialize(candidate));
		assertNull(decoded.completeness());
		assertEquals(CoverageMapLifecycleState.CANDIDATE, decoded.lifecycleState());
		assertTrue(CoverageMapValidator.validate(decoded).has(ValidationCode.LIFECYCLE_NOT_PUBLISHED));
	}

	@Test void checksumChangesWithSemanticContentAndRejectsCorruption()
	{
		CoverageMapCodec codec = new CoverageMapCodec(); byte[] original = codec.serialize(sampleMap());
		byte[] changed = codec.serialize(map(Map.of(FOO, covered("com.foo.Different")), List.of(new UnmappedTest(BAR, UnmappedReason.FAILED)), List.of()));
		assertNotEquals(checksum(original), checksum(changed));
		String corrupt = new String(original, StandardCharsets.UTF_8).replace("com.foo.Service", "com.foo.ServiceX");
		CoverageMapValidationException error = assertThrows(CoverageMapValidationException.class, () -> codec.deserialize(corrupt.getBytes(StandardCharsets.UTF_8)));
		assertEquals(ValidationCode.CHECKSUM_MISMATCH, error.getError().code()); assertEquals(ValidationCategory.CORRUPT_MAP, error.getError().category());
	}

	@Test void higherSchemaFailsBeforePartialParsing()
	{
		byte[] bytes = new CoverageMapCodec().serialize(sampleMap()); String json = new String(bytes, StandardCharsets.UTF_8).replace("\"schemaVersion\":2", "\"schemaVersion\":3");
		CoverageMapValidationException error = assertThrows(CoverageMapValidationException.class, () -> new CoverageMapCodec().deserialize(json.getBytes(StandardCharsets.UTF_8)));
		assertEquals(ValidationCode.HIGHER_SCHEMA_VERSION, error.getError().code()); assertEquals(ValidationCategory.INCOMPATIBLE_SCHEMA, error.getError().category());
	}

	@Test void validationCategoriesSeparateSchemaStructureAndCompleteness()
	{
		CoverageMap base = sampleMap();
		CoverageMap oldSchema = new CoverageMap(0, base.revision(), base.generatedAt(), base.generator(), base.tests(), base.unmapped(), base.setupScopes(), base.completeness(), base.statistics(), base.lifecycleState(), null);
		assertEquals(ValidationCategory.INCOMPATIBLE_SCHEMA, CoverageMapValidator.validate(oldSchema).errors().get(0).category());
		CoverageMap missingRevision = new CoverageMap(1, null, base.generatedAt(), base.generator(), base.tests(), base.unmapped(), base.setupScopes(), base.completeness(), base.statistics(), base.lifecycleState(), null);
		assertTrue(CoverageMapValidator.validate(missingRevision).has(ValidationCode.MISSING_REVISION));
		Completeness incomplete = Completeness.from(expectation(Set.of(FOO, BAR), Set.of(new ShardId("01"))),
				new CollectionSummary(Set.of(FOO), List.of(new ShardId("01")), Set.of()));
		assertEquals(ValidationCategory.INCOMPLETE_MAP, CoverageMapValidator.validate(withCompleteness(incomplete)).errors().stream().filter(e -> e.code() == ValidationCode.MISSING_TEST).findFirst().orElseThrow().category());
	}

	@Test void setupScopesSupportOneManyAndOverlap()
	{
		SetupScope one = scope("one", Set.of(new TestContainer("com.foo.FooTest")));
		SetupScope many = scope("many", Set.of(new TestContainer("com.foo.FooTest"), new TestContainer("com.foo.BarTest")));
		CoverageMap decoded = new CoverageMapCodec().deserialize(new CoverageMapCodec().serialize(map(Map.of(FOO, covered("com.foo.Service")), List.of(new UnmappedTest(BAR, UnmappedReason.SKIPPED)), List.of(one, many))));
		assertEquals(2, decoded.setupScopes().size()); assertEquals(2, decoded.setupScopes().get(0).affectedContainers().size());
		assertThrows(IllegalArgumentException.class, () -> scope("empty", Set.of()));
	}

	@Test void duplicateSetupScopeIdIsRejected()
	{
		SetupScope scope = scope("same", Set.of(new TestContainer("com.foo.FooTest")));
		CoverageMap invalid = map(Map.of(FOO, covered("com.foo.Service")), List.of(new UnmappedTest(BAR, UnmappedReason.FAILED)), List.of(scope, scope));
		assertTrue(CoverageMapValidator.validate(invalid).has(ValidationCode.DUPLICATE_SETUP_SCOPE_ID));
	}

	@Test void mappedAndUnmappedOverlapIsRejectedAndAllReasonsRoundTrip()
	{
		for (UnmappedReason reason : UnmappedReason.values())
		{
			CoverageMap decoded = new CoverageMapCodec().deserialize(new CoverageMapCodec().serialize(map(Map.of(FOO, covered("com.foo.Service")), List.of(new UnmappedTest(BAR, reason)), List.of())));
			assertEquals(reason, decoded.unmapped().get(0).reason()); assertFalse(decoded.tests().containsKey(BAR));
		}
		CoverageMap overlap = map(Map.of(FOO, covered("com.foo.Service"), BAR, covered("com.foo.Other")), List.of(new UnmappedTest(BAR, UnmappedReason.FAILED)), List.of());
		assertTrue(CoverageMapValidator.validate(overlap).has(ValidationCode.TEST_MAPPED_AND_UNMAPPED));
	}

	@Test void completenessUsesIdentitiesNotCountsAndUnmappedCountsAsReported()
	{
		CollectionExpectation expectation = expectation(Set.of(FOO, BAR), Set.of(new ShardId("01")));
		Completeness complete = Completeness.from(expectation, new CollectionSummary(Set.of(FOO, BAR), List.of(new ShardId("01")), Set.of()));
		assertTrue(complete.isComplete());
		Completeness sameCountDifferentMembers = Completeness.from(expectation, new CollectionSummary(Set.of(FOO, BAZ), List.of(new ShardId("01")), Set.of()));
		assertFalse(sameCountDifferentMembers.isComplete()); assertEquals(Set.of(BAR), sameCountDifferentMembers.missingTests()); assertEquals(Set.of(BAZ), sameCountDifferentMembers.unexpectedTests());
	}

	@Test void orchestrationCanAccountForKnownNonExecutableDeclarationsWithoutMakingThemUnmapped()
	{
		CollectionExpectation expectation = new CollectionExpectation(
				new TestInventory(new CoverageMapRevision("abc123"), Set.of(FOO, BAR)),
				Set.of(new ShardId("01")), Set.of(BAR));
		Completeness completeness = Completeness.from(expectation,
				new CollectionSummary(Set.of(FOO), List.of(new ShardId("01")), Set.of()));
		assertTrue(completeness.isComplete());
		assertEquals(Set.of(FOO, BAR), completeness.reportedTests());
		assertThrows(IllegalArgumentException.class, () -> new CollectionExpectation(
				new TestInventory(new CoverageMapRevision("abc123"), Set.of(FOO)), Set.of(), Set.of(BAR)));
	}

	@Test void missingDuplicateAndUnexpectedShardsAreDistinguished()
	{
		CollectionExpectation expectation = expectation(Set.of(FOO), Set.of(new ShardId("01"), new ShardId("02")));
		Completeness value = Completeness.from(expectation, new CollectionSummary(Set.of(FOO),
				List.of(new ShardId("01"), new ShardId("01"), new ShardId("99")), Set.of(FOO)));
		assertEquals(Set.of(new ShardId("02")), value.missingShards()); assertEquals(Set.of(new ShardId("01")), value.duplicateShards()); assertEquals(Set.of(FOO), value.duplicateTests());
		CoverageMap invalid = withCompleteness(value);
		assertTrue(CoverageMapValidator.validate(invalid).has(ValidationCode.UNEXPECTED_COMPLETED_SHARD));
	}

	@Test void collectorCannotHideAnExpectedShard()
	{
		CollectionExpectation realExpectation = expectation(Set.of(FOO),
				Set.of(new ShardId("A"), new ShardId("B"), new ShardId("C")));
		CollectionSummary internallyConsistentCollectorOutput = new CollectionSummary(Set.of(FOO),
				List.of(new ShardId("A"), new ShardId("B")), Set.of());
		Completeness completeness = Completeness.from(realExpectation, internallyConsistentCollectorOutput);
		assertFalse(completeness.isComplete());
		assertEquals(Set.of(new ShardId("C")), completeness.missingShards());
	}

	@Test void collectionStatusesAreDistinct()
	{
		assertDoesNotThrow(() -> new TestCoverage(Set.of(), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTED_EMPTY));
		assertThrows(IllegalArgumentException.class, () -> new TestCoverage(Set.of(), Set.of(), TestOutcome.PASS, CollectionStatus.COLLECTION_FAILED));
	}

	@Test void failedTestOutcomeDoesNotDiscardValidCoverageOrChangeCompleteness()
	{
		TestCoverage failedWithCoverage = new TestCoverage(Set.of("com.foo.Service"),
				Set.of(method("com.foo.Service#run()V")), TestOutcome.FAIL, CollectionStatus.COLLECTED_WITH_COVERAGE);
		TestCoverage failedWithEmptyCollection = new TestCoverage(Set.of(), Set.of(), TestOutcome.FAIL,
				CollectionStatus.COLLECTED_EMPTY);
		CoverageMap roundTripped = new CoverageMapCodec().deserialize(new CoverageMapCodec().serialize(
				map(Map.of(FOO, failedWithCoverage, BAR, failedWithEmptyCollection), List.of(), List.of())));
		assertEquals(TestOutcome.FAIL, roundTripped.tests().get(FOO).outcome());
		assertEquals(CollectionStatus.COLLECTED_WITH_COVERAGE, roundTripped.tests().get(FOO).collectionStatus());
		assertEquals(CollectionStatus.COLLECTED_EMPTY, roundTripped.tests().get(BAR).collectionStatus());
		assertTrue(roundTripped.completeness().isComplete());
	}

	@Test void collectionFailureIsUnmappedAndDistinctFromTestFailure()
	{
		CoverageMap map = map(Map.of(FOO, new TestCoverage(Set.of("com.foo.Service"), Set.of(),
				TestOutcome.FAIL, CollectionStatus.COLLECTED_WITH_COVERAGE)),
				List.of(new UnmappedTest(BAR, UnmappedReason.COLLECTION_FAILED)), List.of());
		assertTrue(map.tests().containsKey(FOO));
		assertFalse(map.tests().containsKey(BAR));
		assertEquals(UnmappedReason.COLLECTION_FAILED, map.unmapped().get(0).reason());
	}

	@Test void provisionalFragmentRoundTripsIncludingIncompleteAndUnmapped()
	{
		CoverageFragment fragment = new CoverageFragment(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision("abc123"), new ShardId("07"), Map.of(FOO, covered("com.foo.Service")), List.of(new UnmappedTest(BAR, UnmappedReason.COLLECTION_FAILED)), List.of(scope("scope-1", Set.of(new TestContainer("com.foo.FooTest"), new TestContainer("com.foo.BarTest")))), false);
		CoverageFragment decoded = new CoverageFragmentCodec().deserialize(new CoverageFragmentCodec().serialize(fragment));
		assertFalse(decoded.collectionCompleted()); assertEquals(fragment.revision(), decoded.revision()); assertEquals(UnmappedReason.COLLECTION_FAILED, decoded.unmapped().get(0).reason()); assertEquals(2, decoded.setupScopes().get(0).affectedContainers().size());
		CoverageFragment other = new CoverageFragment(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision("other"), new ShardId("08"), Map.of(), List.of(), List.of(), true);
		assertFalse(fragment.hasSameRevision(other));
	}

	@Test void fragmentValidatorRejectsMissingBindingOverlapDuplicatesAndMalformedMethods()
	{
		CoverageFragment missing = new CoverageFragment(CoverageMapContract.SCHEMA_VERSION, null, null, Map.of(), List.of(), List.of(), false);
		assertTrue(CoverageMapValidator.validate(missing).has(ValidationCode.MISSING_REVISION));
		assertTrue(CoverageMapValidator.validate(missing).has(ValidationCode.MISSING_SHARD_ID));

		CoverageFragment overlap = new CoverageFragment(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision("r"), new ShardId("s"),
				Map.of(FOO, covered("com.foo.Service")), List.of(new UnmappedTest(FOO, UnmappedReason.COLLECTION_FAILED),
				new UnmappedTest(FOO, UnmappedReason.COLLECTION_FAILED)), List.of(), false);
		assertTrue(CoverageMapValidator.validate(overlap).has(ValidationCode.TEST_MAPPED_AND_UNMAPPED));
		assertTrue(CoverageMapValidator.validate(overlap).has(ValidationCode.DUPLICATE_TEST_IDENTITY));

		assertThrows(IllegalArgumentException.class, () -> MethodIdentity.parse("not-a-method"));
		assertThrows(CoverageMapValidationException.class, () -> new CoverageFragmentCodec().serialize(overlap));
	}

	@Test void fragmentCodecRejectsDuplicateWireTestIdentity()
	{
		String coverage = "{\"classes\":[],\"methods\":[],\"outcome\":\"PASS\",\"collectionStatus\":\"COLLECTED_EMPTY\"}";
		String json = "{\"schemaVersion\":2,\"revision\":\"r\",\"shardId\":\"s\",\"tests\":{"
				+ "\"com.foo.FooTest#a\":" + coverage + ",\"com.foo.FooTest#a\":" + coverage
				+ "},\"unmapped\":[],\"setupScopes\":[],\"collection\":{\"completed\":true}}";
		CoverageMapValidationException failure = assertThrows(CoverageMapValidationException.class,
				() -> new CoverageFragmentCodec().deserialize(json.getBytes(StandardCharsets.UTF_8)));
		assertEquals(ValidationCode.DUPLICATE_TEST_IDENTITY, failure.getError().code());
	}

	@Test void separableMethodCoverageDescriptorRoundTrips()
	{
		String hash = "sha256:" + "a".repeat(64); CoverageMap base = sampleMap();
		CoverageMap withReference = new CoverageMap(base.schemaVersion(), base.revision(), base.generatedAt(), base.generator(), base.tests(), base.unmapped(), base.setupScopes(), base.completeness(), base.statistics(), base.lifecycleState(), new MethodCoverageReference("coverage-methods.json.gz", hash));
		assertEquals(hash, new CoverageMapCodec().deserialize(new CoverageMapCodec().serialize(withReference)).methodCoverageReference().sha256());
	}

	private static CoverageMap sampleMap() { return map(Map.of(FOO, covered("com.foo.Service")), List.of(new UnmappedTest(BAR, UnmappedReason.FAILED)), List.of()); }
	private static CoverageMap map(Map<TestIdentity, TestCoverage> tests, List<UnmappedTest> unmapped, List<SetupScope> scopes)
	{
		Set<TestIdentity> reported = new java.util.HashSet<>(tests.keySet()); unmapped.forEach(value -> reported.add(value.test()));
		Completeness completeness = Completeness.from(expectation(reported, Set.of(new ShardId("01"))),
				new CollectionSummary(reported, List.of(new ShardId("01")), Set.of()));
		return new CoverageMap(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision("abc123"), Instant.parse("2026-08-30T10:00:00Z"), new GeneratorProvenance("0.2.0", "0.1.0", "0.8.15", "17"), tests, unmapped, scopes, completeness, new MapStatistics(reported.size(), tests.size(), unmapped.size(), scopes.size(), 1, 1), CoverageMapLifecycleState.PUBLISHED, null);
	}
	private static CoverageMap withCompleteness(Completeness value)
	{
		return new CoverageMap(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision("abc123"), Instant.parse("2026-08-30T10:00:00Z"), new GeneratorProvenance("0.2.0", "0.1.0", "0.8.15", "17"), Map.of(FOO, covered("com.foo.Service")), List.of(), List.of(), value, new MapStatistics(1, 1, 0, 0, 1, 1), CoverageMapLifecycleState.PUBLISHED, null);
	}
	private static CollectionExpectation expectation(Set<TestIdentity> tests, Set<ShardId> shards)
	{
		return new CollectionExpectation(new TestInventory(new CoverageMapRevision("abc123"), tests), shards);
	}
	private static TestCoverage covered(String cls) { return new TestCoverage(Set.of(cls), Set.of(method(cls + "#run()V")), TestOutcome.PASS, CollectionStatus.COLLECTED_WITH_COVERAGE); }
	private static MethodIdentity method(String value) { return MethodIdentity.parse(value); }
	private static SetupScope scope(String id, Set<TestContainer> containers) { return new SetupScope(id, SetupScopeType.CONTAINER, Set.of("com.foo.Config"), containers); }
	private static String checksum(byte[] json) { return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject().get("checksum").getAsString(); }
}
