// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import static com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AsmReactorScopeIntegrationTest {
	private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:" + System.getProperty("smartTestPicker.version") + ":";
	private static final String B2 = "maven:module-b::b.BTests#b2";
	private static final String EMPTY = "maven:module-b::shared.SharedTest#same";

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void derivesCrossModuleScopeIncludingInstalledMainJar(boolean installed, @TempDir Path temp) throws Exception {
		Path fixture = prepare(temp); install(fixture);
		if (installed) Files.move(fixture.resolve("module-a/target/classes"), temp.resolve("removed-a-classes"));
		Result result = mapping(fixture, installed, B2, EMPTY);
		save(fixture, installed ? "installed" : "reactor", result);
		assertEquals(0, result.exitCode(), result.output());
		var fragment = fragment(fixture);
		assertEquals(Set.of(ExecutableTestIdentity.parse(B2), ExecutableTestIdentity.parse(EMPTY)), fragment.tests().keySet());
		assertEquals(Set.of("a.AValue", "b.BValue"), fragment.tests().get(ExecutableTestIdentity.parse(B2)).coveredClasses());
		assertTrue(fragment.tests().get(ExecutableTestIdentity.parse(EMPTY)).coveredClasses().isEmpty());
		assertTrue(fragment.collectionCompleted()); assertTrue(fragment.unmapped().isEmpty());
		var diagnostic = diagnostic(fixture);
		assertEquals(Set.of("a.", "b."), strings(diagnostic.getAsJsonObject("configuration").getAsJsonArray("includes")));
		assertTrue(diagnostic.getAsJsonObject("metrics").get("classesTransformed").getAsInt() >= 2);
		if (installed) assertTrue(diagnostic.getAsJsonObject("configuration").get("productionSources").toString().contains("module-a-1.jar"));
		var evidence = MavenMappingEvidence.read(fixture.resolve("target/shard-evidence.json"));
		assertEquals(fragment.tests().keySet(), evidence.executed());
	}

	@ParameterizedTest @ValueSource(strings = {"wrong.prefix.", "a.,b."})
	void zeroInstrumentationRejectsWrongScopeAndFullyExcludedScope(String includes, @TempDir Path temp) throws Exception {
		Path fixture = prepare(temp); install(fixture); assignment(fixture, B2);
		var args = arguments(fixture, false);
		args.add("-DsmartTestPicker.asm.includes=" + includes);
		if (includes.equals("a.,b.")) args.add("-DsmartTestPicker.asm.excludes=a.,b.");
		Result result = maven(fixture, args.toArray(String[]::new));
		save(fixture, includes.startsWith("wrong") ? "wrong-prefix" : "excluded", result);
		assertNotEquals(0, result.exitCode());
		assertTrue(result.output().contains("classesTransformed=0"), result.output());
		assertTrue(result.output().contains("includes="), result.output());
		assertEquals(0, diagnostic(fixture).getAsJsonObject("metrics").get("classesTransformed").getAsInt());
		assertFalse(Files.exists(fixture.resolve("module-b/target/stp/coverage-fragment-v3.json")));
		assertFalse(Files.exists(fixture.resolve("module-b/target/stp/execution-evidence-v2.json")));
		assertFalse(Files.exists(fixture.resolve("target/shard.json")));
		assertTrue(Files.readString(fixture.resolve("module-b/target/surefire-reports/TEST-b.BTests.xml")).contains("tests=\"1\""));
	}

	@Test void emptyCoverageWithObservedInstrumentationStillSucceeds(@TempDir Path temp) throws Exception {
		Path fixture = prepare(temp);
		Files.writeString(fixture.resolve("module-b/src/test/java/shared/SharedTest.java"), """
			package shared;
			class SharedTest {
			  @org.junit.jupiter.api.BeforeAll static void loadOnly() throws Exception {
			    Class.forName("a.AValue", false, SharedTest.class.getClassLoader());
			  }
			  @org.junit.jupiter.api.Test void same() { }
			}
			""");
		install(fixture); Result result = mapping(fixture, false, EMPTY); save(fixture, "legitimate-empty", result);
		assertEquals(0, result.exitCode(), result.output());
		var fragment = fragment(fixture); var coverage = fragment.tests().get(ExecutableTestIdentity.parse(EMPTY));
		assertEquals(Set.of(ExecutableTestIdentity.parse(EMPTY)), fragment.tests().keySet());
		assertTrue(coverage.coveredClasses().isEmpty()); assertTrue(coverage.coveredMethods().isEmpty());
		assertEquals(CollectionStatus.COLLECTED_EMPTY, coverage.collectionStatus());
		assertTrue(fragment.collectionCompleted()); assertTrue(fragment.unmapped().isEmpty());
		assertTrue(diagnostic(fixture).getAsJsonObject("metrics").get("classesTransformed").getAsInt() > 0);
	}

	@Test void productionSourcesExcludeCustomTestOutputAndTestJarAndMeasureInflation(@TempDir Path temp) throws Exception {
		Path fixture = prepare(temp);
		Path aPom = fixture.resolve("module-a/pom.xml"), bPom = fixture.resolve("module-b/pom.xml");
		Files.writeString(aPom, Files.readString(aPom).replace("</project>", """
			<build><plugins><plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version>
			<executions><execution><goals><goal>test-jar</goal></goals></execution></executions>
			</plugin></plugins></build></project>
			"""));
		Files.writeString(fixture.resolve("module-a/src/test/java/a/TestHelper.java"),
				"package a; public class TestHelper { public static int value() { return 42; } }");
		Files.writeString(bPom, Files.readString(bPom).replace("<dependencies>", """
			<dependencies><dependency><groupId>fixture</groupId><artifactId>module-a</artifactId><version>1</version><type>test-jar</type><scope>test</scope></dependency>
			""").replace("</project>", "<build><testOutputDirectory>${project.build.directory}/checks</testOutputDirectory></build></project>"));
		Files.writeString(fixture.resolve("module-b/src/test/java/b/BTests.java"), """
			package b; class BTests {
			 @org.junit.jupiter.api.Test void b2() {
			  new b.BValue().value(); new a.AValue().value();
			  org.junit.jupiter.api.Assertions.assertEquals(42, a.TestHelper.value());
			 }
			}
			""");
		install(fixture);
		Result safe = mapping(fixture, true, B2); save(fixture, "production-only", safe);
		assertEquals(0, safe.exitCode(), safe.output());
		var safeFragment = fragment(fixture);
		assertEquals(Set.of("a.AValue", "b.BValue"), safeFragment.tests().get(ExecutableTestIdentity.parse(B2)).coveredClasses());
		long safeBytes = Files.size(fixture.resolve("module-b/target/stp/coverage-fragment-v3.json"));
		// Unsupported raw-agent control, without the prepared path's code-source allowlist.
		Path raw = fixture.resolve("raw-fragment.json"), diagnostics = fixture.resolve("raw-diagnostic.json");
		String agent = "-javaagent:" + System.getProperty("stp.test.agent.jar") + "=output=" + diagnostics + ";fragmentOutput=" + raw
				+ ";schemaVersion=3;revision=reactor-revision;shardId=reactor-shard;executionTarget=maven:module-b;runId=scope-control;includes=a.,b.";
		Result control = maven(fixture, "-pl", ".,module-b", "test", "-Djacoco.skip=true", "-DargLine=" + agent,
				"-Dtest=b.BTests#b2", "-DsmartTestPicker.coverageCollector=ASM");
		assertEquals(0, control.exitCode(), control.output());
		var controlFragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(raw));
		assertEquals(Set.of("a.AValue", "b.BValue", "b.BTests", "a.TestHelper"),
				controlFragment.tests().get(ExecutableTestIdentity.parse(B2)).coveredClasses());
		Path evidence = Files.createDirectories(Path.of("build/maven-asm-08/test-inflation"));
		Files.copy(raw, evidence.resolve("with-tests-fragment.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(diagnostics, evidence.resolve("with-tests-diagnostic.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.writeString(evidence.resolve("maven.log"), control.output());
		Files.writeString(evidence.resolve("comparison.json"), new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
				"productionOnly", sizes(safeFragment, safeBytes), "withTestClasses", sizes(controlFragment, Files.size(raw)))));
	}

	private static Map<String, Long> sizes(ExecutableCoverageFragment f, long bytes) {
		return Map.of("bytes", bytes, "classEdges", f.tests().values().stream().mapToLong(t -> t.coveredClasses().size()).sum(),
				"methodEdges", f.tests().values().stream().mapToLong(t -> t.coveredMethods().size()).sum());
	}
	private static Path prepare(Path temp) throws Exception { return copyFixture("schema-v3-reactor", temp.resolve("reactor")); }
	private static void install(Path fixture) throws Exception {
		Result result = maven(fixture, "install", "-DskipTests", "-Djacoco.skip=true");
		assertEquals(0, result.exitCode(), result.output());
	}
	private static void assignment(Path fixture, String... tests) throws Exception {
		Set<ExecutableTestIdentity> identities = new TreeSet<>(); for (String test : tests) identities.add(ExecutableTestIdentity.parse(test));
		Files.write(fixture.resolve("assignment.json"), new ExecutableShardAssignmentCodec().serialize(new ExecutableShardAssignment(
				1, new CoverageMapRevision("reactor-revision"), new ShardId("reactor-shard"), identities)));
	}
	private static Result mapping(Path fixture, boolean installed, String... tests) throws Exception {
		assignment(fixture, tests); return maven(fixture, arguments(fixture, installed).toArray(String[]::new));
	}
	private static ArrayList<String> arguments(Path fixture, boolean installed) {
		var args = new ArrayList<String>(); if (installed) args.addAll(List.of("-pl", ".,module-b"));
		args.addAll(List.of(PLUGIN + "prepare-reactor-executable-mapping", "verify", PLUGIN + "aggregate-reactor-coverage-fragment",
				"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.coverageCollector=ASM", "-Djacoco.skip=true",
				"-DsmartTestPicker.testsFile=" + fixture.resolve("assignment.json"),
				"-DsmartTestPicker.fragmentOutput=" + fixture.resolve("target/shard.json"),
				"-DsmartTestPicker.evidenceOutput=" + fixture.resolve("target/shard-evidence.json")));
		return args;
	}
	private static ExecutableCoverageFragment fragment(Path fixture) throws Exception {
		return new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fixture.resolve("module-b/target/stp/coverage-fragment-v3.json")));
	}
	private static JsonObject diagnostic(Path fixture) throws Exception {
		return JsonParser.parseString(Files.readString(fixture.resolve("module-b/target/stp/asm-agent-diagnostic.json"))).getAsJsonObject();
	}
	private static Set<String> strings(JsonArray array) { Set<String> values = new TreeSet<>(); array.forEach(v -> values.add(v.getAsString())); return values; }
	private static void save(Path fixture, String scenario, Result result) throws Exception {
		Path destination = Files.createDirectories(Path.of("build/maven-asm-08", scenario));
		Files.writeString(destination.resolve("maven.log"), result.output());
		for (String source : List.of("module-b/target/stp/coverage-fragment-v3.json", "module-b/target/stp/asm-agent-diagnostic.json",
				"module-b/target/stp/execution-evidence-v2.json", "module-b/target/stp/asm-production-sources.txt", "target/shard.json", "target/shard-evidence.json")) {
			Path file = fixture.resolve(source); if (Files.isRegularFile(file)) Files.copy(file, destination.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
