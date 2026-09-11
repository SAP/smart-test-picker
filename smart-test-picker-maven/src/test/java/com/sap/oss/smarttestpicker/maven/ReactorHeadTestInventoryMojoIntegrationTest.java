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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;

class ReactorHeadTestInventoryMojoIntegrationTest {
	private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0:";

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

	private static Result maven(Path directory, String... arguments) throws Exception {
		Path repository = Path.of(System.getProperty("smartTestPicker.functionalTestRepository"));
		Path localRepository = repository.getParent().resolve("functional-test-maven-local");
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
