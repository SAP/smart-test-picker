// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GradleV3FreshnessFunctionalTest {
	@TempDir Path temporary;
	private static final String INVENTORY = "generateGradleExecutableHeadTestInventory";
	private static final String MAPPING = "generateSmartTestMapping";

	@Test void unchangedMappingAlwaysExecutesAndProducesIdenticalAggregate() throws Exception {
		Path root = fixture("unchanged", "ASM", "", false);
		fresh(run(root, false, null, INVENTORY, MAPPING));
		byte[] first = Files.readAllBytes(aggregate(root));
		fresh(run(root, false, null, INVENTORY, MAPPING));
		assertArrayEquals(first, Files.readAllBytes(aggregate(root)));
	}

	@Test void assignmentChangeAtSameRevisionRemovesOldIdentities() throws Exception {
		for (String collector : List.of("ASM", "JACOCO")) {
			Path root = fixture("assignment-" + collector, collector, "", false);
			fresh(run(root, false, null, INVENTORY, MAPPING));
			assignment(root, List.of("second"));
			Path staleReport = root.resolve("build/stp/jacoco/_test/local/reports/session_setup_removed.status");
			if (collector.equals("JACOCO")) Files.writeString(staleReport, "EMPTY");
			fresh(run(root, false, null, INVENTORY, MAPPING));
			assertFalse(Files.exists(staleReport));
			assertEquals(Set.of("second"), fragment(root).tests().keySet().stream()
					.map(test -> test.test().methodName()).collect(Collectors.toSet()));
		}
	}

	@Test void productionChangeAtSameRevisionReplacesOldEdges() throws Exception {
		Path root = fixture("changed-code", "ASM", "", false);
		fresh(run(root, false, null, INVENTORY, MAPPING));
		assertTrue(classes(root).contains("example.OldValue"));
		write(root, "src/main/java/example/Service.java", "package example; public class Service { public static int value() { return NewValue.value(); } }");
		fresh(run(root, false, null, INVENTORY, MAPPING));
		assertTrue(classes(root).contains("example.NewValue"));
		assertFalse(classes(root).contains("example.OldValue"));
	}

	@Test void buildCacheCannotRestoreMappingForEitherCollector() throws Exception {
		for (String collector : List.of("ASM", "JACOCO")) {
			Path root = fixture("cache-" + collector, collector, "", false);
			fresh(run(root, false, null, INVENTORY, MAPPING, "--build-cache"));
			Path copy = temporary.resolve("copy-" + collector);
			try (var files = Files.walk(root)) {
				for (Path source : files.filter(Files::isRegularFile).toList()) {
					Path relative = root.relativize(source);
					if (Set.of("build", ".gradle").contains(relative.getName(0).toString())) continue;
					Path destination = copy.resolve(relative);
					Files.createDirectories(destination.getParent());
					Files.copy(source, destination);
				}
			}
			assertFalse(Files.exists(copy.resolve("build")));
			fresh(run(copy, false, null, INVENTORY, MAPPING, "--build-cache"));
			assertEquals(fragment(root).tests(), fragment(copy).tests());
		}
	}

	@Test void ordinaryTestRemainsUpToDate() throws Exception {
		Path root = fixture("ordinary", "ASM", "", false);
		assertEquals(TaskOutcome.SUCCESS, run(root, false, null, "test", "-Dfixture.schema=2").task(":test").getOutcome());
		assertEquals(TaskOutcome.UP_TO_DATE, run(root, false, null, "test", "-Dfixture.schema=2").task(":test").getOutcome());
	}

	@Test void movedHeadIsCheckedEvenWithExistingInventory() throws Exception {
		Path root = fixture("head", "ASM", "", false);
		run(root, false, null, INVENTORY, "--configuration-cache");
		git(root, "commit", "--allow-empty", "-qm", "new revision");
		var failed = run(root, true, null, INVENTORY, "--configuration-cache");
		assertTrue(failed.getOutput().contains("Workspace HEAD does not equal prHeadRevision"));
		assertEquals(TaskOutcome.FAILED, failed.task(":generateGradleExecutableProjectInventory").getOutcome());
	}

	@Test void configurationCacheReusesInventoryMappingAndAggregation() throws Exception {
		for (String collector : List.of("ASM", "JACOCO")) {
			Path root = fixture("configuration-cache-" + collector, collector, "", false);
			fresh(run(root, false, null, INVENTORY, MAPPING, "--configuration-cache"));
			var second = run(root, false, null, INVENTORY, MAPPING, "--configuration-cache");
			fresh(second);
			assertTrue(second.getOutput().contains("Reusing configuration cache."));
		}
	}

	@Test void rotatingForksFailBeforeTestBody() throws Exception {
		Path root = fixture("fork-every", "ASM", "test { doFirst { forkEvery = 1 } }", false);
		var failed = run(root, true, null, MAPPING);
		assertTrue(failed.getOutput().contains("ASM mapping task :test requires forkEvery=0; forkEvery=1"));
		assertFalse(Files.exists(root.resolve("test-body-ran")));
	}

	@Test void parallelForkOverrideIsVisibleAndEffective() throws Exception {
		Path root = fixture("parallel-forks", "ASM", "test { maxParallelForks = 4; doLast { assert maxParallelForks == 1 } }", false);
		var result = run(root, false, null, MAPPING);
		assertTrue(result.getOutput().contains("ASM mapping task :test overrides maxParallelForks=4 with 1"));
		assertEquals(2, fragment(root).tests().size());
	}

	@Test void manifestMismatchFailsAndMatchingBlankUnsetPass() throws Exception {
		Path root = fixture("manifest", "ASM", "", false);
		var failed = run(root, true, "JACOCO", MAPPING, "--configuration-cache");
		assertTrue(failed.getOutput().contains("STP_MAPPING_COLLECTOR=JACOCO conflicts with effective collector=ASM"));
		assertFalse(Files.exists(root.resolve("test-body-ran")));
		assertTrue(run(root, true, "UNKNOWN", MAPPING).getOutput()
				.contains("STP_MAPPING_COLLECTOR=UNKNOWN conflicts with effective collector=ASM"));
		for (String frozen : new String[] {"ASM", " ", null}) {
			assertEquals(TaskOutcome.SUCCESS, run(root, false, frozen, MAPPING, "--configuration-cache").task(":test").getOutcome());
		}
	}

	@Test void earlyJvmArgumentsInspectionDoesNotDeriveScope() throws Exception {
		Path root = fixture("early-args", "ASM", """
			gradle.projectsEvaluated {
			 assert !file('build/classes/java/main').exists()
			 assert test.allJvmArgs.any { it.startsWith('-javaagent:') }
			 assert !file('build/stp/coverage/_test/local/asm-production-sources.txt').exists()
			}
			""", false);
		fresh(run(root, false, null, INVENTORY, MAPPING));
	}

	@Test void kotlinJvmMainIsDerivedButKotlinTestsAreExcluded() throws Exception {
		Path root = fixture("kotlin", "ASM", "", true);
		fresh(run(root, false, null, INVENTORY, MAPPING));
		assertTrue(classes(root).contains("example.Service"));
		assertFalse(classes(root).contains("example.Helper"));
		assertFalse(classes(root).contains("example.FreshTest"));
		String sources = Files.readString(root.resolve("build/stp/coverage/_test/local/asm-production-sources.txt"));
		assertTrue(sources.contains("classes/kotlin/main"));
		assertFalse(sources.contains("classes/kotlin/test"));
	}

	private Path fixture(String name, String collector, String extra, boolean kotlin) throws Exception {
		Path root = temporary.resolve(name);
		write(root, "settings.gradle", "rootProject.name='freshness'\nbuildCache { local { directory = '"
				+ temporary.resolve("cache").toString().replace('\\', '/') + "' } }\n");
		write(root, ".gitignore", "build/\n.gradle/\nassignment.json\nrevision.txt\ntest-body-ran\n");
		write(root, "build.gradle", """
			plugins { id 'java'; id 'jacoco'; %s id 'com.sap.oss.smart-test-picker' }
			repositories { mavenCentral() }
			dependencies {
			 testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
			 testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
			 stpAgent files('%s')
			 stpJacocoCollector files('%s')
			}
			test { useJUnitPlatform() }
			smartTestPicker {
			 runtimeSchemaVersion = providers.systemProperty('fixture.schema').orElse('3').map { Integer.parseInt(it) }
			 coverageCollector = '%s'
			 revision = providers.fileContents(layout.projectDirectory.file('revision.txt')).asText.map { it.trim() }
			 shardId = 'local'; executableAssignmentFile = 'assignment.json'; mappingTestTasks = [':test']
			}
			%s
			""".formatted(kotlin ? "id 'org.jetbrains.kotlin.jvm' version '2.4.10';" : "",
				System.getProperty("stp.test.agent.jar").replace('\\', '/'),
				System.getProperty("stp.test.core.jar").replace('\\', '/'), collector, extra));
		if (kotlin) {
			write(root, "src/main/kotlin/example/Service.kt", "package example\nobject Service { @JvmStatic fun value(): Int = 1 }\n");
			write(root, "src/test/kotlin/example/Helper.kt", "package example\nobject Helper { @JvmStatic fun value(): Int = 2 }\n");
		} else {
			write(root, "src/main/java/example/Service.java", "package example; public class Service { public static int value() { return OldValue.value(); } }");
			for (String type : List.of("OldValue", "NewValue")) write(root, "src/main/java/example/" + type + ".java",
					"package example; public class " + type + " { public static int value() { return 1; } }");
			write(root, "src/test/java/example/Helper.java", "package example; public class Helper { public static int value() { return 2; } }");
		}
		write(root, "src/test/java/example/FreshTest.java", """
			package example;
			import org.junit.jupiter.api.Test;
			import static org.junit.jupiter.api.Assertions.*;
			class FreshTest {
			 @Test void first() throws Exception {
			  java.nio.file.Files.writeString(java.nio.file.Path.of("test-body-ran"), "yes");
			  assertEquals(1, Service.value()); assertEquals(2, Helper.value());
			 }
			 @Test void second() { assertEquals(1, Service.value()); }
			}
			""");
		git(root, "init", "-q"); git(root, "config", "user.name", "Fixture"); git(root, "config", "user.email", "fixture@example.invalid");
		git(root, "add", "."); git(root, "commit", "-qm", "fixture");
		write(root, "revision.txt", git(root, "rev-parse", "HEAD").trim());
		assignment(root, List.of("first", "second"));
		return root;
	}

	private void assignment(Path root, List<String> methods) throws Exception {
		write(root, "assignment.json", "{\"version\":1,\"revision\":\"" + Files.readString(root.resolve("revision.txt"))
				+ "\",\"shardId\":\"local\",\"tests\":[" + methods.stream().map(m -> "\"gradle::test::example.FreshTest#" + m + "\"")
				.collect(Collectors.joining(",")) + "]}");
	}
	private BuildResult run(Path root, boolean failure, String frozen, String... arguments) {
		var args = new ArrayList<>(List.of(arguments)); args.addAll(List.of("--stacktrace", "--max-workers=2"));
		var environment = new HashMap<>(System.getenv()); environment.remove("STP_MAPPING_COLLECTOR");
		if (frozen != null) environment.put("STP_MAPPING_COLLECTOR", frozen);
		var runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(args).withEnvironment(environment);
		BuildResult result = failure ? runner.buildAndFail() : runner.build();
		System.out.println("FRESHNESS " + root.getFileName() + " " + String.join(" ", arguments));
		System.out.println("CACHE_REUSED " + result.getOutput().contains("Reusing configuration cache."));
		result.getTasks().stream().filter(t -> t.getPath().equals(":test") || t.getPath().contains("Inventory")
				|| t.getPath().contains("aggregate") || t.getPath().contains("JacocoExecutableFragment"))
				.forEach(t -> System.out.println("OUTCOME " + t.getPath() + " " + t.getOutcome()));
		return result;
	}
	private void fresh(BuildResult result) {
		for (String task : List.of(":generateGradleExecutableProjectInventory", ":" + INVENTORY, ":test", ":aggregateGradleExecutableCoverage")) {
			assertNotNull(result.task(task), task);
			assertEquals(TaskOutcome.SUCCESS, result.task(task).getOutcome(), task);
		}
	}
	private Path aggregate(Path root) { return root.resolve("build/stp/executable-fragment.json"); }
	private ExecutableCoverageFragment fragment(Path root) throws Exception {
		return new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(aggregate(root)));
	}
	private Set<String> classes(Path root) throws Exception {
		return fragment(root).tests().values().stream().flatMap(c -> c.coveredClasses().stream()).collect(Collectors.toSet());
	}
	private void write(Path root, String name, String text) throws Exception {
		Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, text);
	}
	private String git(Path root, String... args) throws Exception {
		var command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false")); command.addAll(List.of(args));
		var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), output); return output;
	}
}
