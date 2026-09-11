// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.google.gson.JsonParser;

class ReactorHeadTestInventoryMojoIntegrationTest {
	private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0:";
	private static final Path LOCAL_REPOSITORY = Path.of("build/functional-test-maven-local-"
			+ ProcessHandle.current().pid()).toAbsolutePath();

	@Test
	void realMavenReactorWritesOneRevisionBoundInventoryWithoutRunningTests(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v2-reactor", temp.resolve("reactor"));
		String revision = initializeGit(fixture);

		Result result = maven(fixture, "process-test-classes", PLUGIN + "generate-reactor-head-test-inventory",
				"-DsmartTestPicker.prHeadRevision=" + revision);
		assertEquals(0, result.exitCode(), result.output());
		Path output = fixture.resolve("target/head-test-inventory.json");
		var inventory = new HeadTestInventoryCodec().read(output.toFile());
		assertEquals(revision, inventory.revision());
		assertEquals(List.of(new TestIdentity("a.SameNameTests", "alpha"),
				new TestIdentity("b.SameNameTests", "beta")), inventory.runnableTests().stream().sorted().toList());
		assertEquals(1, Files.walk(fixture).filter(path -> path.getFileName().toString()
				.equals("head-test-inventory.json")).count());
		assertFalse(Files.exists(fixture.resolve("module-a/target/surefire-reports")));
		assertFalse(Files.exists(fixture.resolve("module-b/target/surefire-reports")));
	}

	@Test
	void duplicateIdentityAndWrongRevisionBothDeleteStaleOutput(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v2-reactor", temp.resolve("reactor"));
		String revision = initializeGit(fixture);
		Path output = Files.createDirectories(fixture.resolve("target")).resolve("head-test-inventory.json");
		Files.writeString(output, "stale");

		Result collision = maven(fixture, "process-test-classes", "-Pcollision",
				PLUGIN + "generate-reactor-head-test-inventory",
				"-DsmartTestPicker.prHeadRevision=" + revision);
		assertTrue(collision.exitCode() != 0, collision.output());
		assertTrue(collision.output().contains("Duplicate head test identity"), collision.output());
		assertFalse(Files.exists(output));

		Files.writeString(output, "stale");
		Result mismatch = maven(fixture, PLUGIN + "generate-reactor-head-test-inventory",
				"-DsmartTestPicker.prHeadRevision=0000000000000000000000000000000000000000");
		assertTrue(mismatch.exitCode() != 0, mismatch.output());
		assertFalse(Files.exists(output));
	}

	@Test
	void existingGoalStillGeneratesSingleModuleInventory(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v2", temp.resolve("single"));
		Result result = maven(fixture, "process-test-classes", PLUGIN + "generate-head-test-inventory");
		assertEquals(0, result.exitCode(), result.output());
		var inventory = new HeadTestInventoryCodec().read(
				fixture.resolve("target/head-test-inventory.json").toFile());
		assertTrue(inventory.runnableTests().contains(new TestIdentity("example.FixtureTests", "ordinary")));
		assertFalse(Files.exists(fixture.resolve("target/surefire-reports")));
	}

	@Test
	void reactorWithNoCompiledTestOutputFailsWithoutPublishing(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v2-reactor", temp.resolve("reactor"));
		Path output = Files.createDirectories(fixture.resolve("target")).resolve("head-test-inventory.json");
		Files.writeString(output, "stale");
		Result result = maven(fixture, PLUGIN + "generate-reactor-head-test-inventory");
		assertTrue(result.exitCode() != 0, result.output());
		assertTrue(result.output().contains("No compiled Maven test output is available"), result.output());
		assertFalse(Files.exists(output));
	}

