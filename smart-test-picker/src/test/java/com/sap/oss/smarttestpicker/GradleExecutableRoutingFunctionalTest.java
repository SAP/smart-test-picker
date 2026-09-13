// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;

import static org.junit.jupiter.api.Assertions.*;

class GradleExecutableRoutingFunctionalTest {
	@TempDir Path temporary;

	@Test void routesSameLogicalTestSeparatelyAcrossRootTestTasks() throws Exception {
		Path project = temporary.resolve("multi-task");
		Files.createDirectories(project.resolve("src/main/java/example"));
		Files.createDirectories(project.resolve("src/test/java/example"));
		Files.writeString(project.resolve("settings.gradle"), "rootProject.name='multi-task'\n");
		Files.writeString(project.resolve("build.gradle"), """
			plugins { id 'java'; id 'com.sap.oss.smart-test-picker' }
			repositories { mavenCentral() }
			dependencies {
			  testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
			  testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
			  stpAgent files('%s')
			}
			test { useJUnitPlatform() }
			tasks.register('integrationTest', Test) {
			  useJUnitPlatform()
			  testClassesDirs = sourceSets.test.output.classesDirs
			  classpath = sourceSets.test.runtimeClasspath
			}
			tasks.register('componentTest', Test) {
			  useJUnitPlatform()
			  testClassesDirs = sourceSets.test.output.classesDirs
			  classpath = sourceSets.test.runtimeClasspath
			}
			smartTestPicker {
			  runtimeSchemaVersion = 3
			  revision = providers.systemProperty('fixture.revision')
			  shardId = 'fixture-shard'
			  executableAssignmentFile = 'assignment.json'
			  coverageIncludes = ['example.']
			}
			""".formatted(slash(new File(System.getProperty("stp.test.agent.jar")))));
		Files.writeString(project.resolve("src/main/java/example/Service.java"),
				"package example; public class Service { public int value() { return 1; } }\n");
		Files.writeString(project.resolve("src/test/java/example/SharedTest.java"), """
			package example;
			import org.junit.jupiter.api.*;
			import org.junit.jupiter.params.ParameterizedTest;
			import org.junit.jupiter.params.provider.ValueSource;
			import static org.junit.jupiter.api.Assertions.*;
			class SharedTest {
			 @Test void same() throws Exception {
			   assertEquals(1, new Service().value());
			   Files.writeString(Path.of("executed.txt"), System.getProperty("smartTestPicker.executionTarget") + "\\n",
			     java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			 }
			 @Test void outside() { fail("outside assignment executed"); }
			 @ParameterizedTest @ValueSource(strings={"a","b"}) void parameterized(String value) { assertFalse(value.isEmpty()); }
			 @Nested class NestedCase { @Test void inside() { assertEquals(1, new Service().value()); } }
			 @Disabled @Test void skipped() { fail("disabled test executed"); }
			}
			""".replace("import org.junit.jupiter.api.*;", "import org.junit.jupiter.api.*;\nimport java.nio.file.*;"));
		git(project, "init", "-q"); git(project, "config", "user.email", "fixture@example.invalid");
		git(project, "config", "user.name", "Fixture"); git(project, "add", "."); git(project, "commit", "-qm", "fixture");
		String revision = command(project, "git", "rev-parse", "HEAD").trim();
		Files.writeString(project.resolve("assignment.json"), """
			{"version":1,"revision":"%s","shardId":"fixture-shard","tests":[
			 "gradle::integrationTest::example.SharedTest#same",
			 "gradle::test::example.SharedTest#same",
			 "gradle::test::example.SharedTest#parameterized(java.lang.String)",
			 "gradle::test::example.SharedTest$NestedCase#inside",
			 "gradle::test::example.SharedTest#skipped"
			]}
			""".formatted(revision));

		var result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
				.withArguments("generateSmartTestMapping", "-Dfixture.revision=" + revision, "--stacktrace")
				.forwardOutput().build();
		assertNotNull(result.task(":test")); assertNotNull(result.task(":integrationTest"));
		assertNotNull(result.task(":componentTest"));
		assertEquals(Set.of("gradle::test", "gradle::integrationTest"),
				Set.copyOf(Files.readAllLines(project.resolve("executed.txt"))));
		var inventory = new ExecutableHeadTestInventoryCodec().read(
				project.resolve("build/executable-head-test-inventory.json").toFile());
		assertTrue(inventory.runnableTests().stream().anyMatch(id -> id.toString().equals(
				"gradle::test::example.SharedTest#same")));
		assertTrue(inventory.runnableTests().stream().anyMatch(id -> id.toString().equals(
				"gradle::integrationTest::example.SharedTest#same")));
		assertTrue(inventory.runnableTests().stream().anyMatch(id -> id.toString().equals(
				"gradle::test::example.SharedTest#parameterized(java.lang.String)")));
		assertTrue(inventory.runnableTests().stream().anyMatch(id -> id.toString().equals(
				"gradle::test::example.SharedTest$NestedCase#inside")));
		for (String task : List.of("_test", "_integrationTest")) {
			Path fragment = project.resolve("build/stp/coverage/" + task + "/fixture-shard/fragment.json");
			var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
			assertEquals(task.equals("_test") ? 3 : 1, decoded.tests().size());
			assertTrue(decoded.tests().keySet().iterator().next().target().toString().equals(
					task.equals("_test") ? "gradle::test" : "gradle::integrationTest"));
		}
		assertFalse(Files.exists(project.resolve("build/test-results/componentTest/TEST-example.SharedTest.xml")));
		var aggregate = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(
				project.resolve("build/stp/executable-fragment.json")));
		assertEquals(4, aggregate.tests().size());
		assertEquals(Set.of("gradle::test::example.SharedTest#skipped"), aggregate.unmapped().stream()
				.map(value -> value.test().toString()).collect(java.util.stream.Collectors.toSet()));
	}

	@Test void routesSameLogicalTestSeparatelyAcrossSubprojects() throws Exception {
		Path project = temporary.resolve("multi-project");
		Files.createDirectories(project);
		Files.writeString(project.resolve("settings.gradle"), "rootProject.name='multi-project'\ninclude 'module-a','module-b'\n");
		Files.writeString(project.resolve("build.gradle"), """
			plugins { id 'com.sap.oss.smart-test-picker' }
			repositories { mavenCentral() }
			subprojects {
			 apply plugin: 'java'
			 repositories { mavenCentral() }
			 dependencies { testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'; testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3' }
			 test { useJUnitPlatform() }
			}
			dependencies { stpAgent files('%s') }
			smartTestPicker {
			 runtimeSchemaVersion = 3; revision = providers.systemProperty('fixture.revision'); shardId = 'fixture-shard'
			 executableAssignmentFile = 'assignment.json'; coverageIncludes = ['example.']
			}
			""".formatted(slash(new File(System.getProperty("stp.test.agent.jar")))));
		for (String module : List.of("module-a", "module-b")) {
			Files.createDirectories(project.resolve(module + "/src/main/java/example"));
			Files.createDirectories(project.resolve(module + "/src/test/java/example"));
			Files.writeString(project.resolve(module + "/src/main/java/example/Service.java"),
					"package example; public class Service { public int value() { return 1; } }\n");
			Files.writeString(project.resolve(module + "/src/test/java/example/SharedTest.java"), """
				package example; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
				class SharedTest { @Test void same() { assertEquals(1, new Service().value()); }
				@Test void outside() { fail("outside assignment executed"); } }
				""");
		}
		git(project, "init", "-q"); git(project, "config", "user.email", "fixture@example.invalid");
		git(project, "config", "user.name", "Fixture"); git(project, "add", "."); git(project, "commit", "-qm", "fixture");
		String revision = command(project, "git", "rev-parse", "HEAD").trim();
		Files.writeString(project.resolve("assignment.json"), """
			{"version":1,"revision":"%s","shardId":"fixture-shard","tests":[
			"gradle::module-a:test::example.SharedTest#same","gradle::module-b:test::example.SharedTest#same"]}
			""".formatted(revision));
		var result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
				.withArguments("generateSmartTestMapping", "-Dfixture.revision=" + revision, "--stacktrace")
				.forwardOutput().build();
		assertNotNull(result.task(":module-a:test")); assertNotNull(result.task(":module-b:test"));
		var inventory = new ExecutableHeadTestInventoryCodec().read(
				project.resolve("build/executable-head-test-inventory.json").toFile());
		assertTrue(inventory.runnableTests().stream().anyMatch(id -> id.toString().equals(
				"gradle::module-a:test::example.SharedTest#same")));
		assertTrue(inventory.runnableTests().stream().anyMatch(id -> id.toString().equals(
				"gradle::module-b:test::example.SharedTest#same")));
		var aggregate = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(
				project.resolve("build/stp/executable-fragment.json")));
		assertEquals(2, aggregate.tests().size());
	}

	@Test void jacocoFallbackProducesTaskQualifiedSchemaV3Fragment() throws Exception {
		Path project = temporary.resolve("jacoco-v3");
		Files.createDirectories(project.resolve("src/main/java/example"));
		Files.createDirectories(project.resolve("src/test/java/example"));
		Files.writeString(project.resolve("settings.gradle"), "rootProject.name='jacoco-v3'\n");
		Files.writeString(project.resolve("build.gradle"), """
			plugins { id 'java'; id 'jacoco'; id 'com.sap.oss.smart-test-picker' }
			repositories { mavenCentral() }
			dependencies {
			 testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
			 testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
			 stpJacocoCollector files('%s')
			}
			test { useJUnitPlatform() }
			smartTestPicker {
			 coverageCollector='JACOCO'; runtimeSchemaVersion=3; revision=providers.systemProperty('fixture.revision')
			 shardId='fixture-shard'; executableAssignmentFile='assignment.json'
			}
			""".formatted(slash(new File(System.getProperty("stp.test.core.jar")))));
		Files.writeString(project.resolve("src/main/java/example/Service.java"),
				"package example; public class Service { public int value() { return 1; } }\n");
		Files.writeString(project.resolve("src/test/java/example/SharedTest.java"),
				"package example; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; class SharedTest { @Test void same(){ assertEquals(1,new Service().value()); } @Test void outside(){ fail(); } }\n");
		git(project, "init", "-q"); git(project, "config", "user.email", "fixture@example.invalid");
		git(project, "config", "user.name", "Fixture"); git(project, "add", "."); git(project, "commit", "-qm", "fixture");
		String revision = command(project, "git", "rev-parse", "HEAD").trim();
		Files.writeString(project.resolve("assignment.json"), "{\"version\":1,\"revision\":\"" + revision
				+ "\",\"shardId\":\"fixture-shard\",\"tests\":[\"gradle::test::example.SharedTest#same\"]}\n");
		GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
				.withArguments("generateSmartTestMapping", "-Dfixture.revision=" + revision, "--stacktrace")
				.forwardOutput().build();
		var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(
				project.resolve("build/stp/executable-fragment.json")));
		assertEquals(Set.of("gradle::test::example.SharedTest#same"), fragment.tests().keySet().stream()
				.map(Object::toString).collect(java.util.stream.Collectors.toSet()));
	}

	private static void git(Path directory, String... command) throws Exception { command(directory, "git", command); }
	private static String command(Path directory, String executable, String... args) throws Exception {
		String[] command = new String[args.length + 1]; command[0] = executable;
		System.arraycopy(args, 0, command, 1, args.length);
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		if (process.waitFor() != 0) throw new IllegalStateException(output); return output;
	}
	private static String slash(File file) { return file.getAbsolutePath().replace("\\", "/").replace("'", "\\'"); }
}
