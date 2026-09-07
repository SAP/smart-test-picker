// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

class GradleCollectorBackendFunctionalTest {
	@TempDir Path temporary;

	@Test
	void defaultAndExplicitAsmProduceDescriptorAwareSchemaV2FragmentsAndRemainIsolated() throws Exception {
		Path defaultProject = fixture("default-asm", null, false);
		BuildResult defaultBuild = run(defaultProject, "generateSmartTestCoverage");
		assertEquals(SUCCESS, defaultBuild.task(":generateSmartTestCoverage").getOutcome());
		assertTrue(defaultBuild.getOutput().contains("Smart Test Picker coverage collector: ASM"));
		assertAsmFragment(defaultProject, "revision-default-asm", "fixture-default-asm");

		Path explicitProject = fixture("explicit-asm", "ASM", false);
		run(explicitProject, "generateSmartTestCoverage");
		assertAsmFragment(explicitProject, "revision-explicit-asm", "fixture-explicit-asm");
		assertFalse(Files.exists(defaultProject.resolve("build/stp/coverage/_generateSmartTestCoverage/fixture-explicit-asm")));
	}

	@Test
	void externalPublishedConsumerAutomaticallyResolvesTheShadedAgent() throws Exception {
		Path project = fixture("external-published", "ASM", false);
		String repository = slash(new File(System.getProperty("stp.test.maven.repository")));
		Files.writeString(project.resolve("settings.gradle"), """
			pluginManagement { repositories { maven { url = uri('%s') }; gradlePluginPortal(); mavenCentral() } }
			rootProject.name = 'external-published'
			""".formatted(repository));
		String build = Files.readString(project.resolve("build.gradle"));
		build = build.replace("id 'com.sap.oss.smart-test-picker'", "id 'com.sap.oss.smart-test-picker' version '0.1.0'");
		build = build.replace("repositories { mavenCentral() }",
				"repositories { maven { url = uri('" + repository + "') }; mavenCentral() }");
		build = build.replace("    stpAgent files('" + slash(agentJar()) + "')\n", "");
		Files.writeString(project.resolve("build.gradle"), build);

		BuildResult result = GradleRunner.create().withProjectDir(project.toFile())
				.withArguments("generateSmartTestCoverage", "--stacktrace").forwardOutput().build();
		assertEquals(SUCCESS, result.task(":generateSmartTestCoverage").getOutcome());
		assertAsmFragment(project, "revision-external-published", "fixture-external-published");
	}

	@Test
	void jacocoFallbackUsesLegacyCollectorWithoutAsmAgent() throws Exception {
		Path project = fixture("jacoco", "JACOCO", true);
		BuildResult result = run(project, "generateSmartTestMapping");
		assertEquals(SUCCESS, result.task(":generateSmartTestCoverage").getOutcome());
		assertTrue(result.getOutput().contains("Smart Test Picker coverage collector: JACOCO"));
		assertFalse(Files.exists(project.resolve("build/stp")));
		assertTrue(hasFile(project.resolve("build/jacoco"), ".exec"), "legacy per-test JaCoCo exec output missing");
		assertTrue(hasFile(project.resolve("build/jacoco-xml"), ".xml"), "legacy JaCoCo XML output missing");
		assertTrue(Files.isRegularFile(project.resolve("build/test-coverage-map.json")), "legacy map missing");
	}

	@Test
	void asmCoexistsWithOrdinaryProjectJacocoAndConfigurationCache() throws Exception {
		Path project = fixture("coexist", "ASM", true);
		BuildResult first = run(project, "generateSmartTestCoverage", "--configuration-cache");
		assertEquals(SUCCESS, first.task(":generateSmartTestCoverage").getOutcome());
		assertAsmFragment(project, "revision-coexist", "fixture-coexist");
		assertTrue(hasFile(project.resolve("build/jacoco"), ".exec"), "ordinary JaCoCo exec output missing");

		BuildResult second = run(project, "generateSmartTestCoverage", "--configuration-cache", "--rerun-tasks");
		assertTrue(second.getOutput().contains("Reusing configuration cache."));
		assertAsmFragment(project, "revision-coexist", "fixture-coexist");
		assertTrue(hasFile(project.resolve("build/jacoco"), ".exec"));
	}

	@Test
	void collectorStateDoesNotLeakWhenSwitchingInEitherDirection() throws Exception {
		Path project = fixture("switching", "JACOCO", true);
		run(project, "generateSmartTestCoverage", "--rerun-tasks");
		assertFalse(Files.exists(project.resolve("build/stp")));

		Path buildFile = project.resolve("build.gradle");
		Files.writeString(buildFile, Files.readString(buildFile).replace("coverageCollector = 'JACOCO'",
				"coverageCollector = 'ASM'"));
		run(project, "generateSmartTestCoverage", "--rerun-tasks");
		Path fragment = project.resolve("build/stp/coverage/_generateSmartTestCoverage/fixture-switching/fragment.json");
		assertTrue(Files.isRegularFile(fragment));
		String asmFragment = Files.readString(fragment);

		Files.writeString(buildFile, Files.readString(buildFile).replace("coverageCollector = 'ASM'",
				"coverageCollector = 'JACOCO'"));
		run(project, "generateSmartTestCoverage", "--rerun-tasks");
		assertEquals(asmFragment, Files.readString(fragment), "JaCoCo run rewrote the ASM fragment");
	}

