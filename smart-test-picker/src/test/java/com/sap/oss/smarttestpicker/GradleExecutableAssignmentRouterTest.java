// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.ExecutableAssignmentOrchestrator;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableSelectionResult;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

import static org.junit.jupiter.api.Assertions.*;

class GradleExecutableAssignmentRouterTest {
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {
			"Core BO class should contain @ReferencedBy with bo.input when only bo-input references it -> CXMC-52873 TC-03.2",
			"execute uses restTemplate.exchange with the given HTTP method",
			"Should import allowed type [distributedImpexEnabled: #distributedImpexEnabled, maxThreads: #maxThreads, synchronous: #synchronous] -> TC-03 CXMC-33683",
			"Should fail to import/modify/remove non-dynamic enum type for ImpEx mode: (#impExOperation) and impex.allowtype.violation: '#violationValue'-> TC-02 CXMC-33298"
	})
	void realGradleMatcherKeepsFreeTextMethodExact(String name) {
		var matcher = new org.gradle.api.internal.tasks.testing.filter.TestSelectionMatcher(
				new org.gradle.api.internal.tasks.testing.filter.TestFilterSpec(
						Set.of(GradleExecutableAssignmentRouter.gradleFilter("example.Spec", name)), Set.of(), Set.of()));
		assertTrue(matcher.mayIncludeClass("example.Spec"));
		assertTrue(matcher.matchesTest("example.Spec", name));
		assertFalse(matcher.matchesTest("example.Spec", "sibling"));
		assertFalse(matcher.matchesTest("example.Spec", name + " sibling"));
	}

	@Test void wildcardClassDiscoveryIsBoundedToTheAssignment(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
		var project = org.gradle.testfixtures.ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
		var task = project.getTasks().create("assignedTest", org.gradle.api.tasks.testing.Test.class);
		var classes = directory.resolve("classes");
		for (String path : List.of("example/Spec.class", "otherexample/Spec.class")) {
			java.nio.file.Files.createDirectories(classes.resolve(path).getParent());
			java.nio.file.Files.write(classes.resolve(path), new byte[0]);
		}
		task.setTestClassesDirs(project.files(classes));
		GradleExecutableAssignmentRouter.configureFilter(task, java.util.Map.of("example.Spec", Set.of("uses api.call")));
		assertEquals(Set.of(classes.resolve("example/Spec.class").toFile()), task.getCandidateClassFiles().getFiles());
		assertThrows(IllegalArgumentException.class, () -> GradleExecutableAssignmentRouter.configureFilter(task,
				java.util.Map.of("example.Spec", Set.of("uses api.call"), "otherexample.Spec", Set.of("sibling"))));
	}

	@Test void productionSelectionAssignmentTransportAndDispatchPreservesStrictSubset() {
		ExecutionTarget test = new ExecutionTarget(BuildTool.GRADLE, ":test");
		ExecutionTarget integration = new ExecutionTarget(BuildTool.GRADLE, ":integrationTest");
		TestIdentity logical = TestIdentity.parse("example.SharedTest#works");
		byte[][] transported = new byte[1][];
		new ExecutableAssignmentOrchestrator().dispatch(new ExecutableSelectionResult(
				new CoverageMapRevision("r"), Set.of(new ExecutableTestIdentity(integration, logical))),
				new CoverageMapRevision("r"), new ShardId("s"), BuildTool.GRADLE, bytes -> transported[0] = bytes);
		var decoded = new ExecutableShardAssignmentCodec().deserialize(transported[0]);
		var partitions = new GradleExecutableAssignmentRouter().partition(decoded, "r", "s", Set.of(test, integration));
		assertEquals(Set.of(), partitions.get(test));
		assertEquals(Set.of(logical), partitions.get(integration));
	}
	private static final ExecutionTarget TEST = new ExecutionTarget(BuildTool.GRADLE, ":module-a:test");
	private static final ExecutionTarget IT = new ExecutionTarget(BuildTool.GRADLE, ":module-a:integrationTest");
	private static final TestIdentity SHARED = new TestIdentity("example.SharedTest", "same", "java.lang.String");

	@Test void partitionsTheSameLogicalIdentityByExactTaskAndPreservesSignature() {
		var assignment = assignment(Set.of(new ExecutableTestIdentity(TEST, SHARED),
				new ExecutableTestIdentity(IT, SHARED)));
		var result = new GradleExecutableAssignmentRouter().partition(assignment, "r", "s", List.of(TEST, IT));
		assertEquals(Set.of(SHARED), result.get(TEST));
		assertEquals(Set.of(SHARED), result.get(IT));
		assertEquals("example.SharedTest.same", GradleExecutableAssignmentRouter.gradleFilter(SHARED));
	}

	@Test void retainsKnownTasksWithZeroAssignments() {
		var result = new GradleExecutableAssignmentRouter().partition(assignment(Set.of()), "r", "s", List.of(TEST));
		assertEquals(Set.of(), result.get(TEST));
	}

	@Test void failsClosedForBindingToolAndTopologyErrors() {
		assertThrows(IllegalArgumentException.class, () -> new GradleExecutableAssignmentRouter()
				.partition(assignment(Set.of()), "other", "s", List.of(TEST)));
		assertThrows(IllegalArgumentException.class, () -> new GradleExecutableAssignmentRouter()
				.partition(assignment(Set.of()), "r", "other", List.of(TEST)));
		assertThrows(IllegalArgumentException.class, () -> new GradleExecutableAssignmentRouter().partition(
				assignment(Set.of(new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, "."), SHARED))),
				"r", "s", List.of(TEST)));
		assertThrows(IllegalArgumentException.class, () -> new GradleExecutableAssignmentRouter().partition(
				assignment(Set.of(new ExecutableTestIdentity(IT, SHARED))), "r", "s", List.of(TEST)));
	}

	private static ExecutableShardAssignment assignment(Set<ExecutableTestIdentity> tests) {
		return new ExecutableShardAssignment(1, new CoverageMapRevision("r"), new ShardId("s"), tests);
	}
}
