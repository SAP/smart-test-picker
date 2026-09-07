// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CollectionExpectation;
import com.sap.oss.smarttestpicker.coverage.model.CollectionSummary;
import com.sap.oss.smarttestpicker.coverage.model.Completeness;
import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestInventory;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidator;

/** Compact TASK 42 publication harness; all schema semantics remain in smart-test-picker-common. */
public final class Task42MapPublisher {
	public static void main(String[] args) throws Exception {
		if (args.length < 4 || args.length > 6) throw new IllegalArgumentException(
				"usage: <fragment> <head-inventory> <intentional-non-execution> <output> [revision-override|-] [unsafe-unmapped]");
		CoverageFragment fragment = new CoverageFragmentCodec().deserialize(Files.readAllBytes(Path.of(args[0])));
		var revision = args.length >= 5 && !args[4].equals("-")
				? new com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision(args[4]) : fragment.revision();
		Map<TestIdentity, TestCoverage> tests = new TreeMap<>(fragment.tests());
		List<UnmappedTest> unmapped = new ArrayList<>(fragment.unmapped());
		if (args.length == 6) {
			TestIdentity fixture = TestIdentity.parse(args[5]);
			if (tests.remove(fixture) == null) throw new IllegalArgumentException("fixture identity is not mapped: " + fixture);
			unmapped.add(new UnmappedTest(fixture, UnmappedReason.COLLECTION_FAILED));
		}
		Set<TestIdentity> expected = readIdentities(Path.of(args[1]));
		Set<TestIdentity> intentional = Set.copyOf(Files.readAllLines(Path.of(args[2])).stream()
				.filter(line -> !line.isBlank()).map(TestIdentity::parse).toList());
		Set<TestIdentity> executable = new HashSet<>(tests.keySet());
		unmapped.forEach(value -> executable.add(value.test()));
		CollectionExpectation expectation = new CollectionExpectation(
				new TestInventory(revision, expected), Set.of(fragment.shardId()), intentional);
		Completeness completeness = Completeness.from(expectation,
				new CollectionSummary(executable, List.of(fragment.shardId()), Set.of()));
		long classEdges = tests.values().stream().mapToLong(value -> value.coveredClasses().size()).sum();
		long methodEdges = tests.values().stream().mapToLong(value -> value.coveredMethods().size()).sum();
		CoverageMap map = new CoverageMap(CoverageMapContract.SCHEMA_VERSION, revision,
				Instant.parse("2026-09-07T00:00:00Z"),
				new GeneratorProvenance("task42", "0.1.0", "ASM", "21.0.11"),
				tests, unmapped, fragment.setupScopes(), completeness,
				new MapStatistics(expected.size(), tests.size(), unmapped.size(),
						fragment.setupScopes().size(), classEdges, methodEdges),
				CoverageMapLifecycleState.PUBLISHED, null);
		var validation = CoverageMapValidator.validate(map);
		if (!validation.isValid()) throw new IllegalStateException(validation.errors().toString());
		byte[] encoded = new CoverageMapCodec().serialize(map);
		Files.write(Path.of(args[3]), encoded);
		new CoverageMapCodec().deserialize(encoded);
	}

	private static Set<TestIdentity> readIdentities(Path path) throws Exception {
		List<TestIdentity> identities = new ArrayList<>();
		JsonParser.parseString(Files.readString(path)).getAsJsonArray()
				.forEach(value -> identities.add(TestIdentity.parse(value.getAsString())));
		return Set.copyOf(identities);
	}
}