	@Test
	void invalidCollectorFailsDuringConfiguration() throws Exception {
		Path project = fixture("invalid", "BOTH", false);
		BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
				.withArguments("tasks", "--stacktrace").buildAndFail();
		assertTrue(result.getOutput().contains("Supported values are ASM and JACOCO"));
	}

	private Path fixture(String name, String collector, boolean jacoco) throws IOException {
		Path project = temporary.resolve(name);
		Files.createDirectories(project.resolve("src/main/java/example"));
		Files.createDirectories(project.resolve("src/test/java/example"));
		Files.writeString(project.resolve("settings.gradle"), "rootProject.name = '" + name + "'\n");
		String collectorLine = collector == null ? "" : "    coverageCollector = '" + collector + "'\n";
		String build = """
			plugins {
			    id 'java'
			    %s
			    id 'com.sap.oss.smart-test-picker'
			}
			repositories { mavenCentral() }
			dependencies {
			    testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
			    stpAgent files('%s')
			    %s
			}
			test { useJUnitPlatform(); jvmArgs '-Dstp.fixture.flag=present' }
			smartTestPicker {
			%s    revision = 'revision-%s'
			    shardId = 'fixture-%s'
			    coverageIncludes = ['example.']
			}
			""".formatted(jacoco ? "id 'jacoco'" : "", slash(agentJar()),
				jacoco ? "stpJacocoCollector files('" + slash(coreJar()) + "')" : "", collectorLine, name, name);
		Files.writeString(project.resolve("build.gradle"), build);
		Files.writeString(project.resolve("src/main/java/example/OverloadedService.java"), """
			package example;
			public class OverloadedService {
			    public String value(String input) { return input.trim(); }
			    public int value(int input) { return input + 1; }
			}
			""");
		Files.writeString(project.resolve("src/test/java/example/OverloadedServiceTest.java"), """
			package example;
			import org.junit.jupiter.api.Test;
			import static org.junit.jupiter.api.Assertions.*;
			class OverloadedServiceTest {
			    @Test void mapsDescriptorExactly() {
			        assertEquals("present", System.getProperty("stp.fixture.flag"));
			        assertEquals("x", new OverloadedService().value(" x "));
			    }
			}
			""");
		if (jacoco) {
			git(project, "init", "-q");
			git(project, "config", "user.email", "stp-fixture@example.invalid");
			git(project, "config", "user.name", "STP Fixture");
			git(project, "add", ".");
			git(project, "commit", "-qm", "fixture");
		}
		return project;
	}

	private static void git(Path project, String... arguments) throws IOException {
		String[] command = new String[arguments.length + 1];
		command[0] = "git";
		System.arraycopy(arguments, 0, command, 1, arguments.length);
		try {
			int exit = new ProcessBuilder(command).directory(project.toFile()).inheritIO().start().waitFor();
			if (exit != 0) throw new IOException("git fixture command failed with exit " + exit);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("git fixture command interrupted", e);
		}
	}

	private BuildResult run(Path project, String... arguments) {
		return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
				.withArguments(arguments).forwardOutput().build();
	}

	private void assertAsmFragment(Path project, String revision, String shard) throws IOException {
		Path fragment = project.resolve("build/stp/coverage/_generateSmartTestCoverage/" + shard + "/fragment.json");
		assertTrue(Files.isRegularFile(fragment), "fragment missing: " + fragment);
		JsonObject json = JsonParser.parseString(Files.readString(fragment)).getAsJsonObject();
		assertEquals(2, json.get("schemaVersion").getAsInt());
		assertEquals(revision, json.get("revision").getAsString());
		assertEquals(shard, json.get("shardId").getAsString());
		assertTrue(json.toString().contains("OverloadedServiceTest"));
		assertTrue(json.toString().contains("value(Ljava/lang/String;)Ljava/lang/String;"));
		assertTrue(json.getAsJsonObject("collection").get("completed").getAsBoolean());
	}

	private static boolean hasFile(Path directory, String suffix) throws IOException {
		if (!Files.isDirectory(directory)) return false;
		try (var paths = Files.walk(directory)) {
			return paths.anyMatch(path -> path.getFileName().toString().endsWith(suffix));
		}
	}

	private static File agentJar() { return new File(System.getProperty("stp.test.agent.jar")); }
	private static File coreJar() { return new File(System.getProperty("stp.test.core.jar")); }
	private static String slash(File file) { return file.getAbsolutePath().replace("\\", "/").replace("'", "\\'"); }
}
