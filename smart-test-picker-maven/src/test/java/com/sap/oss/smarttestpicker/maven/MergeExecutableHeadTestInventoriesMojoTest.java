// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventory;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;

class MergeExecutableHeadTestInventoriesMojoTest {
	@Test void mergesDistinctExecutionTargetsAndRejectsRevisionAndIdentityCollisions(@TempDir Path root) throws Exception {
		var codec = new ExecutableHeadTestInventoryCodec();
		var unit = identity("module@surefire@default-test", "example.Tests#unit");
		var integration = identity("module@failsafe@default-integration-test", "example.Tests#integration");
		Path first = root.resolve("first.json");
		Path second = root.resolve("second.json");
		Path output = root.resolve("merged.json");
		codec.write(first.toFile(), ExecutableHeadTestInventory.atRevision("r", List.of(unit)));
		codec.write(second.toFile(), ExecutableHeadTestInventory.atRevision("r", List.of(integration)));
		assertEquals(2, MergeExecutableHeadTestInventoriesMojo.merge(List.of(first.toFile(), second.toFile()), output.toFile()));
		assertEquals(java.util.Set.of(unit, integration), codec.read(output.toFile()).runnableTests());
		codec.write(second.toFile(), ExecutableHeadTestInventory.atRevision("other", List.of(integration)));
		assertThrows(IllegalArgumentException.class, () -> MergeExecutableHeadTestInventoriesMojo.merge(
				List.of(first.toFile(), second.toFile()), output.toFile()));
		codec.write(second.toFile(), ExecutableHeadTestInventory.atRevision("r", List.of(unit)));
		assertThrows(IllegalArgumentException.class, () -> MergeExecutableHeadTestInventoriesMojo.merge(
				List.of(first.toFile(), second.toFile()), output.toFile()));
	}

	private static ExecutableTestIdentity identity(String target, String test) {
		return new ExecutableTestIdentity(new ExecutionTarget(BuildTool.MAVEN, target), TestIdentity.parse(test));
	}
}
