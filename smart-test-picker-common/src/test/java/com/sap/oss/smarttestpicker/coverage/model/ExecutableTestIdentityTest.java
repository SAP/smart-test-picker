// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutableTestIdentityTest
{
	private static final TestIdentity LOGICAL = TestIdentity.parse("com.example.Test#works");
	private static final ExecutionTarget MAVEN_A = new ExecutionTarget(BuildTool.MAVEN, "module-a");

	@Test void equalityIncludesTargetAndLogicalTest()
	{
		assertEquals(new ExecutableTestIdentity(MAVEN_A, LOGICAL), new ExecutableTestIdentity(MAVEN_A, LOGICAL));
		assertNotEquals(new ExecutableTestIdentity(MAVEN_A, LOGICAL),
				new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, "module-b"), LOGICAL));
		assertNotEquals(new ExecutableTestIdentity(MAVEN_A, LOGICAL),
				new ExecutableTestIdentity(MAVEN_A, TestIdentity.parse("com.example.Test#other")));
	}

	@Test void canonicalFormRoundTripsIncludingMavenRootAndLogicalPunctuation()
	{
		for (String value : List.of("maven:.::com.example.Test#works",
				"maven:module-a::com.example.Test#name::with:colons",
				"gradle::module-a:test::com.example.Test#works"))
			assertEquals(value, ExecutableTestIdentity.parse(value).toString());
	}

	@Test void sonarJavaDuplicateHasOneLogicalAndTwoExecutableIdentities()
	{
		TestIdentity logical = TestIdentity.parse(
				"org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage");
		ExecutableTestIdentity common = executable(BuildTool.MAVEN, "java-checks-common", logical);
		ExecutableTestIdentity checks = executable(BuildTool.MAVEN, "java-checks", logical);

		assertNotEquals(common, checks);
		assertSame(common.test(), checks.test());
		assertNotEquals(common.toString(), checks.toString());
		assertEquals("maven:java-checks-common::" + logical, common.toString());
		assertEquals("maven:java-checks::" + logical, checks.toString());
	}

	@Test void sameLogicalTestIsDistinctAcrossBuildTools()
	{
		ExecutableTestIdentity maven = executable(BuildTool.MAVEN, "module-a", LOGICAL);
		ExecutableTestIdentity gradle = executable(BuildTool.GRADLE, ":module-a:test", LOGICAL);
		assertNotEquals(maven, gradle);
		assertEquals(maven.test(), gradle.test());
	}

	@Test void naturalOrderingUsesTargetThenLogicalTest()
	{
		ExecutableTestIdentity firstTest = executable(BuildTool.MAVEN, "a", LOGICAL);
		ExecutableTestIdentity secondTest = executable(BuildTool.MAVEN, "a", TestIdentity.parse("com.example.Test#z"));
		ExecutableTestIdentity secondTarget = executable(BuildTool.MAVEN, "b", LOGICAL);
		assertEquals(List.of(firstTest, secondTest, secondTarget),
				List.copyOf(new TreeSet<>(List.of(secondTarget, secondTest, firstTest))));
	}

	@Test void rejectsMissingComponents()
	{
		assertThrows(IllegalArgumentException.class, () -> new ExecutableTestIdentity(null, LOGICAL));
		assertThrows(IllegalArgumentException.class, () -> new ExecutableTestIdentity(MAVEN_A, null));
	}

	private static ExecutableTestIdentity executable(BuildTool tool, String target, TestIdentity test)
	{
		return new ExecutableTestIdentity(new ExecutionTarget(tool, target), test);
	}
}
