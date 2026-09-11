// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidator;
import com.sap.oss.smarttestpicker.selector.JUnitHeadTestInventoryGenerator;

/** Collapses module-local schema-v2 results into one Jenkins shard result. */
@Mojo(name = "aggregate-reactor-coverage-fragment", aggregator = true,
		defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.TEST)
public final class AggregateReactorCoverageFragmentMojo extends AbstractMojo {
	@Parameter(defaultValue = "${project}", readonly = true, required = true) private MavenProject project;
	@Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
	private List<MavenProject> reactorProjects;
	@Parameter(property = "smartTestPicker.revision", required = true) private String revision;
	@Parameter(property = "smartTestPicker.shardId", required = true) private String shardId;
	@Parameter(defaultValue = "test", property = "smartTestPicker.testTarget") private String testTarget;
	@Parameter(property = "smartTestPicker.fragmentOutput", required = true) private File fragmentOutput;
	@Parameter(property = "smartTestPicker.evidenceOutput", required = true) private File evidenceOutput;
	@Parameter(defaultValue = "${env.STP_MAPPING_TESTS_FILE}", property = "smartTestPicker.testsFile") private File shardAssignments;

	@Override public void execute() throws MojoExecutionException {
		validateParameters();
		invalidateFinals();
		try {
			Set<TestIdentity> assigned = readAssignments(shardAssignments.toPath());
			Aggregate aggregate = aggregate(assigned);
			publishPair(new CoverageFragmentCodec().serialize(aggregate.fragment()), aggregate.evidence());
			getLog().info("[SmartTestPicker] Aggregated " + aggregate.fragment().tests().size()
					+ " mapped and " + aggregate.fragment().unmapped().size() + " unmapped tests into " + fragmentOutput);
		} catch (Exception failure) {
			invalidateFinals();
			throw failure instanceof MojoExecutionException mojo ? mojo
					: new MojoExecutionException("Maven reactor coverage aggregation failed: " + failure.getMessage(), failure);
		}
	}

