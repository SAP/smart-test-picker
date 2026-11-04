// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;

/** Existing Maven mapping evidence v2; distinct from managed-selection evidence and agent diagnostics. */
record MavenMappingEvidence(String revision, String shardId, ExecutionTarget target,
		Set<ExecutableTestIdentity> executed, Set<ExecutableTestIdentity> nonExecuted) {
	MavenMappingEvidence {
		if (revision == null || revision.isBlank() || shardId == null || shardId.isBlank())
			throw new IllegalArgumentException("Missing execution evidence binding");
		executed = Set.copyOf(executed); nonExecuted = Set.copyOf(nonExecuted);
		Set<ExecutableTestIdentity> overlap = new TreeSet<>(executed); overlap.retainAll(nonExecuted);
		if (!overlap.isEmpty()) throw new IllegalArgumentException("EXECUTED/NON_EXECUTED overlap: " + overlap);
		if (target != null) for (var identity : union(executed, nonExecuted))
			if (!target.equals(identity.target())) throw new IllegalArgumentException("Execution evidence target mismatch: " + identity);
	}

	static MavenMappingEvidence read(Path path) throws java.io.IOException {
		JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		if (root.get("version").getAsInt() != 2) throw new IllegalArgumentException("Unsupported execution evidence version");
		return new MavenMappingEvidence(root.get("revision").getAsString(), root.get("shardId").getAsString(),
				root.has("executionTarget") ? ExecutionTarget.parse(root.get("executionTarget").getAsString()) : null,
				identities(root, "EXECUTED"), identities(root, "NON_EXECUTED"));
	}

	String moduleJson() {
		if (target == null) throw new IllegalArgumentException("Module execution evidence requires a target");
		JsonObject root = new JsonObject(); root.addProperty("version", 2);
		root.addProperty("revision", revision); root.addProperty("shardId", shardId);
		root.addProperty("executionTarget", target.toString());
		root.add("EXECUTED", array(executed)); root.add("NON_EXECUTED", array(nonExecuted));
		return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
	}

	/** SKIPPED is a positive non-execution observation, not an executed coverage owner. */
	static Set<ExecutableTestIdentity> coverageOwners(ExecutableCoverageFragment fragment) {
		Set<ExecutableTestIdentity> owners = new TreeSet<>(fragment.tests().keySet());
		fragment.unmapped().stream().filter(u -> u.reason() != UnmappedReason.SKIPPED).forEach(u -> owners.add(u.test()));
		return owners;
	}

	private static Set<ExecutableTestIdentity> identities(JsonObject root, String name) {
		Set<ExecutableTestIdentity> result = new TreeSet<>();
		for (var value : root.getAsJsonArray(name)) {
			var identity = ExecutableTestIdentity.parse(value.getAsString());
			if (!result.add(identity)) throw new IllegalArgumentException("Duplicate evidence identity: " + identity);
		}
		return result;
	}
	private static JsonArray array(Set<ExecutableTestIdentity> values) {
		JsonArray result = new JsonArray(); new TreeSet<>(values).forEach(v -> result.add(v.toString())); return result;
	}
	private static Set<ExecutableTestIdentity> union(Set<ExecutableTestIdentity> a, Set<ExecutableTestIdentity> b) {
		Set<ExecutableTestIdentity> result = new TreeSet<>(a); result.addAll(b); return result;
	}
}
