// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMap;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapLifecycleState;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.model.MapStatistics;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageMapCodec;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidationException;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidator;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCategory;
import com.sap.oss.smarttestpicker.coverage.validation.ValidationCode;
import org.junit.jupiter.api.Test;

/** Real-data validation. Reads existing evaluation/source trees and never runs JGraphT. */
class JGraphTCoverageMapV1IntegrationTest
{
	private static final Pattern LEGACY_KEY = Pattern.compile("^(.+)#(.+)_([0-9a-f]{7})$");
	private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
	private static final Pattern JUNIT_ID = Pattern.compile("\\[class:([\\w.$]+)]/\\[method:([\\w$]+)\\(\\)]");

	@Test void realJGraphTMapSurvivesSchemaV1RoundTrip() throws Exception
	{
		Path workspace = workspace();
		Path sourceMap = configured("stp.jgraphtCoverageMap",
				workspace.resolve("../smart-test-picker-evaluation/jgrapht/results/test-coverage-map.json.gz"));
		Path sourceRoot = configured("stp.jgraphtSourceRoot", workspace.resolve("../jgrapht"));
		assumeTrue(Files.isRegularFile(sourceMap) && Files.isDirectory(sourceRoot),
				"JGraphT evaluation data is optional; set stp.jgraphtCoverageMap and stp.jgraphtSourceRoot");

		long constructionStart = System.nanoTime();
		JsonObject legacy = readGzipJson(sourceMap);
		Map<String, Set<String>> fqnsByOuterSimpleName = sourceFqns(sourceRoot);
		Map<String, Set<String>> archivedFqnEvidence = archivedFqnEvidence(sourceMap.getParent());
		Conversion conversion = convert(legacy, fqnsByOuterSimpleName, archivedFqnEvidence);
		LegacyStats legacyStats = legacyStats(legacy);
		long constructionNanos = System.nanoTime() - constructionStart;

		CoverageMap map = candidate(legacy, conversion.tests());
		assertNull(map.completeness(), "legacy data has no independent inventory");
		assertTrue(map.setupScopes().isEmpty(), "setup information is unavailable in the source dataset");
		assertEquals(CoverageMapLifecycleState.CANDIDATE, map.lifecycleState());
		assertTrue(CoverageMapValidator.validate(map).has(ValidationCode.LIFECYCLE_NOT_PUBLISHED));

		CoverageMapCodec codec = new CoverageMapCodec();
		byte[] first = timed(3, ignored -> codec.serialize(map)).value();
		byte[] second = codec.serialize(map);
		assertArrayEquals(first, second);
		Timed<CoverageMap> deserialization = timed(3, ignored -> codec.deserialize(first));
		CoverageMap roundTripped = deserialization.value();
		assertArrayEquals(first, codec.serialize(roundTripped));

		long validationStart = System.nanoTime();
		assertSemanticEquality(conversion.tests(), roundTripped.tests());
		verifyDictionaryIntegrity(first);
		long validationNanos = System.nanoTime() - validationStart;

		String json = new String(first, StandardCharsets.UTF_8);
		String corrupt = json.replaceFirst("org\\.jgrapht", "org.xgrapht");
		assertNotEquals(json, corrupt);
		CoverageMapValidationException corruptError = assertThrows(CoverageMapValidationException.class,
				() -> codec.deserialize(corrupt.getBytes(StandardCharsets.UTF_8)));
		assertEquals(ValidationCode.CHECKSUM_MISMATCH, corruptError.getError().code());

		String higherSchema = json.replaceFirst("\\\"schemaVersion\\\":1", "\\\"schemaVersion\\\":2");
		CoverageMapValidationException schemaError = assertThrows(CoverageMapValidationException.class,
				() -> codec.deserialize(higherSchema.getBytes(StandardCharsets.UTF_8)));
		assertEquals(ValidationCategory.INCOMPATIBLE_SCHEMA, schemaError.getError().category());
		assertEquals(ValidationCode.HIGHER_SCHEMA_VERSION, schemaError.getError().code());

		JsonObject wire = JsonParser.parseString(json).getAsJsonObject();
		long gzipSize = gzip(first).length;
		byte[] withoutMethods = codec.serialize(withoutMethods(map));
		long methodRawContribution = first.length - withoutMethods.length;
		long methodGzipContribution = gzipSize - gzip(withoutMethods).length;
		long dictionarySize = jsonBytes(wire, "classIndex", "methodIndex", "containerIndex", "testIndex");
		long testPayloadSize = jsonBytes(wire, "tests");
		System.out.printf("%nJGraphT schema-v1 validation%n"
				+ "source map: %s%nsource format: gzip-compressed legacy JSON (classMetrics, metadata, testMappings)%n"
				+ "source revision: %s%nsource tests: %d%nconverted FQN tests: %d%nunresolved tests: %d%n"
				+ "conversion percentage: %.2f%%%nunresolved identities: %s%n"
				+ "whole source unique classes/methods: %d/%d%nwhole source class/method edges: %d/%d%n"
				+ "converted-source/round-trip unique classes: %d/%d%nconverted-source/round-trip unique methods: %d/%d%n"
				+ "converted-source/round-trip class edges: %d/%d%nconverted-source/round-trip method edges: %d/%d%n"
				+ "raw/gzip bytes: %d/%d%ndictionary bytes (JSON members): %d%ntest payload bytes (JSON member): %d%n"
				+ "method coverage raw/gzip contribution bytes: %d/%d%n"
				+ "SHA-256: %s%ndeterministic and round-trip bytes: identical%n"
				+ "construction/serialization/deserialization/validation ms: %.2f/%.2f/%.2f/%.2f%n"
				+ "corruption rejection: CHECKSUM_MISMATCH%nhigher-schema rejection: INCOMPATIBLE_SCHEMA/HIGHER_SCHEMA_VERSION%n",
				sourceMap, legacy.getAsJsonObject("metadata").get("commitId").getAsString(),
				conversion.sourceMappings(), conversion.tests().size(), conversion.unresolved().size(),
				100.0 * conversion.tests().size() / conversion.sourceMappings(), conversion.unresolved(),
				legacyStats.classes(), legacyStats.methods(), legacyStats.classEdges(), legacyStats.methodEdges(),
				classes(conversion.tests()).size(), classes(roundTripped.tests()).size(),
				methods(conversion.tests()).size(), methods(roundTripped.tests()).size(),
				classEdges(conversion.tests()).size(), classEdges(roundTripped.tests()).size(),
				methodEdges(conversion.tests()).size(), methodEdges(roundTripped.tests()).size(),
				first.length, gzipSize, dictionarySize, testPayloadSize, methodRawContribution, methodGzipContribution,
				wire.get("checksum").getAsString(), millis(constructionNanos), timed(3, ignored -> codec.serialize(map)).millis(),
				deserialization.millis(), millis(validationNanos));
	}

