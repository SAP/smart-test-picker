// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GradleAsmScopeFunctionalTest {
	@TempDir Path temporary;

	@Test void derivesScopeWithNoIncludesAndKeepsEmptyTest() throws Exception {
		Path project = fixture("derived", "", false, false, false);
		run(project, false);
		var fragment = fragment(project, "", "_test");
		assertEquals(Set.of("example.Service"), covered(fragment));
		assertTrue(fragment.tests().values().stream().flatMap(c -> c.coveredMethods().stream())
				.anyMatch(method -> method.toString().equals("example.Service#value()I")));
		var empty = fragment.tests().entrySet().stream().filter(e -> e.getKey().test().methodName().equals("empty")).findFirst().orElseThrow();
		assertEquals(CollectionStatus.COLLECTED_EMPTY, empty.getValue().collectionStatus());
		assertTrue(fragment.collectionCompleted());
		assertTrue(fragment.unmapped().isEmpty());
		JsonObject configuration = diagnostic(project, "", "_test").getAsJsonObject("configuration");
		assertTrue(configuration.get("requireInstrumentation").getAsBoolean());
		assertFalse(configuration.getAsJsonArray("productionSources").isEmpty());
		assertEquals("example.", configuration.getAsJsonArray("includes").get(0).getAsString());
	}

	@Test void explicitEmptyIncludesAlsoDerives() throws Exception {
		Path project = fixture("empty-list", "coverageIncludes = []", false, false, false);
		run(project, false);
		assertEquals(Set.of("example.Service"), covered(fragment(project, "", "_test")));
	}

	@Test void wrongExplicitScopeReportsAgentDiagnosticAndPublishesNoFragment() throws Exception {
		Path project = fixture("wrong", "coverageIncludes = ['absent.']", false, false, false);
		var result = run(project, true);
		assertTrue(result.getOutput().contains("ASM scope matched zero instrumented classes: classesTransformed=0"));
		assertTrue(result.getOutput().contains("absent."));
		assertFalse(Files.exists(output(project, "", "_test").resolve("fragment.json")));
		assertFalse(Files.exists(project.resolve("build/stp/executable-fragment.json")));
	}

	@Test void samePackageTestHelperIsOutsideExplicitProductionBoundary() throws Exception {
		Path project = fixture("helper", "coverageIncludes = ['example.']", false, false, false);
		run(project, false);
		assertEquals(Set.of("example.Service"), covered(fragment(project, "", "_test")));
		assertFalse(diagnostic(project, "", "_test").toString().contains("example.TestHelper"));
	}

	@Test void includesProjectDependencyJarButNotUnrelatedProject() throws Exception {
		Path project = fixture("dependency", "", true, false, false);
		run(project, false);
		assertEquals(Set.of("example.Service", "example.Dependency"), covered(fragment(project, "b/", "_b_test")));
		String sources = diagnostic(project, "b/", "_b_test").getAsJsonObject("configuration").get("productionSources").toString();
		assertTrue(sources.contains("a.jar"), sources);
		assertFalse(sources.contains("unrelated"), sources);
	}

	@Test void projectTestFixturesAreNotProduction() throws Exception {
		Path project = fixture("test-fixtures", "", true, true, false);
		run(project, false);
		assertEquals(Set.of("example.Service", "example.Dependency"), covered(fragment(project, "b/", "_b_test")));
		assertFalse(diagnostic(project, "b/", "_b_test").toString().contains("example.FixtureHelper"));
	}

	@Test void integrationTestOutputIsNotProduction() throws Exception {
		Path project = fixture("integration", "", false, false, true);
		run(project, false);
		assertEquals(Set.of("example.Service"), covered(fragment(project, "", "_integrationTest")));
		assertFalse(diagnostic(project, "", "_integrationTest").toString().contains("example.TestHelper"));
	}

	@Test void rejectsDefaultPackageDuringDerivation() throws Exception {
		Path project = fixture("default-package", "", false, false, false);
		write(project, "src/main/java/DefaultProduction.java", "public class DefaultProduction {}\n");
		var result = run(project, true);
		assertTrue(result.getOutput().contains("default-package production class DefaultProduction.class"));
		assertFalse(Files.exists(project.resolve("test-body-ran")));
	}

	@Test void rejectsTaskWithoutProductionCodeSources() throws Exception {
		Path project = fixture("no-production", "", false, false, false);
		append(project.resolve("build.gradle"), "test { classpath = files(sourceSets.test.output, configurations.testRuntimeClasspath) }\n");
		var result = run(project, true);
		assertTrue(result.getOutput().contains("ASM mapping task :test has no production code sources"));
		assertFalse(Files.exists(project.resolve("test-body-ran")));
	}

	@Test void rejectsTestTaskUsingProductionOutputAsItsTestRoot() throws Exception {
		Path project = fixture("shared-output", "", false, false, false);
		append(project.resolve("build.gradle"), "test { testClassesDirs = sourceSets.main.output.classesDirs }\n");
		var result = run(project, true);
		assertTrue(result.getOutput().contains("identical production/test output directories"));
		assertFalse(Files.exists(project.resolve("test-body-ran")));
	}

	@Test void configurationCacheCanBeReusedWithFreshMapping() throws Exception {
		Path project = fixture("configuration-cache", "", false, false, false);
		run(project, false, "--configuration-cache", "--rerun-tasks");
		var before = fragment(project, "", "_test");
		var second = run(project, false, "--configuration-cache", "--rerun-tasks");
		assertTrue(second.getOutput().contains("Reusing configuration cache."));
		assertEquals(before.tests(), fragment(project, "", "_test").tests());
	}

	@Test void schemaTwoWrongScopeAlsoFailsClosed() throws Exception {
		Path project = fixture("schema-two", "coverageIncludes = ['absent.']", false, false, false);
		append(project.resolve("build.gradle"), "smartTestPicker { runtimeSchemaVersion = 2 }\n");
		var result = run(project, true);
		assertTrue(result.getOutput().contains("ASM scope matched zero instrumented classes: classesTransformed=0"));
		assertFalse(Files.exists(output(project, "", "_generateSmartTestCoverage").resolve("fragment.json")));
	}

	private Path fixture(String name, String includes, boolean multi, boolean fixtures, boolean integration) throws Exception {
		Path project = temporary.resolve(name);
		write(project, "settings.gradle", "rootProject.name='scope'\n" + (multi ? "include 'a', 'b', 'unrelated'\n" : ""));
		String task = multi ? ":b:test" : integration ? ":integrationTest" : ":test";
		write(project, "build.gradle", """
			plugins { id 'java'; id 'com.sap.oss.smart-test-picker' }
			allprojects {
			 apply plugin: 'java'
			 repositories { mavenCentral() }
			 dependencies {
			  testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
			  testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
			 }
			 tasks.withType(Test).configureEach { useJUnitPlatform() }
			}
			dependencies { stpAgent files('%s') }
			smartTestPicker {
			 runtimeSchemaVersion = 3; revision = 'scope-revision'; shardId = 'scope-shard'
			 executableAssignmentFile = 'assignment.json'; mappingTestTasks = ['%s']
			 %s
			}
			""".formatted(System.getProperty("stp.test.agent.jar").replace('\\', '/'), task, includes));
		String module = multi ? "b/" : "";
		if (multi) {
			write(project, "b/build.gradle", "dependencies { implementation project(':a') }\n");
			write(project, "a/src/main/java/example/Dependency.java", "package example; public class Dependency { public static int value() { return 2; } }\n");
			write(project, "unrelated/src/main/java/Unused.java", "public class Unused {}\n");
		}
		if (fixtures) {
			write(project, "a/build.gradle", "plugins { id 'java-test-fixtures' }\n");
			append(project.resolve("b/build.gradle"), "dependencies { testImplementation testFixtures(project(':a')) }\n");
			write(project, "a/src/testFixtures/java/example/FixtureHelper.java", "package example; public class FixtureHelper { public static int value() { return 3; } }\n");
		}
		if (integration) append(project.resolve("build.gradle"), """
			sourceSets { integrationTest { compileClasspath += sourceSets.main.output; runtimeClasspath += sourceSets.main.output } }
			configurations { integrationTestImplementation.extendsFrom testImplementation; integrationTestRuntimeOnly.extendsFrom testRuntimeOnly }
			tasks.register('integrationTest', Test) {
			 testClassesDirs = sourceSets.integrationTest.output.classesDirs
			 classpath = sourceSets.integrationTest.runtimeClasspath
			}
			""");
		write(project, module + "src/main/java/example/Service.java", "package example; public class Service { public static int value() { return 1; } }\n");
		String testRoot = module + "src/" + (integration ? "integrationTest" : "test") + "/java/example/";
		write(project, testRoot + "TestHelper.java", "package example; class TestHelper { static int value() { return 4; } }\n");
		write(project, testRoot + "ScopeTest.java", """
			package example;
			import org.junit.jupiter.api.Test;
			import static org.junit.jupiter.api.Assertions.*;
			class ScopeTest {
			 @Test void hit() throws Exception {
			  java.nio.file.Files.writeString(java.nio.file.Path.of("test-body-ran"), "yes");
			  assertEquals(1, Service.value()); assertEquals(4, TestHelper.value());
			  %s
			  %s
			 }
			 @Test void empty() { assertEquals(2, 1 + 1); }
			}
			""".formatted(multi ? "assertEquals(2, Dependency.value());" : "", fixtures ? "assertEquals(3, FixtureHelper.value());" : ""));
		write(project, "assignment.json", """
			{"version":1,"revision":"scope-revision","shardId":"scope-shard","tests":[
			"gradle:%s::example.ScopeTest#hit","gradle:%s::example.ScopeTest#empty"]}
			""".formatted(task, task));
		return project;
	}

	private BuildResult run(Path project, boolean fail, String... options) {
		var args = new ArrayList<>(List.of("generateSmartTestMapping", "--stacktrace", "--max-workers=2"));
		args.addAll(List.of(options));
		var runner = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(args);
		return fail ? runner.buildAndFail() : runner.build();
	}

	private Path output(Path project, String module, String task) {
		return project.resolve(module + "build/stp/coverage/" + task + "/scope-shard");
	}
	private ExecutableCoverageFragment fragment(Path project, String module, String task) throws Exception {
		return new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(output(project, module, task).resolve("fragment.json")));
	}
	private JsonObject diagnostic(Path project, String module, String task) throws Exception {
		return JsonParser.parseString(Files.readString(output(project, module, task).resolve("agent-diagnostic.json"))).getAsJsonObject();
	}
	private Set<String> covered(ExecutableCoverageFragment fragment) {
		return fragment.tests().values().stream().flatMap(c -> c.coveredClasses().stream()).collect(Collectors.toSet());
	}
	private void write(Path root, String name, String text) throws Exception {
		Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, text);
	}
	private void append(Path path, String text) throws Exception {
		Files.writeString(path, text, java.nio.file.StandardOpenOption.APPEND);
	}
}