	private Aggregate aggregate(Set<TestIdentity> assigned) throws Exception {
		Map<TestIdentity, TestCoverage> mapped = new TreeMap<>();
		Map<TestIdentity, UnmappedTest> unmapped = new TreeMap<>();
		Map<String, SetupScope> scopes = new TreeMap<>();
		Set<TestIdentity> evidenceOwners = new TreeSet<>(), executed = new TreeSet<>(), nonExecuted = new TreeSet<>();
		Set<TestIdentity> discoveredAssigned = new TreeSet<>();
		CoverageFragmentCodec codec = new CoverageFragmentCodec();

		List<MavenProject> modules = reactorProjects.stream().filter(p -> !"pom".equals(p.getPackaging()))
				.sorted((a,b) -> coordinates(a).compareTo(coordinates(b))).toList();
		for (MavenProject module : modules) {
			Set<TestIdentity> moduleAssigned = discoverAssigned(module, assigned);
			discoveredAssigned.addAll(moduleAssigned);
			Path directory = Path.of(module.getBuild().getDirectory()).resolve("stp");
			Path fragmentFile = directory.resolve("coverage-fragment-v2.json");
			Path evidenceFile = directory.resolve("execution-evidence-v1.json");
			boolean fragmentExists = Files.isRegularFile(fragmentFile), evidenceExists = Files.isRegularFile(evidenceFile);
			if (!fragmentExists && !evidenceExists && moduleAssigned.isEmpty()) continue;
			if (!fragmentExists) throw new IllegalStateException("Missing module coverage fragment for " + coordinates(module));
			if (!evidenceExists) throw new IllegalStateException("Missing module execution evidence for " + coordinates(module));

			CoverageFragment fragment = codec.deserialize(Files.readAllBytes(fragmentFile));
			var validation = CoverageMapValidator.validate(fragment);
			if (!validation.isValid()) throw new IllegalStateException("Invalid module coverage fragment for " + coordinates(module));
			if (fragment.schemaVersion() != CoverageMapContract.SCHEMA_VERSION) throw new IllegalStateException("Schema mismatch");
			binding(fragment.revision().value(), fragment.shardId().value(), coordinates(module));
			if (!fragment.collectionCompleted()) throw new IllegalStateException("Incomplete module coverage fragment for " + coordinates(module));
			for (var entry : fragment.tests().entrySet()) {
				requireAssigned(entry.getKey(), assigned);
				if (mapped.containsKey(entry.getKey()) || unmapped.containsKey(entry.getKey())) duplicate(entry.getKey());
				mapped.put(entry.getKey(), entry.getValue());
			}
			for (UnmappedTest entry : fragment.unmapped()) {
				requireAssigned(entry.test(), assigned);
				if (mapped.containsKey(entry.test()) || unmapped.containsKey(entry.test())) duplicate(entry.test());
				unmapped.put(entry.test(), entry);
			}
			for (SetupScope scope : fragment.setupScopes()) {
				SetupScope previous = scopes.putIfAbsent(scope.id(), scope);
				if (previous != null && !previous.equals(scope))
					throw new IllegalStateException("Incompatible duplicate setup scope: " + scope.id());
			}
			Evidence evidence = readEvidence(evidenceFile);
			binding(evidence.revision(), evidence.shardId(), coordinates(module));
			if (!testTarget.equals(evidence.testTarget())) throw new IllegalStateException("testTarget mismatch for " + coordinates(module));
			if (!"maven".equals(evidence.buildTool())) throw new IllegalStateException("buildTool must be maven for " + coordinates(module));
			for (TestIdentity identity : union(evidence.executed(), evidence.nonExecuted())) {
				requireAssigned(identity, assigned);
				if (!evidenceOwners.add(identity)) throw new IllegalStateException("Duplicate execution evidence identity: " + identity);
			}
			executed.addAll(evidence.executed()); nonExecuted.addAll(evidence.nonExecuted());
		}
		if (!discoveredAssigned.equals(assigned)) {
			Set<TestIdentity> missing = new TreeSet<>(assigned); missing.removeAll(discoveredAssigned);
			throw new IllegalStateException("Shard assignments are absent from the effective reactor: " + missing);
		}
		Set<TestIdentity> fragmentOwners = new TreeSet<>(mapped.keySet()); fragmentOwners.addAll(unmapped.keySet());
		if (!fragmentOwners.equals(assigned)) throw new IllegalStateException("Module fragments do not own exactly the shard assignment");
		if (!evidenceOwners.equals(assigned)) throw new IllegalStateException("Module evidence does not own exactly the shard assignment");
		CoverageFragment result = new CoverageFragment(CoverageMapContract.SCHEMA_VERSION,
				new CoverageMapRevision(revision), new ShardId(shardId), mapped,
				new ArrayList<>(unmapped.values()), new ArrayList<>(scopes.values()), true);
		var validation = CoverageMapValidator.validate(result);
		if (!validation.isValid()) throw new IllegalStateException("Invalid aggregated coverage fragment: " + validation.errors());
		return new Aggregate(result, encodeEvidence(executed, nonExecuted));
	}

	private Set<TestIdentity> discoverAssigned(MavenProject module, Set<TestIdentity> assigned) throws Exception {
		File root = new File(module.getBuild().getTestOutputDirectory());
		if (!root.isDirectory()) return Set.of();
		var inventory = new JUnitHeadTestInventoryGenerator().generate(
				module.getTestClasspathElements().stream().map(File::new).map(File::toPath).toList(), List.of(root.toPath()));
		Set<TestIdentity> result = new TreeSet<>(inventory.runnableTests()); result.retainAll(assigned); return result;
	}

	private Evidence readEvidence(Path file) throws IOException {
		JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		if (root.get("version").getAsInt() != 1) throw new IllegalStateException("Unsupported execution evidence version");
		Set<TestIdentity> executed = identities(root, "EXECUTED"), nonExecuted = identities(root, "NON_EXECUTED");
		Set<TestIdentity> overlap = new HashSet<>(executed); overlap.retainAll(nonExecuted);
		if (!overlap.isEmpty()) throw new IllegalStateException("EXECUTED/NON_EXECUTED overlap: " + overlap);
		return new Evidence(root.get("revision").getAsString(), root.get("shardId").getAsString(),
				root.get("testTarget").getAsString(), root.get("buildTool").getAsString(), executed, nonExecuted);
	}

