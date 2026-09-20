// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.coverage.ExecutionPlanContract;
import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlanMode;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

class MavenExecutionEvidenceTest
{
	@Test void verifiesLogicalParameterizedIdentityWithoutCountingInvocationsAsDuplicates(@TempDir Path temporary)
			throws Exception
	{
		ExecutionTarget target = new ExecutionTarget(BuildTool.MAVEN, "module-a");
		ExecutableTestIdentity identity = new ExecutableTestIdentity(target,
				new TestIdentity("example.ParameterizedTest", "works", "java.lang.String"));
		Path evidence = temporary.resolve("target/jacoco"); Files.createDirectories(evidence);
		Files.writeString(evidence.resolve("session_parameterized.identity"), """
				format=1
				className=example.ParameterizedTest
				methodName=works
				methodParameterTypes=java.lang.String
				outcome=PASS
				""");
		var verification = MavenExecutionEvidence.verify(plan(identity), Map.of(target, project(temporary)), true);
		assertTrue(verification.valid());
		assertEquals(Set.of(identity), verification.executed());
		assertTrue(verification.duplicates().isEmpty());
	}

	@Test void distinguishesMissingUnexpectedDuplicateFailedAndNonExecuted(@TempDir Path temporary) throws Exception
	{
		ExecutionTarget target = new ExecutionTarget(BuildTool.MAVEN, "module-a");
		ExecutableTestIdentity planned = executable(target, "example.PlannedTest", "planned");
		Path evidence = temporary.resolve("target/jacoco"); Files.createDirectories(evidence);
		String unexpected = "format=1\nclassName=example.OtherTest\nmethodName=other\nmethodParameterTypes=\noutcome=FAIL\n";
		Files.writeString(evidence.resolve("session_one.identity"), unexpected);
		Files.writeString(evidence.resolve("session_two.identity"), unexpected);
		Files.writeString(evidence.resolve("session_skipped.non-executed"),
				"format=1\nclassName=example.SkippedTest\nmethodName=skipped\nmethodParameterTypes=\n");
		var verification = MavenExecutionEvidence.verify(plan(planned), Map.of(target, project(temporary)), false);
		assertFalse(verification.valid());
		assertEquals(Set.of(planned), verification.missing());
		assertEquals(1, verification.failed().size());
		assertEquals(1, verification.duplicates().size());
		assertEquals(2, verification.unexpected().size());
		assertFalse(verification.complete());
	}

	@Test void keepsIdenticalTestsOwnedByDifferentQualifiedExecutionsDistinct(@TempDir Path temporary) throws Exception
	{
		ExecutionTarget unit = new ExecutionTarget(BuildTool.MAVEN, "module-a@surefire@unit-tests");
		ExecutionTarget integration = new ExecutionTarget(BuildTool.MAVEN, "module-a@failsafe@integration-tests");
		ExecutableTestIdentity unitTest = executable(unit, "example.SameTest", "works");
		ExecutableTestIdentity integrationTest = executable(integration, "example.SameTest", "works");
		String marker = "format=1\nclassName=example.SameTest\nmethodName=works\nmethodParameterTypes=\noutcome=PASS\n";
		Path unitEvidence = temporary.resolve("target/stp/evidence-surefire-unit-tests");
		Path integrationEvidence = temporary.resolve("target/stp/evidence-failsafe-integration-tests");
		Files.createDirectories(unitEvidence); Files.createDirectories(integrationEvidence);
		Files.writeString(unitEvidence.resolve("session_unit.identity"), marker);
		Files.writeString(integrationEvidence.resolve("session_integration.identity"), marker);
		ExecutionPlan plan = new ExecutionPlan(ExecutionPlanContract.VERSION, ExecutionPlanMode.SELECT,
				Set.of(unitTest, integrationTest), null);
		MavenProject project = project(temporary);
		var verification = MavenExecutionEvidence.verify(plan,
				Map.of(unit, project, integration, project), true);
		assertTrue(verification.valid());
		assertEquals(Set.of(unitTest, integrationTest), verification.executed());
		assertTrue(verification.duplicates().isEmpty());
	}

	private static ExecutionPlan plan(ExecutableTestIdentity identity)
	{
		return new ExecutionPlan(ExecutionPlanContract.VERSION, ExecutionPlanMode.SELECT, Set.of(identity), null);
	}

	private static ExecutableTestIdentity executable(ExecutionTarget target, String owner, String method)
	{
		return new ExecutableTestIdentity(target, new TestIdentity(owner, method));
	}

	private static MavenProject project(Path directory)
	{
		MavenProject project = new MavenProject(); Build build = new Build();
		build.setDirectory(directory.resolve("target").toString()); project.setBuild(build);
		return project;
	}
}
