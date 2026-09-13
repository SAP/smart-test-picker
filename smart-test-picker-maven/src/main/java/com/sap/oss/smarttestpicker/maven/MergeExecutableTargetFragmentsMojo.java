// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableCoverageMapValidator;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableFragmentAssignmentValidator;

/** Joins independently invoked Maven test-execution targets into one exact Jenkins shard result. */
@Mojo(name = "merge-executable-target-fragments", aggregator = true)
public final class MergeExecutableTargetFragmentsMojo extends AbstractMojo {
	@Parameter(property = "smartTestPicker.fragmentFiles", required = true) private String fragmentFiles;
	@Parameter(property = "smartTestPicker.evidenceFiles", required = true) private String evidenceFiles;
	@Parameter(defaultValue = "${env.STP_MAPPING_TESTS_FILE}", property = "smartTestPicker.testsFile", required = true)
	private File assignmentFile;
	@Parameter(property = "smartTestPicker.revision", required = true) private String revision;
	@Parameter(property = "smartTestPicker.shardId", required = true) private String shardId;
	@Parameter(property = "smartTestPicker.fragmentOutput", required = true) private File fragmentOutput;
	@Parameter(property = "smartTestPicker.evidenceOutput", required = true) private File evidenceOutput;

	@Override public void execute() throws MojoExecutionException {
		try {
			var fragments = files(fragmentFiles);
			var evidence = files(evidenceFiles);
			if (fragments.size() != evidence.size() || fragments.isEmpty())
				throw new IllegalArgumentException("Fragment and evidence file lists must be non-empty and aligned");
			var assignment = new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(assignmentFile.toPath()));
			binding(assignment.revision().value(), assignment.shardId().value());
			merge(fragments, evidence, assignment, revision, shardId, fragmentOutput, evidenceOutput);
		} catch (Exception failure) {
			try { Files.deleteIfExists(fragmentOutput.toPath()); Files.deleteIfExists(evidenceOutput.toPath()); }
			catch (Exception ignored) { }
			throw new MojoExecutionException("Cannot merge Maven execution-target fragments: " + failure.getMessage(), failure);
		}
	}

	static void merge(java.util.List<File> fragmentFiles, java.util.List<File> evidenceFiles,
			ExecutableShardAssignment assignment, String revision, String shardId, File fragmentOutput,
			File evidenceOutput) throws Exception {
		Map<ExecutableTestIdentity,TestCoverage> mapped = new TreeMap<>();
		Map<ExecutableTestIdentity,ExecutableUnmappedTest> unmapped = new TreeMap<>();
		Map<String,SetupScope> scopes = new TreeMap<>();
		Set<ExecutableTestIdentity> executed = new TreeSet<>(), nonExecuted = new TreeSet<>(), evidenceOwners = new TreeSet<>();
		var codec = new ExecutableCoverageFragmentCodec();
		for (int index = 0; index < fragmentFiles.size(); index++) {
			ExecutableCoverageFragment fragment = codec.deserialize(Files.readAllBytes(fragmentFiles.get(index).toPath()));
			if (!revision.equals(fragment.revision().value()) || !shardId.equals(fragment.shardId().value()))
				throw new IllegalArgumentException("Execution-target fragment binding mismatch");
			if (!fragment.collectionCompleted() || !ExecutableCoverageMapValidator.validate(fragment).isValid())
				throw new IllegalArgumentException("Invalid or incomplete execution-target fragment");
			for (var entry : fragment.tests().entrySet()) put(mapped, unmapped, entry.getKey(), entry.getValue(), null);
			for (var entry : fragment.unmapped()) put(mapped, unmapped, entry.test(), null, entry);
			for (var scope : fragment.setupScopes()) {
				var old = scopes.putIfAbsent(scope.id(), scope);
				if (old != null && !old.equals(scope)) throw new IllegalArgumentException("Incompatible setup scope: " + scope.id());
			}
			JsonObject root = JsonParser.parseString(Files.readString(evidenceFiles.get(index).toPath())).getAsJsonObject();
			if (root.get("version").getAsInt() != 2 || !revision.equals(root.get("revision").getAsString())
					|| !shardId.equals(root.get("shardId").getAsString()))
				throw new IllegalArgumentException("Execution-target evidence binding mismatch");
			add(root, "EXECUTED", executed, evidenceOwners);
			add(root, "NON_EXECUTED", nonExecuted, evidenceOwners);
		}
		Set<ExecutableTestIdentity> overlap = new TreeSet<>(executed); overlap.retainAll(nonExecuted);
		if (!overlap.isEmpty()) throw new IllegalArgumentException("EXECUTED/NON_EXECUTED overlap: " + overlap);
		if (!evidenceOwners.equals(assignment.tests())) throw new IllegalArgumentException("Target evidence does not own exactly the shard assignment");
		Set<ExecutableTestIdentity> coverageOwners = new TreeSet<>(mapped.keySet()); coverageOwners.addAll(unmapped.keySet());
		if (!coverageOwners.equals(executed)) throw new IllegalArgumentException("Coverage owners differ from executed target evidence");
		var result = new ExecutableCoverageFragment(CoverageMapContract.SCHEMA_V3,
				new CoverageMapRevision(revision), new ShardId(shardId), mapped,
				new ArrayList<>(unmapped.values()), new ArrayList<>(scopes.values()), true);
		ExecutableFragmentAssignmentValidator.validate(result, assignment, nonExecuted);
		Files.createDirectories(fragmentOutput.toPath().getParent());
		Files.write(fragmentOutput.toPath(), codec.serialize(result));
		JsonObject root = new JsonObject(); root.addProperty("version", 2); root.addProperty("revision", revision);
		root.addProperty("shardId", shardId); root.addProperty("buildTool", "maven");
		root.add("EXECUTED", array(executed)); root.add("NON_EXECUTED", array(nonExecuted));
		Files.createDirectories(evidenceOutput.toPath().getParent());
		Files.writeString(evidenceOutput.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n",
				StandardCharsets.UTF_8);
	}

	private static void put(Map<ExecutableTestIdentity,TestCoverage> mapped,
			Map<ExecutableTestIdentity,ExecutableUnmappedTest> unmapped, ExecutableTestIdentity identity,
			TestCoverage coverage, ExecutableUnmappedTest absent) {
		if (mapped.containsKey(identity) || unmapped.containsKey(identity))
			throw new IllegalArgumentException("Duplicate executable identity across target fragments: " + identity);
		if (coverage != null) mapped.put(identity, coverage); else unmapped.put(identity, absent);
	}

	private static void add(JsonObject root, String name, Set<ExecutableTestIdentity> destination,
			Set<ExecutableTestIdentity> owners) {
		for (var value : root.getAsJsonArray(name)) {
			var identity = ExecutableTestIdentity.parse(value.getAsString());
			if (!owners.add(identity)) throw new IllegalArgumentException("Duplicate execution evidence identity: " + identity);
			destination.add(identity);
		}
	}

	private static JsonArray array(Set<ExecutableTestIdentity> identities) {
		JsonArray result = new JsonArray(); identities.forEach(identity -> result.add(identity.toString())); return result;
	}

	private static java.util.List<File> files(String values) {
		if (values == null) return java.util.List.of();
		return java.util.Arrays.stream(values.split(",", -1)).map(String::trim)
				.peek(value -> { if (value.isEmpty()) throw new IllegalArgumentException("Malformed file list"); })
				.map(File::new).toList();
	}

	private void binding(String actualRevision, String actualShard) {
		if (!revision.equals(actualRevision)) throw new IllegalArgumentException("Executable assignment revision mismatch");
		if (!shardId.equals(actualShard)) throw new IllegalArgumentException("Executable assignment shardId mismatch");
	}
}
