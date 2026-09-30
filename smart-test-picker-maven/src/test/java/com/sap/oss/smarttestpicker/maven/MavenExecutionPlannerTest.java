// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.execution.ExecutionFallbackCause;
import com.sap.oss.smarttestpicker.execution.ExecutionIdentityMetadata;
import com.sap.oss.smarttestpicker.execution.ExecutionMode;
import com.sap.oss.smarttestpicker.execution.ExecutionShape;
import com.sap.oss.smarttestpicker.selector.SelectionOutput;

import static org.junit.jupiter.api.Assertions.*;

class MavenExecutionPlannerTest
{
	@Test
	void selectionCauseMetadataDoesNotChangeExecutionPlan() throws Exception
	{
		MavenProject module = module("one", temp.resolve("causes"));
		copyFixture(module);
		SelectionOutput before = output(List.of("FooTest#testA_hash"),
				Map.of("FooTest#testA_hash", identity(FQN, "alpha", ExecutionShape.ORDINARY)));
		SelectionOutput after = output(List.of("FooTest#testA_hash"),
				Map.of("FooTest#testA_hash", identity(FQN, "alpha", ExecutionShape.ORDINARY)));
		after.setSelectionCauses(Map.of("FooTest#testA_hash", List.of(
				new com.sap.oss.smarttestpicker.selector.SelectionCause(
						com.sap.oss.smarttestpicker.selector.SelectionCauseType.METHOD_CHANGE, "p.Service#work"))));
		MavenExecutionPlanner planner = new MavenExecutionPlanner(List.of(module));
		assertEquals(planner.plan(before, false), planner.plan(after, false));
	}
	@TempDir Path temp;
	private static final String FQN = "com.sap.oss.smarttestpicker.maven.PlannerFixtureTest";

	@Test void exactOrdinaryMultipleAndHashLikeMethods() throws Exception
	{
		MavenProject module = module("one", temp.resolve("one"));
		copyFixture(module);
		SelectionOutput output = output(List.of("k1", "k2", "k3"), Map.of(
				"k1", identity(FQN, "alpha", ExecutionShape.ORDINARY),
				"k2", identity(FQN, "beta", ExecutionShape.ORDINARY),
				"k3", identity(FQN, "testFoo_abcdef0", ExecutionShape.ORDINARY)));
		var plan = new MavenExecutionPlanner(List.of(module)).plan(output, false);
		assertTrue(plan.entries().stream().allMatch(e -> e.executionMode() == ExecutionMode.METHOD_EXACT));
		String selector = new MavenExecutionPlanner(List.of(module)).selectorsByModule(plan).get("one");
		assertTrue(selector.contains(FQN + "#testFoo_abcdef0"));
	}

	@Test void packagePrivateJupiterMethodRemainsMethodExact() throws Exception
	{
		MavenProject module = module("one", temp.resolve("package-private"));
		copyFixture(module);
		SelectionOutput output = output(List.of("k"), Map.of(
				"k", identity(FQN, "packagePrivateJupiterStyle", ExecutionShape.ORDINARY)));
		var entry = new MavenExecutionPlanner(List.of(module)).plan(output, false).entries().get(0);
		assertEquals(ExecutionMode.METHOD_EXACT, entry.executionMode());
		assertEquals(FQN + "#packagePrivateJupiterStyle", entry.generatedSurefireSelector());
	}

	@Test void parameterizedShapesGenerateProvenForms() throws Exception
	{
		MavenProject module = module("one", temp.resolve("one")); copyFixture(module);
		SelectionOutput output = output(List.of("named", "jupiter"), Map.of(
				"named", identity(FQN, "alpha", ExecutionShape.JUNIT4_PARAMETERIZED_NAMED),
				"jupiter", identity(FQN, "beta", ExecutionShape.JUPITER_PARAMETERIZED)));
		var entries = new MavenExecutionPlanner(List.of(module)).plan(output, false).entries();
		assertEquals(ExecutionMode.CLASS_FALLBACK, entries.get(0).executionMode());
		assertEquals(ExecutionFallbackCause.UNSUPPORTED_RUNNER, entries.get(0).executionFallbackCause());
		assertEquals(FQN + "#beta", entries.get(1).generatedSurefireSelector());
	}

	@Test void namedParameterizedIsIsolatedAndCombinedPerClass() throws Exception
	{
		MavenProject module = module("one", temp.resolve("one")); copyFixture(module);
		SelectionOutput output = output(List.of("ordinary", "p1", "p2"), Map.of(
				"ordinary", identity(FQN, "alpha", ExecutionShape.ORDINARY),
				"p1", identity(FQN, "beta", ExecutionShape.JUNIT4_PARAMETERIZED_NAMED),
				"p2", identity(FQN, "gamma", ExecutionShape.JUNIT4_PARAMETERIZED_NAMED)));
		MavenExecutionPlanner planner = new MavenExecutionPlanner(List.of(module));
		var batches = planner.selectorBatchesByModule(planner.plan(output, false)).get("one");
		assertEquals(List.of(FQN + "#alpha," + FQN), batches);
	}