	private byte[] encodeEvidence(Set<TestIdentity> executed, Set<TestIdentity> nonExecuted) {
		JsonObject root = new JsonObject(); root.addProperty("version", 1); root.addProperty("revision", revision);
		root.addProperty("shardId", shardId); root.addProperty("testTarget", testTarget); root.addProperty("buildTool", "maven");
		JsonArray x = new JsonArray(); executed.forEach(id -> x.add(id.toString())); root.add("EXECUTED", x);
		JsonArray n = new JsonArray(); nonExecuted.forEach(id -> n.add(id.toString())); root.add("NON_EXECUTED", n);
		return (new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private void publishPair(byte[] fragment, byte[] evidence) throws Exception {
		Path finalFragment = fragmentOutput.toPath().toAbsolutePath(), finalEvidence = evidenceOutput.toPath().toAbsolutePath();
		Path fragmentParent = finalFragment.getParent(), evidenceParent = finalEvidence.getParent();
		if (fragmentParent != null) Files.createDirectories(fragmentParent);
		if (evidenceParent != null) Files.createDirectories(evidenceParent);
		Path fragmentTemp = finalFragment.resolveSibling("." + finalFragment.getFileName() + ".tmp");
		Path evidenceTemp = finalEvidence.resolveSibling("." + finalEvidence.getFileName() + ".tmp");
		try {
			Files.deleteIfExists(fragmentTemp); Files.deleteIfExists(evidenceTemp);
			Files.write(fragmentTemp, fragment); Files.write(evidenceTemp, evidence);
			move(fragmentTemp, finalFragment); move(evidenceTemp, finalEvidence);
		} catch (IOException failure) {
			Files.deleteIfExists(fragmentTemp); Files.deleteIfExists(evidenceTemp); invalidateFinals(); throw failure;
		}
	}

	private static void move(Path source, Path target) throws IOException {
		try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
		catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
	}

	private void validateParameters() throws MojoExecutionException {
		if (revision == null || revision.isBlank()) throw new MojoExecutionException("smartTestPicker.revision must not be blank");
		if (shardId == null || shardId.isBlank()) throw new MojoExecutionException("smartTestPicker.shardId must not be blank");
		if (testTarget == null || testTarget.isBlank()) testTarget = "test";
		if (fragmentOutput == null || evidenceOutput == null) throw new MojoExecutionException("Final fragment and evidence outputs are required");
		if (fragmentOutput.toPath().toAbsolutePath().equals(evidenceOutput.toPath().toAbsolutePath())) throw new MojoExecutionException("Final outputs must be distinct");
		if (shardAssignments == null || !shardAssignments.isFile()) throw new MojoExecutionException("STP_MAPPING_TESTS_FILE is required and must exist");
	}

	private void binding(String actualRevision, String actualShard, String owner) {
		if (!revision.equals(actualRevision)) throw new IllegalStateException("Revision mismatch for " + owner);
		if (!shardId.equals(actualShard)) throw new IllegalStateException("Shard mismatch for " + owner);
	}
	private void invalidateFinals() throws MojoExecutionException {
		try { if (fragmentOutput != null) Files.deleteIfExists(fragmentOutput.toPath()); if (evidenceOutput != null) Files.deleteIfExists(evidenceOutput.toPath()); }
		catch (IOException failure) { throw new MojoExecutionException("Failed to invalidate final shard outputs", failure); }
	}
	private static Set<TestIdentity> readAssignments(Path file) throws IOException {
		Set<TestIdentity> result = new TreeSet<>();
		for (String line : Files.readAllLines(file)) if (!line.isBlank() && !result.add(TestIdentity.parse(line.trim())))
			throw new IllegalStateException("Duplicate shard assignment: " + line.trim());
		return result;
	}
	private static Set<TestIdentity> identities(JsonObject root, String name) {
		Set<TestIdentity> result = new TreeSet<>();
		for (JsonElement value : root.getAsJsonArray(name)) { TestIdentity id = TestIdentity.parse(value.getAsString()); if (!result.add(id)) throw new IllegalStateException("Duplicate evidence identity: " + id); }
		return result;
	}
	private static Set<TestIdentity> union(Set<TestIdentity> first, Set<TestIdentity> second) { Set<TestIdentity> result = new TreeSet<>(first); result.addAll(second); return result; }
	private static void requireAssigned(TestIdentity identity, Set<TestIdentity> assigned) { if (!assigned.contains(identity)) throw new IllegalStateException("Unexpected test identity outside shard assignment: " + identity); }
	private static void duplicate(TestIdentity identity) { throw new IllegalStateException("Duplicate module fragment identity: " + identity); }
	private static String coordinates(MavenProject project) { return project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion(); }
	private record Evidence(String revision, String shardId, String testTarget, String buildTool, Set<TestIdentity> executed, Set<TestIdentity> nonExecuted) { }
	private record Aggregate(CoverageFragment fragment, byte[] evidence) { }
}
