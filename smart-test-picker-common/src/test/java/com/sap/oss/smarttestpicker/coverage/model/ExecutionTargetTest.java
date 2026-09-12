// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.model;

import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionTargetTest
{
	@Test void acceptsCanonicalMavenTargets()
	{
		assertEquals("maven:.", new ExecutionTarget(BuildTool.MAVEN, ".").toString());
		assertEquals("maven:java-checks", new ExecutionTarget(BuildTool.MAVEN, "java-checks").toString());
		assertEquals("maven:java-checks-test-sources/default",
				new ExecutionTarget(BuildTool.MAVEN, "java-checks-test-sources/default").toString());
	}

	@Test void acceptsCanonicalGradleTasks()
	{
		for (String task : List.of(":test", ":spring-core:test", ":module-a:integrationTest"))
			assertEquals(task, new ExecutionTarget(BuildTool.GRADLE, task).targetId());
	}

	@Test void rejectsNullBlankControlAndAmbiguousInput()
	{
		assertThrows(IllegalArgumentException.class, () -> new ExecutionTarget(null, "module"));
		for (String invalid : List.of("", " ", " module", "module ", "line\nbreak"))
			assertThrows(IllegalArgumentException.class, () -> new ExecutionTarget(BuildTool.MAVEN, invalid));
	}

	@Test void rejectsMalformedMavenTargets()
	{
		for (String invalid : List.of("/module", "module/", "./module", "module/./child", "module/../child",
				"module//child", "module\\child", "module:child"))
			assertThrows(IllegalArgumentException.class, () -> new ExecutionTarget(BuildTool.MAVEN, invalid));
	}

	@Test void rejectsMalformedGradleTargets()
	{
		for (String invalid : List.of("test", ":", ":module:", ":module::test", ":module/test",
				":module\\test", ":module: test"))
			assertThrows(IllegalArgumentException.class, () -> new ExecutionTarget(BuildTool.GRADLE, invalid));
	}

	@Test void canonicalFormRoundTrips()
	{
		for (String value : List.of("maven:.", "maven:module/nested", "gradle::test", "gradle::module:test"))
			assertEquals(value, ExecutionTarget.parse(value).toString());
		assertThrows(IllegalArgumentException.class, () -> ExecutionTarget.parse("MAVEN:module"));
	}

	@Test void naturalOrderingUsesBuildToolThenTargetId()
	{
		ExecutionTarget a = new ExecutionTarget(BuildTool.MAVEN, "a");
		ExecutionTarget z = new ExecutionTarget(BuildTool.MAVEN, "z");
		ExecutionTarget gradle = new ExecutionTarget(BuildTool.GRADLE, ":a:test");
		assertEquals(List.of(a, z, gradle), List.copyOf(new TreeSet<>(List.of(gradle, z, a))));
	}
}