	@Test
	void realReactorAggregatesModuleFragmentsAndEvidenceDeterministically(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v2-reactor", temp.resolve("reactor"));
		Path assignments = fixture.resolve("assignments.txt");
		Files.writeString(assignments, "a.SameNameTests#alpha\nb.SameNameTests#beta\n");
		Path fragment = fixture.resolve("target/final-fragment.json");
		Path evidence = fixture.resolve("target/final-evidence.json");
		Result result = maven(fixture, "verify", PLUGIN + "aggregate-reactor-coverage-fragment",
				"-DsmartTestPicker.testsFile=" + assignments, "-DsmartTestPicker.fragmentOutput=" + fragment,
				"-DsmartTestPicker.evidenceOutput=" + evidence);
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(Files.isRegularFile(fixture.resolve("module-a/target/stp/coverage-fragment-v2.json")));
		assertTrue(Files.isRegularFile(fixture.resolve("module-a/target/stp/execution-evidence-v1.json")));
		assertTrue(Files.isRegularFile(fixture.resolve("module-b/target/stp/coverage-fragment-v2.json")));
		assertTrue(Files.isRegularFile(fixture.resolve("module-b/target/stp/execution-evidence-v1.json")));
		var decoded = new CoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		assertTrue(decoded.collectionCompleted());
		assertEquals("reactor-revision", decoded.revision().value());
		assertEquals("reactor-shard", decoded.shardId().value());
		assertEquals(Set.of(new TestIdentity("a.SameNameTests", "alpha"), new TestIdentity("b.SameNameTests", "beta")), decoded.tests().keySet());
		var json = JsonParser.parseString(Files.readString(evidence)).getAsJsonObject();
		assertEquals(2, json.getAsJsonArray("EXECUTED").size());
		assertEquals(0, json.getAsJsonArray("NON_EXECUTED").size());
		byte[] fragmentBytes = Files.readAllBytes(fragment), evidenceBytes = Files.readAllBytes(evidence);
		Result second = maven(fixture, PLUGIN + "aggregate-reactor-coverage-fragment",
				"-DsmartTestPicker.testsFile=" + assignments, "-DsmartTestPicker.fragmentOutput=" + fragment,
				"-DsmartTestPicker.evidenceOutput=" + evidence);
		assertEquals(0, second.exitCode(), second.output());
		assertTrue(java.util.Arrays.equals(fragmentBytes, Files.readAllBytes(fragment)));
		assertTrue(java.util.Arrays.equals(evidenceBytes, Files.readAllBytes(evidence)));
	}

