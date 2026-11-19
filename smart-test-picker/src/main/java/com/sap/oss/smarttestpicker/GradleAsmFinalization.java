// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentQualifier;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableFragmentAssignmentValidator;
import org.gradle.api.GradleException;

/** Per-target acceptance boundary for a prepared schema-v3 ASM Test invocation. */
final class GradleAsmFinalization {

	private GradleAsmFinalization() {}

	static void verify(String task, String revision, String shard, String executionTarget,
			File assignmentFile, File fragmentFile, File diagnosticFile) {
		String rule = "assignment decoding and binding";
		try {
			var assignment = new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(assignmentFile.toPath()));
			if (!revision.equals(assignment.revision().value()) || !shard.equals(assignment.shardId().value())) {
				throw new IllegalArgumentException("Assignment revision/shard does not match the prepared invocation");
			}
			var target = ExecutionTarget.parse(executionTarget);
			var assigned = new TreeSet<ExecutableTestIdentity>();
			assignment.tests().stream().filter(identity -> identity.target().equals(target)).forEach(assigned::add);
			if (assigned.isEmpty()) throw new IllegalArgumentException("No assigned identities for " + target);

			rule = "agent diagnostic decoding";
			com.google.gson.JsonObject diagnostic;
			try (var reader = Files.newBufferedReader(diagnosticFile.toPath())) {
				diagnostic = JsonParser.parseReader(reader).getAsJsonObject();
			}
			rule = "agent diagnostic schemaVersion";
			if (!"agent-shell-1".equals(diagnostic.get("schemaVersion").getAsString())) {
				throw new IllegalArgumentException("Expected agent-shell-1");
			}
			rule = "diagnostic configuration";
			var configuration = diagnostic.getAsJsonObject("configuration");
			rule = "diagnostic revision binding";
			if (!revision.equals(configuration.get("revision").getAsString())) {
				throw new IllegalArgumentException("Expected revision " + revision);
			}
			rule = "diagnostic shardId binding";
			if (!shard.equals(configuration.get("shardId").getAsString())) {
				throw new IllegalArgumentException("Expected shardId " + shard);
			}
			rule = "diagnostic fragmentOutput binding";
			if (!fragmentFile.toPath().toAbsolutePath().normalize().equals(
					Path.of(configuration.get("fragmentOutput").getAsString()).toAbsolutePath().normalize())) {
				throw new IllegalArgumentException("Expected fragmentOutput " + fragmentFile);
			}
			rule = "diagnostic metrics.classesTransformed";
			long transformed = diagnostic.getAsJsonObject("metrics").get("classesTransformed").getAsLong();
			if (transformed <= 0) {
				throw new IllegalArgumentException("ASM scope matched zero instrumented classes: classesTransformed="
						+ transformed + "; includes=" + configuration.get("includes")
						+ "; excludes=" + configuration.get("excludes")
						+ "; productionSources=" + configuration.get("productionSources"));
			}

			rule = "fragment decoding";
			var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragmentFile.toPath()));
			rule = "fragment completion";
			if (!fragment.collectionCompleted()) throw new IllegalArgumentException("ASM fragment collection is incomplete");
			rule = "fragment target assignment membership";
			var unexpected = new TreeSet<>(fragment.tests().keySet());
			fragment.unmapped().forEach(entry -> unexpected.add(entry.test()));
			unexpected.removeAll(assigned);
			if (!unexpected.isEmpty()) throw new IllegalArgumentException("Unexpected identities outside target assignment: " + unexpected);
			rule = "fragment execution target and setup-scope owner";
			new ExecutableCoverageFragmentQualifier().requireTarget(fragment, target);
			rule = "fragment revision/shard binding";
			if (!assignment.revision().equals(fragment.revision()))
				throw new IllegalArgumentException("Fragment revision does not match assignment revision");
			if (!assignment.shardId().equals(fragment.shardId()))
				throw new IllegalArgumentException("Fragment shard does not match assignment shard");
			rule = "fragment assignment absorption audit";
			com.sap.oss.smarttestpicker.coverage.ExecutableAssignmentAbsorption.Result absorption;
			try (var reader = Files.newBufferedReader(fragmentFile.toPath())) {
				absorption = com.sap.oss.smarttestpicker.coverage.ExecutableAssignmentAbsorption.apply(
						fragment, assigned, JsonParser.parseReader(reader).getAsJsonObject());
			}
			fragment = absorption.fragment();
			rule = "fragment unmapped outcome";
			for (var entry : fragment.unmapped()) {
				if (entry.reason() != UnmappedReason.SKIPPED && entry.reason() != UnmappedReason.FAILED
						&& entry.reason() != UnmappedReason.COLLECTION_FAILED) {
					throw new IllegalArgumentException("Unsafe ASM collection outcome: " + entry.test() + " " + entry.reason());
				}
			}
			rule = "fragment revision/shard binding and exact assignment accounting";
			ExecutableFragmentAssignmentValidator.validate(fragment, new ExecutableShardAssignment(
					assignment.version(), assignment.revision(), assignment.shardId(), assigned));
			rule = "normalized fragment write";
			absorption.writeIfChanged(fragmentFile.toPath());
			if (!absorption.absorbed().isEmpty()) {
				org.gradle.api.logging.Logging.getLogger(GradleAsmFinalization.class).warn(
						"[SmartTestPicker] " + task + " absorbed " + absorption.absorbed().size()
						+ " assigned tests as COLLECTION_FAILED: ASSIGNED_TEST_NOT_REPORTED " + absorption.absorbed());
			}
		} catch (Exception failure) {
			throw new GradleException("ASM finalization for " + task + " rejected " + rule + ": " + failure.getMessage(), failure);
		}
	}
}
