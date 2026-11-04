// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import static com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.CollectorCapabilities;
import com.sap.oss.smarttestpicker.CoverageCollectorBackend;
import com.sap.oss.smarttestpicker.CoverageCollectorType;
import com.sap.oss.smarttestpicker.StpCollectorSelection;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises preparation and finalization as real Maven invocations, not only helper calls. */
class AsmMappingParityIntegrationTest {
	private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:"
			+ System.getProperty("smartTestPicker.version") + ":";
	private static final String FRAGMENT = "coverage-fragment-v3.json";
	private static final String DIAGNOSTIC = "asm-agent-diagnostic.json";
	private static final String EVIDENCE = "execution-evidence-v2.json";

	@Test void mavenDeclaresSharedCapabilitiesWithoutAdvertisingLogicalAsmFinalization() {
		CoverageCollectorBackend backend = MavenCoverageCollectorBackend.selected(StpCollectorSelection.ASM);
		assertEquals(CoverageCollectorType.ASM, backend.type());
		assertEquals(new CollectorCapabilities(true, true, true, true, false), backend.capabilities());
		assertEquals(CoverageCollectorType.JACOCO,
				MavenCoverageCollectorBackend.selected(StpCollectorSelection.JACOCO).type());
	}

	@Test void preparationInvalidatesPreviousOutputsEvenWhenNoForkStarts(@TempDir Path temp) throws Exception {
		Path fixture = fixture(temp);
		Result previous = run(fixture, PLUGIN + "prepare-reactor-executable-mapping", "verify");
		save("stale", "previous-mapping.log", previous);
		assertEquals(0, previous.exitCode(), previous.output());
		var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(output(fixture, FRAGMENT)));
		assertEquals(Set.of(ExecutableTestIdentity.parse("maven:module-b::b.BTests#b2")), fragment.tests().keySet());
		for (String name : List.of(FRAGMENT, DIAGNOSTIC, EVIDENCE)) assertTrue(Files.isRegularFile(output(fixture, name)));
		// Preparation alone cannot rely on agent premain or finalization to remove stale outputs.
		Result prepared = run(fixture, PLUGIN + "prepare-reactor-executable-mapping");
		save("stale", "prepare-only.log", prepared);
		assertEquals(0, prepared.exitCode(), prepared.output());
		assertFalse(prepared.output().contains("--- surefire:"), prepared.output());
		for (String name : List.of(FRAGMENT, DIAGNOSTIC, EVIDENCE)) assertFalse(Files.exists(output(fixture, name)), name);
		Result finalized = finalizeOnly(fixture);
		save("stale", "finalize-without-fork.log", finalized);
		assertNotEquals(0, finalized.exitCode());
		assertTrue(finalized.output().contains("Missing ASM agent diagnostic"), finalized.output());
		observation("stale", "Previous valid fragment contained maven:module-b::b.BTests#b2.\n"
				+ "Preparation alone removed fragment, diagnostic and evidence before any test fork.\n"
				+ "Finalization without a new fork rejected missing diagnostics; stale coverage was not consumed.\n");
	}

	@ParameterizedTest @ValueSource(strings = {FRAGMENT, DIAGNOSTIC})
	void preparationFailsHardWhenStaleOutputCannotBeDeleted(String name, @TempDir Path temp) throws Exception {
		Path fixture = fixture(temp);
		// A nonempty directory is undeletable by deleteIfExists, independent of user permissions.
		Path blocked = Files.createDirectories(output(fixture, name));
		Files.writeString(blocked.resolve("retained-child"), "must not be recursively removed");
		Result result = run(fixture, PLUGIN + "prepare-reactor-executable-mapping", "test");
		save("undeletable-" + name, "maven.log", result);
		assertNotEquals(0, result.exitCode());
		assertTrue(result.output().contains("Cannot prepare schema-v3 Maven mapping"), result.output());
		assertTrue(result.output().contains(name), result.output());
		assertFalse(result.output().contains("--- surefire:"), result.output());
		assertFalse(Files.exists(fixture.resolve("module-b/target/surefire-reports")));
		assertTrue(Files.exists(blocked.resolve("retained-child")));
		observation("undeletable-" + name, "Preparation failed before Surefire; undeletable output retained without recursive cleanup.\n");
	}

	@ParameterizedTest @ValueSource(strings = {"revision", "shardId"})
	void finalizationRejectsFragmentBindingMismatch(String field, @TempDir Path temp) throws Exception {
		Path fixture = fixture(temp);
		Result collected = run(fixture, PLUGIN + "prepare-reactor-executable-mapping", "verify");
		save("wrong-" + field, "mapping.log", collected);
		assertEquals(0, collected.exitCode(), collected.output());
		Path path = output(fixture, FRAGMENT);
		var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		json.addProperty(field, "wrong-binding");
		Files.writeString(path, json.toString());
		// Keep assignment and diagnostic valid: this isolates the fragment equality check.
		Result result = finalizeOnly(fixture);
		save("wrong-" + field, "finalize.log", result);
		String message = field.equals("revision") ? "Fragment revision does not match assignment revision"
				: "Fragment shard does not match assignment shard";
		assertNotEquals(0, result.exitCode());
		assertTrue(result.output().contains(message), result.output());
		assertFalse(Files.exists(output(fixture, EVIDENCE)));
		assertEquals("wrong-binding", JsonParser.parseString(Files.readString(path)).getAsJsonObject().get(field).getAsString());
		observation("wrong-" + field, message + "; stale evidence removed; agent fragment was not rewritten.\n");
	}

	private static Path fixture(Path temp) throws Exception {
		// Maven canonicalizes the macOS /var -> /private/var temporary-directory alias.
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")).toRealPath();
		Result installed = maven(fixture, "install", "-DskipTests", "-Djacoco.skip=true");
		assertEquals(0, installed.exitCode(), installed.output());
		Files.writeString(fixture.resolve("assignment.json"), """
			{"version":1,"revision":"reactor-revision","shardId":"reactor-shard",
			 "tests":["maven:module-b::b.BTests#b2"]}
			""");
		return fixture;
	}

	private static Result run(Path fixture, String... goals) throws Exception {
		var args = new ArrayList<>(List.of(goals));
		args.addAll(List.of("-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.coverageCollector=ASM",
				"-Djacoco.skip=true", "-DsmartTestPicker.testsFile=" + fixture.resolve("assignment.json")));
		return maven(fixture, args.toArray(String[]::new));
	}

	private static Result finalizeOnly(Path fixture) throws Exception {
		return run(fixture, "-pl", "module-b", PLUGIN + "generate-coverage-fragment",
				"-DsmartTestPicker.executionTarget=maven:module-b",
				"-DsmartTestPicker.moduleFragmentOutput=" + output(fixture, FRAGMENT),
				"-DsmartTestPicker.moduleEvidenceOutput=" + output(fixture, EVIDENCE),
				"-DsmartTestPicker.moduleDiagnosticOutput=" + output(fixture, DIAGNOSTIC));
	}

	private static Path output(Path fixture, String name) { return fixture.resolve("module-b/target/stp/" + name); }
	private static Path saved(String scenario) throws Exception { return Files.createDirectories(Path.of("build/maven-asm-16", scenario)); }
	private static void save(String scenario, String name, Result result) throws Exception {
		Files.writeString(saved(scenario).resolve(name), "exitCode=" + result.exitCode() + "\n" + result.output());
	}
	private static void observation(String scenario, String text) throws Exception {
		Files.writeString(saved(scenario).resolve("assertions.txt"), text);
		System.out.println("ASM-16 " + scenario + ": " + text);
	}
}
