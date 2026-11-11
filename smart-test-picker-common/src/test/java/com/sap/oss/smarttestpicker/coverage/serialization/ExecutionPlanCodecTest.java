// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sap.oss.smarttestpicker.coverage.ExecutionPlanContract;
import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlanMode;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;

class ExecutionPlanCodecTest
{
	private final ExecutionPlanCodec codec = new ExecutionPlanCodec();

	@Test void deterministicRoundTripPreservesQualifiedTargetsAndRevision()
	{
		ExecutionPlan plan = new ExecutionPlan(ExecutionPlanContract.VERSION, ExecutionPlanMode.SELECT, Set.of(
				new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, "nested/module@surefire@unit"),
						new TestIdentity("com.acme.SampleTest", "parameterized", "java.lang.String")),
				new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, "."),
						new TestIdentity("com.acme.RootTest", "works"))),
				new CoverageMapRevision("0123456789abcdef0123456789abcdef01234567"));
		byte[] first = codec.serialize(plan);
		ExecutionPlan decoded = codec.deserialize(first);
		assertEquals(plan, decoded);
		assertArrayEquals(first, codec.serialize(decoded));
	}

	@Test void rejectsHigherVersionDuplicateAndMissingTarget()
	{
		assertThrows(IllegalArgumentException.class, () -> codec.deserialize(json("""
				{"version":2,"mode":"SELECT","tests":[]}
				""")));
		assertThrows(IllegalArgumentException.class, () -> codec.deserialize(json("""
				{"version":1,"mode":"SELECT","tests":[
				 {"target":"maven:module","class":"com.acme.T","method":"m"},
				 {"target":"maven:module","class":"com.acme.T","method":"m"}]}
				""")));
		assertThrows(IllegalArgumentException.class, () -> codec.deserialize(json("""
				{"version":1,"mode":"SELECT","tests":[{"class":"com.acme.T","method":"m"}]}
				""")));
	}

	private static byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