	@Test void legacyUnknownAndStaleUseExplicitClassFallback() throws Exception
	{
		MavenProject module = module("one", temp.resolve("one")); copyFixture(module);
		SelectionOutput legacy = output(List.of("PlannerFixtureTest#alpha_1234567"), Map.of());
		var legacyEntry = new MavenExecutionPlanner(List.of(module)).plan(legacy, false).entries().get(0);
		assertEquals(ExecutionMode.CLASS_FALLBACK, legacyEntry.executionMode());
		assertEquals(ExecutionFallbackCause.AMBIGUOUS_TEST_IDENTITY, legacyEntry.executionFallbackCause());

		SelectionOutput unknown = output(List.of("u"), Map.of("u", identity(FQN, "alpha", ExecutionShape.UNKNOWN)));
		assertEquals(ExecutionFallbackCause.UNKNOWN_EXECUTION_SHAPE,
				new MavenExecutionPlanner(List.of(module)).plan(unknown, false).entries().get(0).executionFallbackCause());

		SelectionOutput stale = output(List.of("s"), Map.of("s", identity(FQN, "gone", ExecutionShape.ORDINARY)));
		assertEquals(ExecutionFallbackCause.STALE_METHOD_IDENTITY,
				new MavenExecutionPlanner(List.of(module)).plan(stale, false).entries().get(0).executionFallbackCause());
	}

	@Test void unmappedAndDeliberateClassModeAreExplicit() throws Exception
	{
		MavenProject module = module("one", temp.resolve("one")); copyFixture(module);
		SelectionOutput unmapped = output(List.of(), Map.of()); unmapped.setUnmappedTests(Map.of(FQN, "new"));
		var entry = new MavenExecutionPlanner(List.of(module)).plan(unmapped, false).entries().get(0);
		assertEquals(ExecutionFallbackCause.UNMAPPED_TEST_CLASS, entry.executionFallbackCause());
		SelectionOutput exact = output(List.of("k"), Map.of("k", identity(FQN, "alpha", ExecutionShape.ORDINARY)));
		assertEquals(ExecutionFallbackCause.CLASS_LEVEL_SELECTION_REQUESTED,
				new MavenExecutionPlanner(List.of(module)).plan(exact, true).entries().get(0).executionFallbackCause());
	}

	@Test void duplicateFqnAcrossModulesNeverLeaksGlobally() throws Exception
	{
		MavenProject a = module("a", temp.resolve("a")), b = module("b", temp.resolve("b")); copyFixture(a); copyFixture(b);
		ExecutionIdentityMetadata id = identity(FQN, "alpha", ExecutionShape.ORDINARY); id.setModule("a");
		SelectionOutput output = output(List.of("k"), Map.of("k", id));
		var planner = new MavenExecutionPlanner(List.of(a, b)); var plan = planner.plan(output, false);
		assertEquals("a", plan.entries().get(0).module());
		assertEquals(Map.of("a", FQN + "#alpha"), planner.selectorsByModule(plan));
	}

	private MavenProject module(String id, Path dir) throws Exception
	{
		Files.createDirectories(dir); MavenProject project = new MavenProject(); project.setArtifactId(id); project.setPackaging("jar");
		Build build = new Build(); build.setDirectory(dir.resolve("target").toString()); build.setTestOutputDirectory(dir.resolve("target/test-classes").toString()); project.setBuild(build);
		return project;
	}
	private void copyFixture(MavenProject module) throws Exception
	{
		String resource = FQN.replace('.', '/') + ".class"; Path destination = Path.of(module.getBuild().getTestOutputDirectory(), resource);
		Files.createDirectories(destination.getParent());
		try (var input = getClass().getClassLoader().getResourceAsStream(resource)) { assertNotNull(input); Files.copy(input, destination); }
	}
	private static ExecutionIdentityMetadata identity(String fqn, String method, ExecutionShape shape)
	{
		return new ExecutionIdentityMetadata(null, fqn, method, "legacy", "junit", shape);
	}
	private static SelectionOutput output(List<String> selected, Map<String, ExecutionIdentityMetadata> ids)
	{
		SelectionOutput output = new SelectionOutput("SELECTED", "test", selected, Map.of()); output.setExecutionIdentities(ids); return output;
	}
}
