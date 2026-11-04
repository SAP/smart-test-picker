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
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

class GradleCollectorBackendFunctionalTest {
	@TempDir Path temporary;

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = { "ASM", "JACOCO" })
	void capabilitiesMatchCollectedOutput(String collector) throws Exception {
		boolean jacoco = collector.equals("JACOCO");
		Path project = fixture("capabilities", collector, jacoco);
		Files.delete(project.resolve("src/test/java/example/OverloadedServiceTest.java"));
		Path probes = Path.of("../smart-test-picker-maven/src/test/fixtures/capability-probes");
		for (String kind : java.util.List.of("main", "test")) {
			try (var files = Files.list(probes.resolve(kind))) {
				for (Path file : files.toList()) Files.copy(file,
						project.resolve("src/" + kind + "/java/example/" + file.getFileName()),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Path build = project.resolve("build.gradle");
		if (jacoco) {
			String revision = new String(new ProcessBuilder("git", "rev-parse", "HEAD")
					.directory(project.toFile()).start().getInputStream().readAllBytes()).trim();
			Files.writeString(project.resolve("assignment.json"), """
				{"version":1,"revision":"%s","shardId":"fixture-capabilities",
				 "tests":["gradle::test::example.CapabilitiesTest#probes"]}
				""".formatted(revision));
			Files.writeString(build, Files.readString(build) + """
				smartTestPicker { runtimeSchemaVersion=3; revision='%s'; executableAssignmentFile='assignment.json' }
				// Snapshot runtime artifacts before the dependent fragment-conversion task executes.
				test.doLast { copy { from 'build/stp'; into 'collection-snapshot' } }
				""".formatted(revision));
		}
		BuildResult result = run(project, jacoco ? "generateSmartTestMapping" : "generateSmartTestCoverage");
		Path fragment = project.resolve(jacoco ? "build/stp/executable-fragment.json"
				: "build/stp/coverage/_generateSmartTestCoverage/fixture-capabilities/fragment.json");
		Path collected = project.resolve(jacoco ? "collection-snapshot" : "build/stp/coverage");
		Path saved = Files.createDirectories(Path.of("build/stp-capabilities-01/" + collector.toLowerCase()));
		Files.writeString(saved.resolve("gradle.log"), result.getOutput());
		retain(collected, saved.resolve("collected"));
		retain(project.resolve("build/test-results"), saved.resolve("test-results"));
		if (jacoco) retain(project.resolve("build/stp/jacoco"), saved.resolve("converted"));
		Files.copy(fragment, saved.resolve("fragment.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		JsonObject json = JsonParser.parseString(Files.readString(fragment)).getAsJsonObject();
		if (jacoco) new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		else new com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		assertTrue(json.getAsJsonObject("collection").get("completed").getAsBoolean());
		assertEquals(0, json.getAsJsonArray("unmapped").size());
		assertEquals(1, json.getAsJsonObject("tests").size());
		String identity = (jacoco ? "gradle::test::" : "") + "example.CapabilitiesTest#probes";
		JsonObject leaf = json.getAsJsonObject("tests").getAsJsonObject(identity);
		assertNotNull(leaf, identity);
		assertEquals("PASS", leaf.get("outcome").getAsString());
		String methods = leaf.getAsJsonArray("methods").toString();
		assertTrue(methods.contains("example.OverloadedService#value"), methods);
		assertFalse(leaf.getAsJsonArray("classes").toString().contains("example.SetupOnly"));
		boolean setup = json.getAsJsonArray("setupScopes").toString().contains("example.SetupOnly");
		if (setup) assertTrue(json.getAsJsonArray("setupScopes").toString().contains("example.CapabilitiesTest"));
		if (jacoco) {
			assertTrue(hasFile(collected, ".exec"));
			assertTrue(hasFile(collected, ".setup"), "Setup executed but no positive setup session was collected");
			assertSetupReport(project.resolve("build/stp/jacoco"));
		}
		boolean direct = false;
		try (var files = Files.walk(collected)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
				JsonObject value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				if (value.has("tests") && value.has("schemaVersion") && value.get("schemaVersion").getAsString().equals("2")) direct = true;
			}
		}
		CollectorCapabilities observed = new CollectorCapabilities(
				leaf.getAsJsonArray("classes").toString().contains("example.OverloadedService"),
				methods.contains("example.ImplicitException#length"), setup,
				methods.contains("example.OverloadedService#value(Ljava/lang/String;)Ljava/lang/String;")
						&& !methods.contains("example.OverloadedService#value(I)I"), direct);
		Files.writeString(saved.resolve("observed.txt"), observed + "\n");
		System.out.println("Gradle " + collector + " observed: " + observed);
		assertEquals(observed, jacoco ? new JacocoCoverageCollectorBackend(null).capabilities()
				: new AsmCoverageCollectorBackend(null).capabilities());
	}

	private static void retain(Path source, Path destination) throws IOException {
		if (!Files.exists(source)) return;
		try (var files = Files.walk(source)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path output = destination.resolve(source.relativize(file));
				Files.createDirectories(output.getParent());
				Files.copy(file, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static void assertSetupReport(Path root) throws Exception {
		var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		boolean covered = false;
		try (var files = Files.walk(root)) {
			for (Path file : files.filter(p -> p.getFileName().toString().startsWith("session_setup_")
					&& p.toString().endsWith(".xml")).toList()) {
				var methods = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("method");
				for (int i = 0; i < methods.getLength(); i++) {
					var method = (org.w3c.dom.Element) methods.item(i);
					if (!method.getAttribute("name").equals("setupOnly")) continue;
					var counters = method.getElementsByTagName("counter");
					for (int j = 0; j < counters.getLength(); j++) {
						var counter = (org.w3c.dom.Element) counters.item(j);
						if (counter.getAttribute("type").equals("METHOD") && counter.getAttribute("covered").equals("1")) covered = true;
					}
				}
			}
		}
		assertTrue(covered, "Positive setup-only coverage must exist before deciding whether the fragment retains it");
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void ordinaryCoreConsumerRunsBothTestsWithoutCollection(boolean autodetection) throws Exception {
		Path project = Files.createDirectories(temporary.resolve("ordinary"));
		Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'ordinary-consumer'\n");
		Files.writeString(project.resolve("build.gradle"), """
			plugins { id 'java' }
			repositories {
			    maven { url = uri('%s') }
			    mavenCentral()
			}
			dependencies {
			    testImplementation 'com.sap.oss.smart-test-picker:smart-test-picker-core:0.3.0-SNAPSHOT'
			    testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
			    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
			}
			test { useJUnitPlatform(); systemProperty 'junit.jupiter.extensions.autodetection.enabled', '%s' }
			""".formatted(slash(new File(System.getProperty("stp.test.maven.repository"))), autodetection));
		Path source = Files.createDirectories(project.resolve("src/test/java/fixture"));
		Files.copy(Path.of("../smart-test-picker-maven/src/test/fixtures/ordinary-core/src/test/java/fixture/OrdinaryTest.java"),
				source.resolve("OrdinaryTest.java"));
		// No plugin-under-test classpath: this is an ordinary Java consumer, not an STP task.
		BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withArguments("test").build();
		assertEquals(SUCCESS, result.task(":test").getOutcome());
		Path xml = project.resolve("build/test-results/test/TEST-fixture.OrdinaryTest.xml");
		var report = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile());
		assertEquals("2", report.getDocumentElement().getAttribute("tests"));
		assertEquals("0", report.getDocumentElement().getAttribute("failures"));
		assertEquals("0", report.getDocumentElement().getAttribute("errors"));
		assertEquals("0", report.getDocumentElement().getAttribute("skipped"));
		assertEquals(2, report.getElementsByTagName("testcase").getLength());
		assertFalse(result.getOutput().contains("Active JaCoCo runtime"), result.getOutput());
		assertFalse(Files.exists(project.resolve("build/jacoco")));
		Path saved = Files.createDirectories(Path.of("build/maven-asm-03-fix/ordinary-" + autodetection));
		Files.writeString(saved.resolve("gradle.log"), result.getOutput());
		Files.copy(xml, saved.resolve("tests.xml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	@Test
	void defaultAsmIgnoresConsumerDeclaredCoreWithExtensionAutodetection() throws Exception {
		Path project = fixture("consumer-core", null, false);
		Path build = project.resolve("build.gradle");
		Files.writeString(build, Files.readString(build) + """
			dependencies { testImplementation files('%s') }
			test { systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true' }
			""".formatted(slash(coreJar())));
		BuildResult result = run(project, "generateSmartTestCoverage");
		assertEquals(SUCCESS, result.task(":generateSmartTestCoverage").getOutcome());
		assertFalse(result.getOutput().contains("Active JaCoCo runtime"), result.getOutput());
		assertFalse(result.getOutput().contains("JacocoPerTestListener"), result.getOutput());
		assertAsmFragment(project, "revision-consumer-core", "fixture-consumer-core");
		var json = JsonParser.parseString(Files.readString(project.resolve(
				"build/stp/coverage/_generateSmartTestCoverage/fixture-consumer-core/fragment.json"))).getAsJsonObject();
		assertEquals(1, json.getAsJsonObject("tests").size());
		assertEquals(0, json.getAsJsonArray("unmapped").size());
		assertFalse(Files.exists(project.resolve("build/jacoco")));
	}

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
	void schemaV3FailsClosedWithoutExecutableAssignment() throws Exception {
		Path project = fixture("asm-v3", "ASM", false);
		Path buildFile = project.resolve("build.gradle");
		Files.writeString(buildFile, Files.readString(buildFile).replace(
				"coverageIncludes = ['example.']",
				"runtimeSchemaVersion = 3\n    coverageIncludes = ['example.']"));
		BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
				.withArguments("generateSmartTestCoverage", "--stacktrace").buildAndFail();
		assertTrue(result.getOutput().contains("requires -Dstp.executableAssignment"));
	}

	@Test
	void asmFailedTestPublishesNoPartialMappedEntry(@TempDir Path ignored) throws Exception {
		Path project = fixture("failed-asm", "ASM", false);
		Files.writeString(project.resolve("src/main/java/example/LaterService.java"),
				"package example; public class LaterService { public void unreached() {} }\n");
		Files.writeString(project.resolve("src/test/java/example/OverloadedServiceTest.java"), """
			package example;
			import org.junit.jupiter.api.Test;
			class OverloadedServiceTest {
			 @Test void mapsDescriptorExactly() {
			   new OverloadedService().value("observed A");
			   throw new AssertionError("stop before production B");
			 }
			}
			""");
		Files.writeString(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle"))
				+ "\ntasks.named('generateSmartTestCoverage') { ignoreFailures = true }\n");
		run(project, "generateSmartTestCoverage", "--rerun-tasks");
		Path fragmentFile = project.resolve("build/stp/coverage/_generateSmartTestCoverage/fixture-failed-asm/fragment.json");
		var fragment = new com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec()
				.deserialize(Files.readAllBytes(fragmentFile));
		assertTrue(fragment.collectionCompleted());
		assertTrue(fragment.tests().isEmpty());
		assertEquals(com.sap.oss.smarttestpicker.coverage.model.UnmappedReason.FAILED,
				fragment.unmapped().get(0).reason());
		assertFalse(Files.readString(fragmentFile).contains("LaterService"));
		assertTrue(Files.readString(project.resolve("build/test-results/generateSmartTestCoverage/TEST-example.OverloadedServiceTest.xml"))
				.contains("failure"));
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
		build = build.replace("id 'com.sap.oss.smart-test-picker'", "id 'com.sap.oss.smart-test-picker' version '"
				+ System.getProperty("stp.test.version") + "'");
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
			        assertEquals("true", System.getProperty("smartTestPicker.collectionEnabled"));
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