	@Test
	void reactorAggregationFailsClosedForUnsafeModuleInputs(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v2-reactor", temp.resolve("reactor"));
		Path assignments = fixture.resolve("assignments.txt");
		Files.writeString(assignments, "a.SameNameTests#alpha\nb.SameNameTests#beta\n");
		Result prepare = maven(fixture, "verify"); assertEquals(0, prepare.exitCode(), prepare.output());
		Path bFragment = fixture.resolve("module-b/target/stp/coverage-fragment-v2.json");
		Path bEvidence = fixture.resolve("module-b/target/stp/execution-evidence-v1.json");
		byte[] originalFragment = Files.readAllBytes(bFragment), originalEvidence = Files.readAllBytes(bEvidence);
		assertAggregationFailure(fixture, assignments, () -> Files.delete(bFragment), "Missing module coverage fragment"); Files.write(bFragment, originalFragment);
		assertAggregationFailure(fixture, assignments, () -> Files.delete(bEvidence), "Missing module execution evidence"); Files.write(bEvidence, originalEvidence);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bFragment, "{"), "coverage aggregation failed"); Files.write(bFragment, originalFragment);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bFragment, new String(originalFragment).replace("reactor-revision", "wrong-revision")), "Revision mismatch"); Files.write(bFragment, originalFragment);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bFragment, new String(originalFragment).replace("reactor-shard", "wrong-shard")), "Shard mismatch"); Files.write(bFragment, originalFragment);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bFragment, new String(originalFragment).replace("b.SameNameTests#beta", "a.SameNameTests#alpha")), "Duplicate module fragment identity"); Files.write(bFragment, originalFragment);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bFragment, new String(originalFragment).replace("b.SameNameTests#beta", "b.SameNameTests#outside")), "Unexpected test identity"); Files.write(bFragment, originalFragment);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bEvidence, new String(originalEvidence).replace("reactor-revision", "wrong-revision")), "Revision mismatch"); Files.write(bEvidence, originalEvidence);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bEvidence, new String(originalEvidence).replace("reactor-shard", "wrong-shard")), "Shard mismatch"); Files.write(bEvidence, originalEvidence);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bEvidence, new String(originalEvidence).replace("\"test\"", "\"wrong-target\"")), "testTarget mismatch"); Files.write(bEvidence, originalEvidence);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bEvidence, new String(originalEvidence).replace("\"maven\"", "\"gradle\"")), "buildTool must be maven"); Files.write(bEvidence, originalEvidence);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bEvidence, new String(originalEvidence).replace("\"NON_EXECUTED\": []", "\"NON_EXECUTED\": [\"b.SameNameTests#beta\"]")), "EXECUTED/NON_EXECUTED overlap"); Files.write(bEvidence, originalEvidence);
		assertAggregationFailure(fixture, assignments, () -> Files.writeString(bEvidence, new String(originalEvidence).replace("b.SameNameTests#beta", "a.SameNameTests#alpha")), "Duplicate execution evidence identity");
	}

	@Test
	void historicalSingleModuleInvocationPublishesCustomJenkinsPairWithoutAggregator(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v2", temp.resolve("single"));
		Path fragment = fixture.resolve("jenkins/custom-fragment.json");
		Path evidence = fixture.resolve("jenkins/custom-evidence.json");
		Result result = maven(fixture, "verify", "-DsmartTestPicker.revision=historical-revision",
				"-DsmartTestPicker.shardId=historical-shard", "-DsmartTestPicker.fragmentOutput=" + fragment,
				"-DsmartTestPicker.evidenceOutput=" + evidence);
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(Files.isRegularFile(fragment));
		assertTrue(Files.isRegularFile(evidence));
		var decoded = new CoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		assertEquals(2, decoded.schemaVersion());
		assertEquals("historical-revision", decoded.revision().value());
		assertEquals("historical-shard", decoded.shardId().value());
		var json = JsonParser.parseString(Files.readString(evidence)).getAsJsonObject();
		assertEquals("historical-revision", json.get("revision").getAsString());
		assertEquals("historical-shard", json.get("shardId").getAsString());
		assertEquals("test", json.get("testTarget").getAsString());
		assertEquals("maven", json.get("buildTool").getAsString());
		assertFalse(result.output().contains("aggregate-reactor-coverage-fragment"), result.output());
	}

	private static void assertAggregationFailure(Path fixture, Path assignments, ThrowingAction mutation, String message) throws Exception {
		Path fragment = Files.createDirectories(fixture.resolve("target")).resolve("final-fragment.json");
		Path evidence = fixture.resolve("target/final-evidence.json");
		Files.writeString(fragment, "stale"); Files.writeString(evidence, "stale"); mutation.run();
		Result result = maven(fixture, PLUGIN + "aggregate-reactor-coverage-fragment",
				"-DsmartTestPicker.testsFile=" + assignments, "-DsmartTestPicker.fragmentOutput=" + fragment,
				"-DsmartTestPicker.evidenceOutput=" + evidence);
		assertTrue(result.exitCode() != 0, result.output()); assertTrue(result.output().contains(message), result.output());
		assertFalse(Files.exists(fragment)); assertFalse(Files.exists(evidence));
	}

	@FunctionalInterface private interface ThrowingAction { void run() throws Exception; }

	private static Result maven(Path directory, String... arguments) throws Exception {
		Path repository = Path.of(System.getProperty("smartTestPicker.functionalTestRepository"));
		Path localRepository = LOCAL_REPOSITORY;
		Path settings = directory.resolve("functional-test-settings.xml");
		Files.writeString(settings, """
				<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
				  <localRepository>%s</localRepository>
				  <profiles><profile><id>functional-test</id>
				    <repositories><repository><id>functional-test</id><url>%s</url><releases><updatePolicy>always</updatePolicy></releases></repository></repositories>
				    <pluginRepositories><pluginRepository><id>functional-test</id><url>%s</url><releases><updatePolicy>always</updatePolicy></releases></pluginRepository></pluginRepositories>
				  </profile></profiles><activeProfiles><activeProfile>functional-test</activeProfile></activeProfiles>
				</settings>
				""".formatted(localRepository, repository.toUri(), repository.toUri()));
		var command = new java.util.ArrayList<>(List.of("mvn", "-U", "--batch-mode", "--no-transfer-progress",
				"--settings", settings.toString()));
		command.addAll(List.of(arguments));
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		assertTrue(process.waitFor(2, TimeUnit.MINUTES), "Maven fixture invocation timed out");
		return new Result(process.exitValue(), output);
	}

	private static Path copyFixture(String name, Path destination) throws IOException {
		Path source = Path.of("src/test/fixtures", name);
		try (var paths = Files.walk(source)) {
			for (Path path : paths.toList()) {
				Path target = destination.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) Files.createDirectories(target);
				else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return destination;
	}

	private static String initializeGit(Path directory) throws Exception {
		run(directory, "git", "init", "--quiet");
		run(directory, "git", "add", ".");
		run(directory, "git", "-c", "user.name=STP Test", "-c", "user.email=stp@example.invalid",
				"commit", "--quiet", "-m", "fixture");
		return run(directory, "git", "rev-parse", "HEAD").trim();
	}

	private static String run(Path directory, String... command) throws Exception {
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		assertEquals(0, process.waitFor(), output);
		return output;
	}

	private record Result(int exitCode, String output) { }
}