	private static Conversion convert(JsonObject legacy, Map<String, Set<String>> fqnsByOuter,
			Map<String, Set<String>> archivedFqnEvidence) {
		JsonObject mappings = legacy.getAsJsonObject("testMappings");
		Map<TestIdentity, TestCoverage> converted = new TreeMap<>(); List<String> unresolved = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : mappings.entrySet()) {
			Matcher key = LEGACY_KEY.matcher(entry.getKey());
			if (!key.matches()) { unresolved.add(entry.getKey()); continue; }
			String simpleBinaryName = key.group(1); String outer = simpleBinaryName.split("\\$", 2)[0];
			Set<String> candidates = new TreeSet<>(fqnsByOuter.getOrDefault(outer, Set.of()));
			if (candidates.size() > 1) {
				Set<String> evidence = archivedFqnEvidence.getOrDefault(simpleBinaryName + "#" + key.group(2), Set.of());
				candidates.retainAll(evidence);
			}
			if (candidates.size() != 1) { unresolved.add(entry.getKey()); continue; }
			String outerFqn = candidates.iterator().next();
			String className = outerFqn + simpleBinaryName.substring(outer.length());
			TestIdentity identity;
			try { identity = new TestIdentity(className, key.group(2)); }
			catch (IllegalArgumentException invalid) { unresolved.add(entry.getKey()); continue; }
			JsonObject coverage = entry.getValue().getAsJsonObject();
			Set<String> coveredClasses = strings(coverage.getAsJsonArray("classes"));
			Set<String> coveredMethods = strings(coverage.getAsJsonArray("methods"));
			TestCoverage previous = converted.put(identity, new TestCoverage(coveredClasses, coveredMethods, TestOutcome.PASS,
					coveredClasses.isEmpty() ? CollectionStatus.COLLECTED_EMPTY : CollectionStatus.COLLECTED_WITH_COVERAGE));
			assertNull(previous, "legacy keys collided after logical identity reconstruction: " + identity);
		}
		return new Conversion(mappings.size(), Map.copyOf(converted), List.copyOf(unresolved));
	}

	private static CoverageMap candidate(JsonObject legacy, Map<TestIdentity, TestCoverage> tests) {
		JsonObject metadata = legacy.getAsJsonObject("metadata"); long classEdges = classEdges(tests).size(); long methodEdges = methodEdges(tests).size();
		return new CoverageMap(CoverageMapContract.SCHEMA_VERSION,
				new CoverageMapRevision(metadata.get("commitId").getAsString()), Instant.parse(metadata.get("timestamp").getAsString()),
				new GeneratorProvenance("0.2.0-validation", "legacy-evaluation", "unknown", System.getProperty("java.version")),
				tests, List.of(), List.of(), null, new MapStatistics(0, tests.size(), 0, 0, classEdges, methodEdges),
				CoverageMapLifecycleState.CANDIDATE, null);
	}

	private static CoverageMap withoutMethods(CoverageMap map) {
		Map<TestIdentity, TestCoverage> tests = new TreeMap<>();
		map.tests().forEach((identity, coverage) -> tests.put(identity,
				new TestCoverage(coverage.coveredClasses(), Set.of(), coverage.outcome(), coverage.collectionStatus())));
		MapStatistics statistics = map.statistics();
		return new CoverageMap(map.schemaVersion(), map.revision(), map.generatedAt(), map.generator(), tests,
				map.unmapped(), map.setupScopes(), map.completeness(), new MapStatistics(statistics.expectedTests(),
				statistics.mappedTests(), statistics.unmappedTests(), statistics.setupScopes(), statistics.classEdges(), 0),
				map.lifecycleState(), null);
	}

	private static LegacyStats legacyStats(JsonObject legacy) {
		Set<String> classes = new HashSet<>(), methods = new HashSet<>(); long classEdges = 0, methodEdges = 0;
		for (JsonElement element : legacy.getAsJsonObject("testMappings").asMap().values()) {
			JsonObject coverage = element.getAsJsonObject();
			Set<String> testClasses = strings(coverage.getAsJsonArray("classes"));
			Set<String> testMethods = strings(coverage.getAsJsonArray("methods"));
			classes.addAll(testClasses); methods.addAll(testMethods); classEdges += testClasses.size(); methodEdges += testMethods.size();
		}
		return new LegacyStats(classes.size(), methods.size(), classEdges, methodEdges);
	}

	private static Map<String, Set<String>> sourceFqns(Path root) throws IOException {
		Map<String, Set<String>> result = new HashMap<>();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(p -> p.toString().contains("/src/test/java/") && p.toString().endsWith(".java")).toList()) {
				String source = Files.readString(path); Matcher packageName = PACKAGE.matcher(source);
				if (!packageName.find()) continue;
				String file = path.getFileName().toString(); String simple = file.substring(0, file.length() - 5);
				result.computeIfAbsent(simple, ignored -> new TreeSet<>()).add(packageName.group(1) + "." + simple);
			}
		}
		return result;
	}

	private static Map<String, Set<String>> archivedFqnEvidence(Path resultsRoot) throws IOException {
		Map<String, Set<String>> result = new HashMap<>();
		try (var paths = Files.walk(resultsRoot)) {
			for (Path path : paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".xml")).toList()) {
				Matcher matcher = JUNIT_ID.matcher(Files.readString(path));
				while (matcher.find()) {
					String fqn = matcher.group(1); int dot = fqn.lastIndexOf('.');
					String simple = fqn.substring(dot + 1);
					result.computeIfAbsent(simple + "#" + matcher.group(2), ignored -> new TreeSet<>()).add(fqn);
				}
			}
		}
		return result;
	}

	private static void assertSemanticEquality(Map<TestIdentity, TestCoverage> source, Map<TestIdentity, TestCoverage> actual) {
		assertEquals(source.keySet(), actual.keySet());
		assertEquals(classes(source), classes(actual)); assertEquals(methods(source), methods(actual));
		assertEquals(classEdges(source), classEdges(actual)); assertEquals(methodEdges(source), methodEdges(actual));
		assertEquals(source, actual);
	}

	private static void verifyDictionaryIntegrity(byte[] bytes) {
		JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
		List<String> tests = dictionary(root, "testIndex"), classes = dictionary(root, "classIndex"), methods = dictionary(root, "methodIndex");
		assertSortedUnique(tests); assertSortedUnique(classes); assertSortedUnique(methods);
		for (JsonElement element : root.getAsJsonArray("tests")) {
			JsonObject value = element.getAsJsonObject(); assertInRange(value.get("test").getAsInt(), tests.size());
			value.getAsJsonArray("classes").forEach(index -> assertInRange(index.getAsInt(), classes.size()));
			value.getAsJsonArray("methods").forEach(index -> assertInRange(index.getAsInt(), methods.size()));
		}
	}

	private static List<String> dictionary(JsonObject root, String name) { return root.getAsJsonArray(name).asList().stream().map(JsonElement::getAsString).toList(); }
	private static void assertSortedUnique(List<String> values) { assertEquals(new ArrayList<>(new TreeSet<>(values)), values); }
	private static void assertInRange(int value, int size) { assertTrue(value >= 0 && value < size, () -> value + " outside dictionary size " + size); }
	private static Set<String> strings(JsonArray values) { Set<String> result = new TreeSet<>(); values.forEach(v -> result.add(v.getAsString())); return result; }
	private static Set<String> classes(Map<TestIdentity, TestCoverage> tests) { return values(tests, TestCoverage::coveredClasses); }
	private static Set<String> methods(Map<TestIdentity, TestCoverage> tests) { return values(tests, TestCoverage::coveredMethods); }
	private static Set<String> values(Map<TestIdentity, TestCoverage> tests, Function<TestCoverage, Set<String>> getter) { Set<String> result = new TreeSet<>(); tests.values().forEach(v -> result.addAll(getter.apply(v))); return result; }
	private static Set<String> classEdges(Map<TestIdentity, TestCoverage> tests) { return edges(tests, TestCoverage::coveredClasses); }
	private static Set<String> methodEdges(Map<TestIdentity, TestCoverage> tests) { return edges(tests, TestCoverage::coveredMethods); }
	private static Set<String> edges(Map<TestIdentity, TestCoverage> tests, Function<TestCoverage, Set<String>> getter) { Set<String> result = new HashSet<>(); tests.forEach((test, coverage) -> getter.apply(coverage).forEach(target -> result.add(test + " -> " + target))); return result; }

	private static JsonObject readGzipJson(Path path) throws IOException { try (Reader reader = new java.io.InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); } }
	private static byte[] gzip(byte[] bytes) throws IOException { java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); } return output.toByteArray(); }
	private static long jsonBytes(JsonObject root, String... names) { JsonObject selected = new JsonObject(); Arrays.stream(names).forEach(name -> selected.add(name, root.get(name))); return selected.toString().getBytes(StandardCharsets.UTF_8).length; }
	private static Path configured(String property, Path fallback) { String value = System.getProperty(property); return value == null ? fallback.normalize() : Path.of(value).toAbsolutePath().normalize(); }
	private static Path workspace() { Path current = Path.of("").toAbsolutePath(); return current.getFileName().toString().equals("smart-test-picker-common") ? current.getParent() : current; }
	private static double millis(long nanos) { return nanos / 1_000_000.0; }
	private static <T> Timed<T> timed(int runs, Function<Integer, T> action) { T value = null; long total = 0; for (int run = 0; run < runs; run++) { long start = System.nanoTime(); value = action.apply(run); total += System.nanoTime() - start; } return new Timed<>(value, total / runs); }
	private record Conversion(int sourceMappings, Map<TestIdentity, TestCoverage> tests, List<String> unresolved) {}
	private record LegacyStats(int classes, int methods, long classEdges, long methodEdges) {}
	private record Timed<T>(T value, long nanos) { double millis() { return JGraphTCoverageMapV1IntegrationTest.millis(nanos); } }
}
