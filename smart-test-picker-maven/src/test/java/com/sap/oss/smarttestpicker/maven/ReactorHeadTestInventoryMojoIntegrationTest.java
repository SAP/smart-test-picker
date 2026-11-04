// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestInventory;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlan;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionPlanMode;
import com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentJoiner;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.google.gson.JsonParser;

class ReactorHeadTestInventoryMojoIntegrationTest {
	private static final String STP_VERSION = System.getProperty("smartTestPicker.version");
	private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:" + STP_VERSION + ":";
	private static final Path LOCAL_REPOSITORY = Path.of("build/functional-test-maven-local-"
			+ ProcessHandle.current().pid()).toAbsolutePath();

	@ParameterizedTest
	@ValueSource(strings = { "literal", "property", "expression", "execution", "profile", "management", "default-attribute", "cpu",
			"zero", "reuse", "unresolved", "legacy", "failsafe" })
	void asmMappingRejectsUnsafeForksBeforeAnyTestJvm(String source, @TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		String provider = source.equals("failsafe") ? "failsafe" : "surefire";
		String setting = source.equals("reuse") ? "reuseForks" : source.equals("legacy") ? "forkMode" : "forkCount";
		String value = switch (source) {
			case "cpu" -> "2C"; case "zero" -> "0"; case "reuse" -> "false";
			case "unresolved" -> "${unknown.mapping.forks}"; case "legacy" -> "always"; default -> "2";
		};
		String configured = source.equals("expression") ? "${mapping.forks}" : value;
		String configuration = "<configuration><" + setting + ">" + configured + "</" + setting + "></configuration>";
		if (source.equals("default-attribute")) configuration = "<configuration><forkCount default-value=\"2\"/></configuration>";
		if (source.equals("execution")) configuration = "<executions><execution><id>default-test</id>" + configuration
				+ "</execution></executions>";
		Path modulePom = fixture.resolve("module-b/pom.xml");
		String build = "<build><plugins><plugin><artifactId>maven-" + provider + "-plugin</artifactId>"
				+ configuration + "</plugin></plugins></build>";
		if (source.equals("management")) build = build.replace("<build>", "<build><pluginManagement>")
				.replace("</build>", "</pluginManagement></build>");
		if (!source.equals("property")) {
			if (source.equals("profile")) build = "<profiles><profile><id>unsafe-forks</id>" + build + "</profile></profiles>";
			Files.writeString(modulePom, Files.readString(modulePom).replace("</project>", build + "</project>"));
		}
		String target = "maven:module-b" + (provider.equals("failsafe") ? "@failsafe@fixture-it" : "");
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment(target + "::b.BTests#b2", target + "::shared.SharedTest#same"));
		Path fragment = fixture.resolve("module-b/target/stp/coverage-fragment-v3.json"),
				diagnostic = fixture.resolve("module-b/target/stp/execution-evidence-v2.json");
		var arguments = new java.util.ArrayList<>(List.of(PLUGIN + "prepare-reactor-executable-mapping", "test",
				"-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.coverageCollector=ASM",
				"-Djacoco.skip=true", "-DsmartTestPicker.asm.includes=a.,b."));
		if (source.equals("property")) arguments.add("-DforkCount=2");
		if (source.equals("expression")) arguments.add("-Dmapping.forks=2");
		if (source.equals("profile")) arguments.add("-Punsafe-forks");
		if (provider.equals("failsafe")) {
			arguments.add("-DsmartTestPicker.executionType=failsafe");
			arguments.add("-DsmartTestPicker.executionId=fixture-it");
		}
		Result result = maven(fixture, arguments.toArray(String[]::new));
		Path saved = Files.createDirectories(Path.of("build/maven-asm-04", "rejected-" + source));
		Files.writeString(saved.resolve("maven.log"), result.output());
		assertTrue(result.exitCode() != 0, result.output());
		assertTrue(result.output().contains("unsupported " + setting + "=" + value), result.output());
		assertTrue(result.output().contains("module-b / maven-" + provider + "-plugin"), result.output());
		assertFalse(result.output().contains("ASM mapping agent "), "Fork validation must precede agent resolution");
		assertFalse(result.output().contains("--- surefire:"), result.output());
		assertFalse(Files.exists(fragment), "A rejected run must not publish a partial fragment");
		assertFalse(Files.exists(diagnostic), "The ASM agent must not have started");
		for (String module : List.of("module-a", "module-b", "module-zero")) {
			assertFalse(Files.exists(fixture.resolve(module + "/target/surefire-reports")));
			assertFalse(Files.exists(fixture.resolve(module + "/target/test-classes")), "Preparation must precede test compilation");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "default", "property", "literal-over-property" })
	void asmMappingSingleReusedForkRetainsBothTestClasses(String source, @TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		Result install = maven(fixture, "-pl", "module-a", "-am", "install", "-DskipTests");
		assertEquals(0, install.exitCode(), install.output());
		if (source.equals("literal-over-property")) {
			Path pom = fixture.resolve("module-b/pom.xml");
			Files.writeString(pom, Files.readString(pom).replace("</project>", """
				<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>
				<configuration><forkCount>1</forkCount><reuseForks>true</reuseForks></configuration>
				</plugin></plugins></build></project>
				"""));
		}
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-b::b.BTests#b2", "maven:module-b::shared.SharedTest#same"));
		Path fragment = fixture.resolve("module-b/target/stp/coverage-fragment-v3.json"),
				diagnostic = fixture.resolve("module-b/target/stp/execution-evidence-v2.json");
		var arguments = new java.util.ArrayList<>(List.of("-pl", ".,module-b", PLUGIN + "prepare-reactor-executable-mapping", "test",
				"-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.reactorRoot=" + fixture,
				"-DsmartTestPicker.coverageCollector=ASM", "-Djacoco.skip=true", "-DsmartTestPicker.asm.includes=a.,b."));
		if (!source.equals("default")) arguments.add("-DforkCount=" + (source.equals("property") ? "1" : "2"));
		Result result = maven(fixture, arguments.toArray(String[]::new));
		Path saved = Files.createDirectories(Path.of("build/maven-asm-04", "accepted-" + source));
		Files.writeString(saved.resolve("maven.log"), result.output());
		assertEquals(0, result.exitCode(), result.output());
		var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		assertEquals(Set.of("maven:module-b::b.BTests#b2", "maven:module-b::shared.SharedTest#same"),
				decoded.tests().keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()));
		assertTrue(decoded.collectionCompleted()); assertTrue(decoded.unmapped().isEmpty());
		decoded.tests().forEach((identity, coverage) -> assertEquals(
				identity.test().className().equals("b.BTests") ? Set.of("a.AValue", "b.BValue") : Set.of(), coverage.coveredClasses()));
		Files.copy(fragment, saved.resolve("fragment.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(diagnostic, saved.resolve("diagnostic.json"), StandardCopyOption.REPLACE_EXISTING);
	}

	@ParameterizedTest
	@ValueSource(strings = { "literal", "prepare-agent", "both", "user-property", "user-late-property" })
	void asmPreparedAttachmentPreservesArgLineShapes(String shape, @TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor with spaces")); initializeGit(fixture);
		String argLine = switch (shape) {
			case "literal" -> "-Xmx256m -Dproject.literal=preserved";
			case "prepare-agent" -> "@{argLine}";
			default -> "@{argLine} -Xmx256m -Dproject.literal=preserved";
		};
		Path pom = fixture.resolve("module-b/pom.xml");
		boolean userProperty = shape.startsWith("user-");
		boolean jacoco = !shape.equals("literal") && !shape.equals("user-property");
		Files.writeString(pom, Files.readString(pom).replace("</project>", userProperty
				? "<properties><argLine>-Dproject.literal=wrong</argLine></properties></project>"
				: "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration><argLine>"
				+ argLine + "</argLine></configuration></plugin></plugins></build></project>"));
		Path test = fixture.resolve("module-b/src/test/java/b/BTests.java");
		Files.writeString(test, Files.readString(test).replace("new BValue().value();", """
			org.junit.jupiter.api.Assertions.assertEquals("true", System.getProperty("smartTestPicker.collectionEnabled"));
			org.junit.jupiter.api.Assertions.assertEquals("ASM", System.getProperty("smartTestPicker.coverageCollector"));
			java.util.List<String> arguments = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
			long stpAgents = arguments.stream().filter(a -> a.startsWith("-javaagent:") && a.contains("stp-agent")).count();
			org.junit.jupiter.api.Assertions.assertEquals(1L, stpAgents);
			%s
			new BValue().value();
			""".formatted(shape.equals("prepare-agent") ? "" :
				"org.junit.jupiter.api.Assertions.assertEquals(\"preserved\", System.getProperty(\"project.literal\"));")));
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-b::b.BTests#b2", "maven:module-b::shared.SharedTest#same"));
		var command = new java.util.ArrayList<>(List.of("-X", PLUGIN + "prepare-reactor-executable-mapping", "test",
				"-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.coverageCollector=ASM",
				"-DsmartTestPicker.asm.includes=a.,b.", "-Djacoco.skip=" + !jacoco));
		if (userProperty) command.add("-DargLine=" + (jacoco ? "@{argLine} " : "") + "-Xmx256m -Dproject.literal=preserved");
		Result result = maven(fixture, command.toArray(String[]::new));
		Path saved = Files.createDirectories(Path.of("build/maven-asm-05", shape));
		Files.writeString(saved.resolve("maven.log"), result.output());
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("ASM mapping agent com.sap.oss.smart-test-picker:stp-agent:jar:" + STP_VERSION), result.output());
		assertTrue(result.output().contains(LOCAL_REPOSITORY.resolve("com/sap/oss/smart-test-picker/stp-agent").toString()), result.output());
		assertArrayEquals(Files.readAllBytes(Path.of(System.getProperty("stp.test.agent.jar"))),
				Files.readAllBytes(LOCAL_REPOSITORY.resolve("com/sap/oss/smart-test-picker/stp-agent/" + STP_VERSION
						+ "/stp-agent-" + STP_VERSION + ".jar")), "Resolved agent must match the current shaded build");
		String realm = result.output().lines().filter(line -> line.contains("ASM mapping plugin realm:")).findFirst().orElseThrow();
		assertFalse(realm.contains("stp-agent"), realm); assertFalse(realm.contains("stp-runtime"), realm);
		assertFalse(realm.contains("stp-junit-adapter"), realm);
		String fork = result.output().lines().filter(line -> line.contains("Forking command line:") && line.contains("stp-agent")).findFirst().orElseThrow();
		if (jacoco) {
			assertTrue(fork.indexOf("org.jacoco.agent") < fork.indexOf("stp-agent"), fork);
			if (shape.equals("both")) assertTrue(fork.indexOf("org.jacoco.agent") < fork.indexOf("-Dproject.literal"), fork);
		}
		Path fragmentPath = fixture.resolve("module-b/target/stp/coverage-fragment-v3.json");
		var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragmentPath));
		assertEquals(Set.of(ExecutableTestIdentity.parse("maven:module-b::b.BTests#b2"),
				ExecutableTestIdentity.parse("maven:module-b::shared.SharedTest#same")), fragment.tests().keySet());
		assertTrue(fragment.unmapped().isEmpty()); assertTrue(fragment.collectionCompleted());
		fragment.tests().forEach((identity, coverage) -> assertEquals(identity.test().className().equals("b.BTests")
				? Set.of("a.AValue", "b.BValue") : Set.of(), coverage.coveredClasses()));
		Files.copy(fragmentPath, saved.resolve("fragment.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(fixture.resolve("module-b/target/stp/execution-evidence-v2.json"), saved.resolve("diagnostic.json"), StandardCopyOption.REPLACE_EXISTING);
	}

	@ParameterizedTest
	@ValueSource(strings = { "absent", "", "  ", "," })
	void asmPreparedAttachmentRejectsMissingScope(String includes, @TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-b::b.BTests#b2"));
		var args = new java.util.ArrayList<>(List.of(PLUGIN + "prepare-reactor-executable-mapping", "test",
				"-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.coverageCollector=ASM"));
		if (!includes.equals("absent")) args.add("-DsmartTestPicker.asm.includes=" + includes);
		Result result = maven(fixture, args.toArray(String[]::new));
		assertTrue(result.exitCode() != 0, result.output());
		assertTrue(result.output().contains("smartTestPicker.asm.includes"), result.output());
		assertFalse(result.output().contains("ASM mapping agent "), result.output());
		assertFalse(Files.exists(fixture.resolve("module-b/target/test-classes")));
		assertFalse(Files.exists(fixture.resolve("module-b/target/stp/coverage-fragment-v3.json")));
		Path saved = Files.createDirectories(Path.of("build/maven-asm-05/scope-" + (includes.equals("absent") ? "absent" : Integer.toHexString(includes.hashCode()))));
		Files.writeString(saved.resolve("maven.log"), result.output());
	}

	@ParameterizedTest
	@ValueSource(strings = { "surefire", "failsafe" })
	void asmPreparedAttachmentUsesDistinctCanonicalTargets(String provider, @TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		boolean failsafe = provider.equals("failsafe");
		String qualifier = failsafe ? "@failsafe@fixture-it" : "";
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-a" + qualifier + "::a.ATests#a1",
				"maven:module-b" + qualifier + "::b.BTests#b2"));
		var args = new java.util.ArrayList<>(List.of(PLUGIN + "prepare-reactor-executable-mapping",
				failsafe ? "integration-test" : "test", "-DsmartTestPicker.testsFile=" + assignment,
				"-DsmartTestPicker.coverageCollector=ASM", "-DsmartTestPicker.asm.includes=a.,b.",
				"-DsmartTestPicker.asm.excludes=unused.package.", "-Djacoco.skip=true"));
		if (failsafe) {
			args.addAll(List.of("-Pfailsafe", "-DsmartTestPicker.executionType=failsafe", "-DsmartTestPicker.executionId=fixture-it"));
		}
		Result result = maven(fixture, args.toArray(String[]::new));
		Path saved = Files.createDirectories(Path.of("build/maven-asm-05/targets-" + provider));
		Files.writeString(saved.resolve("maven.log"), result.output());
		assertEquals(0, result.exitCode(), result.output());
		var runIds = new java.util.HashSet<String>();
		for (String module : List.of("module-a", "module-b")) {
			String suffix = failsafe ? "-" + module + "_failsafe_fixture-it" : "";
			Path fragmentFile = fixture.resolve(module + "/target/stp/coverage-fragment-v3" + suffix + ".json");
			Path diagnosticFile = fixture.resolve(module + "/target/stp/execution-evidence-v2" + suffix + ".json");
			var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragmentFile));
			String target = "maven:" + module + qualifier;
			assertEquals(Set.of(ExecutableTestIdentity.parse(target + (module.equals("module-a") ? "::a.ATests#a1" : "::b.BTests#b2"))), fragment.tests().keySet());
			assertTrue(fragment.collectionCompleted()); assertTrue(fragment.unmapped().isEmpty());
			var diagnostic = JsonParser.parseString(Files.readString(diagnosticFile)).getAsJsonObject();
			String runId = diagnostic.get("runId").getAsString();
			assertEquals(java.util.UUID.nameUUIDFromBytes(("reactor-revision\nreactor-shard\n" + target)
					.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), runId);
			assertTrue(runIds.add(runId));
			assertTrue(diagnostic.getAsJsonObject("configuration").getAsJsonArray("excludes").asList().stream()
					.anyMatch(value -> value.getAsString().equals("unused.package.")));
			Files.copy(fragmentFile, saved.resolve(module + "-fragment.json"), StandardCopyOption.REPLACE_EXISTING);
			Files.copy(diagnosticFile, saved.resolve(module + "-diagnostic.json"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Test
	void jacocoMappingStillAllowsTwoForks(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-a::a.ATests#a1", "maven:module-a::shared.SharedTest#same"));
		Result result = maven(fixture, PLUGIN + "prepare-reactor-executable-mapping", "test",
				"-DsmartTestPicker.testsFile=" + assignment, "-DforkCount=2");
		Path saved = Files.createDirectories(Path.of("build/maven-asm-04/jacoco-two-forks"));
		Files.writeString(saved.resolve("maven.log"), result.output());
		assertEquals(0, result.exitCode(), result.output());
		assertFalse(result.output().contains("ASM mapping requires"), result.output());
		for (String test : List.of("a.ATests", "shared.SharedTest")) {
			var report = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
					fixture.resolve("module-a/target/surefire-reports/TEST-" + test + ".xml").toFile());
			assertEquals("1", report.getDocumentElement().getAttribute("tests"));
			assertEquals("0", report.getDocumentElement().getAttribute("errors"));
			assertEquals("0", report.getDocumentElement().getAttribute("failures"));
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void ordinaryCoreConsumerRunsBothTestsWithoutCollection(boolean autodetection, @TempDir Path temp) throws Exception {
		Path fixture = copyFixture("ordinary-core", temp.resolve("consumer"));
		Result result = maven(fixture, "test", "-Djunit.jupiter.extensions.autodetection.enabled=" + autodetection);
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("Tests run: 2, Failures: 0, Errors: 0, Skipped: 0"), result.output());
		var report = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(fixture.resolve("target/surefire-reports/TEST-fixture.OrdinaryTest.xml").toFile());
		assertEquals("2", report.getDocumentElement().getAttribute("tests"));
		assertEquals(2, report.getElementsByTagName("testcase").getLength());
		assertEquals(0, report.getElementsByTagName("failure").getLength());
		assertEquals(0, report.getElementsByTagName("error").getLength());
		assertFalse(result.output().contains("Active JaCoCo runtime"), result.output());
		assertFalse(Files.exists(fixture.resolve("target/jacoco")));
		Path saved = Files.createDirectories(Path.of("build/maven-asm-03-fix/ordinary-" + autodetection));
		Files.writeString(saved.resolve("maven.log"), result.output());
		Files.copy(fixture.resolve("target/surefire-reports/TEST-fixture.OrdinaryTest.xml"),
				saved.resolve("tests.xml"), StandardCopyOption.REPLACE_EXISTING);
	}

	@Test
	void asmCollectorWithConsumerCoreAndAutodetectionDoesNotActivateJacoco(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor"));
		Path test = fixture.resolve("module-b/src/test/java/b/BTests.java");
		Files.writeString(test, Files.readString(test).replace("new BValue().value();", """
				org.junit.jupiter.api.Assertions.assertEquals("true", System.getProperty("smartTestPicker.collectionEnabled"));
				org.junit.jupiter.api.Assertions.assertEquals("ASM", System.getProperty("smartTestPicker.coverageCollector"));
				org.junit.jupiter.api.Assertions.assertEquals("true", System.getProperty("junit.jupiter.extensions.autodetection.enabled"));
				org.junit.jupiter.api.Assertions.assertThrows(ClassNotFoundException.class,
				    () -> Class.forName("org.jacoco.agent.rt.RT"));
				org.junit.jupiter.api.Assertions.assertTrue(com.sap.oss.smarttestpicker.JacocoPerTestListener.class
				    .getProtectionDomain().getCodeSource().getLocation().toString().contains("smart-test-picker-core"));
				new BValue().value();
				"""));
		String revision = initializeGit(fixture);
		Result install = maven(fixture, "-pl", "module-a", "-am", "install", "-DskipTests");
		assertEquals(0, install.exitCode(), install.output());
		Path fragment = fixture.resolve("asm-fragment.json");
		Path diagnostic = fixture.resolve("asm-diagnostic.json");
		String options = "output=" + diagnostic + ";fragmentOutput=" + fragment
				+ ";schemaVersion=3;revision=" + revision
				+ ";shardId=asm-shard;executionTarget=maven:module-b;runId=asm-core;includes=a.,b.";
		// Manual attachment is fixture setup only; this task does not add Maven ASM orchestration.
		Result result = maven(fixture, "-pl", "module-b", "test", "-Dtest=b.BTests#b2,shared.SharedTest",
				"-DsmartTestPicker.collectionEnabled=true",
				"-Djacoco.skip=true", "-DsmartTestPicker.coverageCollector=ASM",
				"-Djunit.jupiter.extensions.autodetection.enabled=true",
				"-DargLine=-javaagent:" + System.getProperty("stp.test.agent.jar") + "=" + options);
		assertEquals(0, result.exitCode(), result.output());
		assertFalse(result.output().contains("Active JaCoCo runtime"), result.output());
		assertFalse(result.output().contains("JacocoPerTestListener"), result.output());
		var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		assertEquals(revision, decoded.revision().value());
		assertTrue(decoded.collectionCompleted());
		assertTrue(decoded.unmapped().isEmpty());
		assertEquals(Set.of("maven:module-b::b.BTests#b2", "maven:module-b::shared.SharedTest#same"),
				decoded.tests().keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()));
		decoded.tests().forEach((identity, coverage) -> {
			assertEquals(com.sap.oss.smarttestpicker.coverage.model.TestOutcome.PASS, coverage.outcome());
			assertEquals(identity.test().className().equals("b.BTests") ? Set.of("a.AValue", "b.BValue") : Set.of(),
					coverage.coveredClasses());
		});
		assertFalse(Files.exists(fixture.resolve("module-b/target/jacoco")), "Inactive collector wrote files");
		Path saved = Files.createDirectories(Path.of("build/maven-asm-03"));
		Files.copy(fragment, saved.resolve("fragment.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(diagnostic, saved.resolve("diagnostic.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.writeString(saved.resolve("maven.log"), result.output());
	}

	@Test
	void omittedActivationCannotSatisfyMappingEvidenceOrJoin(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		var identity = ExecutableTestIdentity.parse("maven:module-a::a.ATests#a1");
		Path module = fixture.resolve("module-a");
		Path output = module.resolve("target/stp/coverage-fragment-v3.json");
		Path evidence = module.resolve("target/stp/execution-evidence-v2.json");
		// Deliberately omit preparation/activation, but retain the real JaCoCo agent and output directories.
		Files.createDirectories(module.resolve("target/jacoco-xml"));
		Result collection = maven(fixture, "-pl", "module-a", "verify", "-Dtest=a.ATests#a1",
				"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.executionTarget=maven:module-a",
				"-DsmartTestPicker.moduleFragmentOutput=" + output, "-DsmartTestPicker.moduleEvidenceOutput=" + evidence);
		assertEquals(0, collection.exitCode(), collection.output());
		assertTrue(collection.output().contains("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"), collection.output());
		try (var files = Files.list(module.resolve("target/jacoco"))) {
			assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("session_")),
					"Inactive collection must not write per-test exec or identity artifacts");
		}
		var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(output));
		assertFalse(fragment.collectionCompleted());
		assertTrue(fragment.tests().isEmpty()); assertTrue(fragment.unmapped().isEmpty());
		var project = new org.apache.maven.project.MavenProject();
		var build = new org.apache.maven.model.Build(); build.setDirectory(module.resolve("target").toString()); project.setBuild(build);
		var plan = new ExecutionPlan(com.sap.oss.smarttestpicker.coverage.ExecutionPlanContract.VERSION,
				ExecutionPlanMode.SELECT, Set.of(identity), fragment.revision());
		var verified = MavenExecutionEvidence.verify(plan, java.util.Map.of(identity.target(), project), true);
		assertFalse(verified.valid()); assertEquals(Set.of(identity), verified.missing());
		var inventory = new ExecutableTestInventory(fragment.revision(), Set.of(identity));
		var shards = new ExecutableShardPlan(fragment.revision(), java.util.Map.of(fragment.shardId(), Set.of(identity)));
		var rejected = assertThrows(IllegalArgumentException.class, () -> new ExecutableCoverageFragmentJoiner().join(
				inventory, shards, List.of(fragment), Set.of(), java.time.Instant.EPOCH,
				new GeneratorProvenance(STP_VERSION, STP_VERSION, "0.8.13", "17")));
		assertTrue(rejected.getMessage().toLowerCase().contains("missing"), rejected.getMessage());
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment(identity.toString()));
		Result aggregation = maven(fixture, PLUGIN + "aggregate-reactor-coverage-fragment",
				"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment,
				"-DsmartTestPicker.fragmentOutput=" + fixture.resolve("final.json"),
				"-DsmartTestPicker.evidenceOutput=" + fixture.resolve("final-evidence.json"));
		assertTrue(aggregation.exitCode() != 0, aggregation.output());
		assertTrue(aggregation.output().contains("Incomplete module coverage fragment"), aggregation.output());
		assertFalse(Files.exists(fixture.resolve("final.json")));
		Path saved = Files.createDirectories(Path.of("build/maven-asm-03-fix/omitted-activation"));
		Files.copy(output, saved.resolve("fragment.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.writeString(saved.resolve("collection.log"), collection.output());
		Files.writeString(saved.resolve("aggregation.log"), aggregation.output());
		Files.writeString(saved.resolve("verification.txt"), "missing=" + verified.missing() + "\njoin=" + rejected.getMessage() + "\n");
	}

	@ParameterizedTest
	@ValueSource(strings = { "default", "surefire", "failsafe" })
	void isolatedClassSourceOnlyVintageInventoryMatchesJacocoMapping(String provider, @TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor"));
		Path pom = fixture.resolve("module-a/pom.xml");
		Files.writeString(pom, Files.readString(pom).replace("</dependencies>", """
				<dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId><version>1.9.3</version><scope>test</scope></dependency>
				<dependency><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId><version>5.9.3</version><scope>test</scope></dependency>
				<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
				</dependencies>
				"""));
		// A field-backed custom runner intentionally provides no Java test method / MethodSource.
		Files.writeString(fixture.resolve("module-a/src/test/java/a/ClassSourceOnlyTest.java"), """
				package a;
				@org.junit.runner.RunWith(ClassSourceOnlyTest.RuleRunner.class)
				public class ClassSourceOnlyTest {
				    public static final String architecture_rule = "rule";
				    public static class RuleRunner extends org.junit.runner.Runner {
				        private final org.junit.runner.Description root;
				        private final org.junit.runner.Description rule;
				        public RuleRunner(Class<?> type) {
				            root = org.junit.runner.Description.createSuiteDescription(type);
				            rule = org.junit.runner.Description.createTestDescription(type, "architecture_rule");
				            root.addChild(rule);
				        }
				        public org.junit.runner.Description getDescription() { return root; }
				        public void run(org.junit.runner.notification.RunNotifier notifier) {
				            notifier.fireTestStarted(rule);
				            try { org.junit.Assert.assertEquals(1, new AValue().value()); }
				            catch (Throwable failure) {
				                notifier.fireTestFailure(new org.junit.runner.notification.Failure(rule, failure));
				            } finally { notifier.fireTestFinished(rule); }
				        }
				    }
				}
				""");
		String revision = initializeGit(fixture);
		boolean qualified = !provider.equals("default");
		String executionType = qualified ? provider : "";
		String executionId = qualified ? (provider.equals("surefire") ? "default-test" : "fixture-it") : "";
		String expected = "maven:module-a" + (qualified ? "@" + provider + "@" + executionId : "")
				+ "::a.ClassSourceOnlyTest#architecture_rule";
		Path inventoryFile = fixture.resolve("complete-inventory.json");
		Result discovery = maven(fixture, "-X", "-Pfailsafe", "process-test-classes",
				PLUGIN + "generate-reactor-head-test-inventory", "-DsmartTestPicker.schemaVersion=3",
				"-DsmartTestPicker.prHeadRevision=" + revision, "-DsmartTestPicker.executionType=" + executionType,
				"-DsmartTestPicker.executionId=" + executionId,
				"-DsmartTestPicker.testFilter=a.ClassSourceOnlyTest", "-DsmartTestPicker.outputFile=" + inventoryFile);
		assertEquals(0, discovery.exitCode(), discovery.output());
		assertTrue(discovery.output().lines().anyMatch(line -> line.contains("fixture:module-a:jar:1")
				&& line.contains("TargetJUnitClassLoader")), discovery.output());
		var inventory = new ExecutableHeadTestInventoryCodec().read(inventoryFile.toFile());
		assertEquals(Set.of(expected), inventory.runnableTests().stream().map(Object::toString)
				.collect(java.util.stream.Collectors.toSet()));
		assertEquals(revision, inventory.revision());
		assertFalse(Files.exists(fixture.resolve("module-a/target/surefire-reports")));
		assertFalse(Files.exists(fixture.resolve("module-a/target/failsafe-reports")));
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignmentAtRevision(revision, expected));
		String suffix = qualified ? "-module-a_" + provider + "_" + executionId : "";
		Path fragment = fixture.resolve("module-a/target/stp/coverage-fragment-v3" + suffix + ".json");
		Path evidence = fixture.resolve("module-a/target/stp/execution-evidence-v2" + suffix + ".json");
		Result collection = maven(fixture, "-Pfailsafe", PLUGIN + "prepare-reactor-executable-mapping",
				"verify", "-DsmartTestPicker.schemaVersion=3",
				"-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.revision=" + revision,
				"-DsmartTestPicker.completeInventoryFile=" + inventoryFile,
				"-DsmartTestPicker.executionType=" + executionType, "-DsmartTestPicker.executionId=" + executionId);
		assertEquals(0, collection.exitCode(), collection.output());
		String reportProvider = provider.equals("failsafe") ? "failsafe" : "surefire";
		assertTrue(Files.isRegularFile(fixture.resolve("module-a/target/" + reportProvider
				+ "-reports/TEST-a.ClassSourceOnlyTest.xml")));
		String otherProvider = reportProvider.equals("failsafe") ? "surefire" : "failsafe";
		assertFalse(Files.exists(fixture.resolve("module-a/target/" + otherProvider
				+ "-reports/TEST-a.ClassSourceOnlyTest.xml")));
		String collectionLog = collection.output();
		if (!qualified) {
			fragment = fixture.resolve("target/vintage-fragment.json");
			evidence = fixture.resolve("target/vintage-evidence.json");
			Result aggregate = maven(fixture, PLUGIN + "aggregate-reactor-coverage-fragment",
					"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment,
					"-DsmartTestPicker.revision=" + revision, "-DsmartTestPicker.fragmentOutput=" + fragment,
					"-DsmartTestPicker.evidenceOutput=" + evidence);
			assertEquals(0, aggregate.exitCode(), aggregate.output());
			collectionLog += aggregate.output();
		}
		var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		assertEquals(inventory.runnableTests(), decoded.tests().keySet());
		assertTrue(decoded.collectionCompleted());
		assertTrue(decoded.unmapped().isEmpty());
		var coverage = decoded.tests().values().iterator().next();
		assertEquals(com.sap.oss.smarttestpicker.coverage.model.TestOutcome.PASS, coverage.outcome());
		assertEquals(com.sap.oss.smarttestpicker.coverage.model.CollectionStatus.COLLECTED_WITH_COVERAGE,
				coverage.collectionStatus());
		assertEquals(Set.of("a.AValue"), coverage.coveredClasses());
		assertTrue(coverage.coveredMethods().stream().anyMatch(method ->
				method.binaryClassName().equals("a.AValue") && method.methodName().equals("value")));
		var actual = JsonParser.parseString(Files.readString(evidence)).getAsJsonObject();
		assertEquals(List.of(expected), actual.getAsJsonArray("EXECUTED").asList().stream()
				.map(value -> value.getAsString()).toList());
		assertEquals(0, actual.getAsJsonArray("NON_EXECUTED").size());
		// Retain the real outputs for inspection, separate from disposable Maven workspaces.
		Path saved = Files.createDirectories(Path.of("build/stp-inv-01", provider));
		Files.copy(inventoryFile, saved.resolve("inventory.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(fragment, saved.resolve("fragment.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(evidence, saved.resolve("evidence.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.writeString(saved.resolve("inventory.log"), discovery.output());
		Files.writeString(saved.resolve("collection.log"), collectionLog);
	}

	@Test
	void schemaV3InventoryKeepsSameLogicalTestUnderBothModuleTargets(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor"));
		String revision = initializeGit(fixture);
		Result result = maven(fixture, "process-test-classes", PLUGIN + "generate-reactor-head-test-inventory",
				"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.prHeadRevision=" + revision);
		assertEquals(0, result.exitCode(), result.output());
		var inventory = new ExecutableHeadTestInventoryCodec().read(fixture.resolve(".stp/head-test-inventory.json").toFile());
		assertEquals(revision, inventory.revision());
		assertEquals(10, inventory.runnableTests().size());
		assertTrue(inventory.runnableTests().stream().anyMatch(i -> i.toString().equals("maven:module-a::shared.SharedTest#same")));
		assertTrue(inventory.runnableTests().stream().anyMatch(i -> i.toString().equals("maven:module-b::shared.SharedTest#same")));
		assertTrue(inventory.runnableTests().stream().noneMatch(i -> i.target().targetId().equals("module-zero")));
	}

	@Test
	void schemaV3ExecutesParameterizedMethodsAndAccountsDisabledParameterizedContainers(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment(
				"maven:module-a::a.ATests#parameterized(java.lang.String)",
				"maven:module-a::a.ATests#disabledParameterized(java.lang.String)",
				"maven:module-a::a.ATests#disabledOrdinary"));
		Path fragment = fixture.resolve("target/final-fragment-v3.json"), evidence = fixture.resolve("target/final-evidence-v2.json");
		Result result = maven(fixture, PLUGIN + "prepare-reactor-executable-mapping", "verify",
				PLUGIN + "aggregate-reactor-coverage-fragment", "-DsmartTestPicker.schemaVersion=3",
				"-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.fragmentOutput=" + fragment,
				"-DsmartTestPicker.evidenceOutput=" + evidence);
		assertEquals(0, result.exitCode(), result.output());
		assertEquals(List.of("a.ATests#disabledOrdinary", "a.ATests#disabledParameterized", "a.ATests#parameterized"),
				Files.readAllLines(fixture.resolve("module-a/target/stp/selected-tests-surefire-v3.txt")));
		var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		assertEquals(Set.of("maven:module-a::a.ATests#parameterized(java.lang.String)"),
				decoded.tests().keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()));
		var json = JsonParser.parseString(Files.readString(evidence)).getAsJsonObject();
		assertEquals("test", json.get("testTarget").getAsString());
		assertEquals("maven", json.get("buildTool").getAsString());
		assertEquals(1, json.getAsJsonArray("EXECUTED").size());
		assertEquals(Set.of("maven:module-a::a.ATests#disabledOrdinary",
				"maven:module-a::a.ATests#disabledParameterized(java.lang.String)"),
				java.util.stream.StreamSupport.stream(json.getAsJsonArray("NON_EXECUTED").spliterator(), false)
						.map(value -> value.getAsString()).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void schemaV3MappingRoutesStrictSubsetAndAggregatesExecutableOwners(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-a::a.ATests#a1", "maven:module-b::b.BTests#b2",
				"maven:module-a::shared.SharedTest#same", "maven:module-b::shared.SharedTest#same"));
		Path fragment = fixture.resolve("target/final-fragment-v3.json"), evidence = fixture.resolve("target/final-evidence-v2.json");
		Result result = maven(fixture, PLUGIN + "prepare-reactor-executable-mapping", "verify",
				PLUGIN + "aggregate-reactor-coverage-fragment", "-DsmartTestPicker.schemaVersion=3",
				"-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.fragmentOutput=" + fragment,
				"-DsmartTestPicker.evidenceOutput=" + evidence);
		assertEquals(0, result.exitCode(), result.output());
		var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
		assertEquals(Set.of("maven:module-a::a.ATests#a1", "maven:module-b::b.BTests#b2",
				"maven:module-a::shared.SharedTest#same", "maven:module-b::shared.SharedTest#same"),
				decoded.tests().keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()));
		var moduleBCoverage = decoded.tests().entrySet().stream()
				.filter(entry -> entry.getKey().toString().equals("maven:module-b::b.BTests#b2"))
				.findFirst().orElseThrow().getValue();
		assertEquals(Set.of("a.AValue", "b.BValue"), moduleBCoverage.coveredClasses());
		assertTrue(moduleBCoverage.coveredMethods().stream()
				.anyMatch(method -> method.binaryClassName().equals("a.AValue") && method.methodName().equals("value")));
		assertTrue(moduleBCoverage.coveredMethods().stream()
				.anyMatch(method -> method.binaryClassName().equals("b.BValue") && method.methodName().equals("value")));
		var moduleACoverage = decoded.tests().entrySet().stream()
				.filter(entry -> entry.getKey().toString().equals("maven:module-a::a.ATests#a1"))
				.findFirst().orElseThrow().getValue();
		assertEquals(Set.of("a.AValue"), moduleACoverage.coveredClasses());
		assertFalse(result.output().contains("outside assignment executed"), result.output());
		assertTrue(Files.readString(fixture.resolve("module-a/target/stp/selected-tests-surefire-v3.txt")).contains("a.ATests#a1"));
		assertTrue(Files.readString(fixture.resolve("module-b/target/stp/selected-tests-surefire-v3.txt")).contains("b.BTests#b2"));
		var json = JsonParser.parseString(Files.readString(evidence)).getAsJsonObject();
		assertEquals(2, json.get("version").getAsInt()); assertEquals(4, json.getAsJsonArray("EXECUTED").size());
		Path moduleFragment = fixture.resolve("module-b/target/stp/coverage-fragment-v3.json");
		String originalFragment = Files.readString(moduleFragment);
		Files.writeString(moduleFragment, originalFragment.replace("maven:module-b::", "maven:module-a::"));
		Result wrongFragment = maven(fixture, PLUGIN + "aggregate-reactor-coverage-fragment",
				"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment,
				"-DsmartTestPicker.fragmentOutput=" + fragment, "-DsmartTestPicker.evidenceOutput=" + evidence);
		assertTrue(wrongFragment.exitCode() != 0); assertTrue(wrongFragment.output().contains("Execution target mismatch"), wrongFragment.output());
		Files.writeString(moduleFragment, originalFragment);
		Path moduleEvidence = fixture.resolve("module-b/target/stp/execution-evidence-v2.json");
		String originalEvidence = Files.readString(moduleEvidence);
		Files.writeString(moduleEvidence, originalEvidence.replace("maven:module-b", "maven:module-a"));
		Result wrongEvidence = maven(fixture, PLUGIN + "aggregate-reactor-coverage-fragment",
				"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment,
				"-DsmartTestPicker.fragmentOutput=" + fragment, "-DsmartTestPicker.evidenceOutput=" + evidence);
		assertTrue(wrongEvidence.exitCode() != 0); assertTrue(wrongEvidence.output().contains("Execution target mismatch for evidence"), wrongEvidence.output());
	}

	@Test
	void schemaV3FailedTestRetainsNoPartialJacocoCoverage(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		Files.writeString(fixture.resolve("module-a/src/main/java/a/ProductionB.java"),
				"package a; public final class ProductionB { public void unreached() {} }\n");
		Files.writeString(fixture.resolve("module-a/src/test/java/a/FailedCoverageTest.java"), """
			package a;
			import org.junit.jupiter.api.Test;
			class FailedCoverageTest {
			 @Test void partial() {
			   new AValue().value();
			   throw new AssertionError("stop before production B");
			 }
			}
			""");
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-a::a.FailedCoverageTest#partial"));
		Path fragmentPath = fixture.resolve("target/failed-fragment-v3.json");
		Result result = maven(fixture, PLUGIN + "prepare-reactor-executable-mapping", "verify",
				PLUGIN + "aggregate-reactor-coverage-fragment", "-Dmaven.test.failure.ignore=true",
				"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment,
				"-DsmartTestPicker.fragmentOutput=" + fragmentPath,
				"-DsmartTestPicker.evidenceOutput=" + fixture.resolve("target/failed-evidence.json"));
		assertEquals(0, result.exitCode(), result.output());
		var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragmentPath));
		assertTrue(fragment.collectionCompleted());
		assertTrue(fragment.tests().isEmpty());
		assertEquals("maven:module-a::a.FailedCoverageTest#partial", fragment.unmapped().get(0).test().toString());
		assertEquals(com.sap.oss.smarttestpicker.coverage.model.UnmappedReason.FAILED,
				fragment.unmapped().get(0).reason());
		assertFalse(Files.readString(fragmentPath).contains("ProductionB"));
		assertTrue(Files.readString(fixture.resolve("module-a/target/surefire-reports/TEST-a.FailedCoverageTest.xml"))
				.contains("failure"));
	}

	@Test
	void schemaV3KeepsSameContainerSetupOwnedByEachMavenTarget(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		for (String module : List.of("module-a", "module-b")) {
			String production = module.equals("module-a") ? "ProductionA" : "ProductionB";
			Files.createDirectories(fixture.resolve(module + "/src/main/java/shared"));
			Files.writeString(fixture.resolve(module + "/src/main/java/shared/" + production + ".java"),
					"package shared; public final class " + production + " { public static void setupOnly() {} }\n");
			Files.writeString(fixture.resolve(module + "/src/test/java/shared/SharedTest.java"), """
				package shared;
				import org.junit.jupiter.api.BeforeAll;
				import org.junit.jupiter.api.Test;
				class SharedTest {
				 @BeforeAll static void setup() { %s.setupOnly(); }
				 @Test void same() { }
				}
				""".formatted(production));
		}
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-a::shared.SharedTest#same",
				"maven:module-b::shared.SharedTest#same"));
		Path fragmentPath = fixture.resolve("target/setup-fragment-v3.json");
		Result result = maven(fixture, PLUGIN + "prepare-reactor-executable-mapping", "verify",
				PLUGIN + "aggregate-reactor-coverage-fragment", "-DsmartTestPicker.schemaVersion=3",
				"-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.fragmentOutput=" + fragmentPath,
				"-DsmartTestPicker.evidenceOutput=" + fixture.resolve("target/setup-evidence.json"));
		assertEquals(0, result.exitCode(), result.output());
		var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragmentPath));
		assertEquals(Set.of("maven:module-a", "maven:module-b"), fragment.setupScopes().stream()
				.map(scope -> scope.owner().toString()).collect(java.util.stream.Collectors.toSet()));
		assertTrue(fragment.setupScopes().stream().anyMatch(scope -> scope.owner().toString().equals("maven:module-a")
				&& scope.coveredClasses().contains("shared.ProductionA")));
		assertTrue(fragment.setupScopes().stream().anyMatch(scope -> scope.owner().toString().equals("maven:module-b")
				&& scope.coveredClasses().contains("shared.ProductionB")));
	}

	@Test
	void schemaV3PreventsTestsInAReactorModuleWithNoAssignment(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		Path pom = fixture.resolve("pom.xml");
		Files.writeString(pom, Files.readString(pom).replace("<includesFile>${surefire.includesFile}</includesFile>",
				"<includes><include>**/*Test.java</include></includes><includesFile>${surefire.includesFile}</includesFile>"));
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-a::a.ATests#a1"));
		Path fragment = fixture.resolve("target/final-fragment-v3.json"), evidence = fixture.resolve("target/final-evidence-v2.json");
		Result result = maven(fixture, PLUGIN + "prepare-reactor-executable-mapping", "verify",
				PLUGIN + "aggregate-reactor-coverage-fragment", "-DsmartTestPicker.schemaVersion=3",
				"-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.fragmentOutput=" + fragment,
				"-DsmartTestPicker.evidenceOutput=" + evidence);
		assertEquals(0, result.exitCode(), result.output());
		assertFalse(Files.exists(fixture.resolve("module-b/target/surefire-reports/TEST-b.BTests.xml")), result.output());
		assertEquals(List.of("**/__stp_no_assigned_tests__*.java"),
				Files.readAllLines(fixture.resolve("module-b/target/stp/selected-tests-surefire-v3.txt")));
		assertEquals(List.of("**/*"), Files.readAllLines(fixture.resolve("module-b/target/stp/suppress-all-surefire-v3.txt")));
	}

	@Test
	void schemaV3RoutesFailsafeWithoutEnablingSurefireAndKeepsQualifiedOutputs(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor"));
		String revision = initializeGit(fixture);
		Path inventory = fixture.resolve("complete-inventory.json");
		Result discovery = maven(fixture, "-Pfailsafe", "process-test-classes",
				PLUGIN + "generate-reactor-head-test-inventory", "-DsmartTestPicker.schemaVersion=3",
				"-DsmartTestPicker.prHeadRevision=" + revision, "-DsmartTestPicker.executionType=failsafe",
				"-DsmartTestPicker.executionId=fixture-it", "-DsmartTestPicker.outputFile=" + inventory);
		assertEquals(0, discovery.exitCode(), discovery.output());
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignmentAtRevision(revision,
				"maven:module-a@failsafe@fixture-it::a.AValueIT#integrationValue"));
		Result result = maven(fixture, "-Pfailsafe", PLUGIN + "prepare-reactor-executable-mapping",
				"verify", "-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment,
				"-DsmartTestPicker.revision=" + revision,
				"-DsmartTestPicker.completeInventoryFile=" + inventory, "-DsmartTestPicker.executionType=failsafe",
				"-DsmartTestPicker.executionId=fixture-it");
		assertEquals(0, result.exitCode(), result.output());
		Path directory = fixture.resolve("module-a/target/stp");
		assertTrue(Files.isRegularFile(directory.resolve(
				"selected-tests-failsafe-v3-module-a_failsafe_fixture-it.txt")));
		assertTrue(Files.isRegularFile(directory.resolve(
				"coverage-fragment-v3-module-a_failsafe_fixture-it.json")));
		assertEquals(List.of("**/__stp_no_assigned_tests__*.java"), Files.readAllLines(directory.resolve(
				"selected-tests-surefire-v3-module-a_failsafe_fixture-it.txt")));
		assertFalse(Files.exists(fixture.resolve("module-a/target/surefire-reports/TEST-a.AValueIT.xml")));
		assertTrue(Files.isRegularFile(fixture.resolve("module-a/target/failsafe-reports/TEST-a.AValueIT.xml")));
	}

	@Test
	void schemaV3UsesActiveAgentBytesWithProjectOwnedLiteralArgLine(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-a::a.ATests#a1"));
		Result result = maven(fixture, "-Pliteral-jacoco-argline", PLUGIN + "prepare-reactor-executable-mapping",
				"verify", PLUGIN + "aggregate-reactor-coverage-fragment", "-Dliteral.fixture.required=true",
				"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment,
				"-DsmartTestPicker.fragmentOutput=" + fixture.resolve("target/literal-fragment.json"),
				"-DsmartTestPicker.evidenceOutput=" + fixture.resolve("target/literal-evidence.json"));
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(Files.isRegularFile(fixture.resolve("module-a/target/jacoco.exec")));
		assertTrue(Files.list(fixture.resolve("module-a/target/jacoco"))
				.anyMatch(path -> path.getFileName().toString().matches("session_.*\\.exec")));
		var fragment = new ExecutableCoverageFragmentCodec().deserialize(
				Files.readAllBytes(fixture.resolve("target/literal-fragment.json")));
		assertTrue(fragment.collectionCompleted());
		assertEquals(Set.of("maven:module-a::a.ATests#a1"), fragment.tests().keySet().stream()
				.map(Object::toString).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void schemaV3FailsClosedWhenTestForkHasNoActiveJacocoRuntime(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")); initializeGit(fixture);
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:module-a::a.ATests#a1"));
		Result result = maven(fixture, "-Pno-jacoco-agent", PLUGIN + "prepare-reactor-executable-mapping", "test",
				"-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment);
		assertTrue(result.exitCode() != 0, result.output());
		assertTrue(result.output().contains("Active JaCoCo runtime is unavailable for STP mapping"), result.output());
	}

	@Test
	void schemaV3PreparationRejectsAbsentAndNonMavenTargets(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor"));
		Path assignment = fixture.resolve("assignment.json");
		Files.writeString(assignment, executableAssignment("maven:not-in-reactor::a.ATests#a1"));
		Result absent = maven(fixture, PLUGIN + "prepare-reactor-executable-mapping", "-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment);
		assertTrue(absent.exitCode() != 0); assertTrue(absent.output().contains("absent from reactor"), absent.output());
		Files.writeString(assignment, executableAssignment("gradle::module:test::a.ATests#a1"));
		Result wrong = maven(fixture, PLUGIN + "prepare-reactor-executable-mapping", "-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment);
		assertTrue(wrong.exitCode() != 0); assertTrue(wrong.output().contains("non-Maven execution target"), wrong.output());
	}

	private static String executableAssignment(String... tests) {
		return executableAssignmentAtRevision("reactor-revision", tests);
	}

	private static String executableAssignmentAtRevision(String revision, String... tests) {
		return "{\"version\":1,\"revision\":\"" + revision + "\",\"shardId\":\"reactor-shard\",\"tests\":["
				+ java.util.Arrays.stream(tests).map(s -> "\"" + s + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}";
	}

	@Test
	void realMavenReactorWritesOneRevisionBoundInventoryWithoutRunningTests(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v2-reactor", temp.resolve("reactor"));
		String revision = initializeGit(fixture);

		Result result = maven(fixture, "process-test-classes", PLUGIN + "generate-reactor-head-test-inventory",
				"-DsmartTestPicker.prHeadRevision=" + revision);
		assertEquals(0, result.exitCode(), result.output());
		Path output = fixture.resolve(".stp/head-test-inventory.json");
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
		Path output = Files.createDirectories(fixture.resolve(".stp")).resolve("head-test-inventory.json");
		Files.writeString(output, "stale");

		Result collision = maven(fixture, "process-test-classes", "-Pcollision",
				PLUGIN + "generate-reactor-head-test-inventory",
				"-DsmartTestPicker.prHeadRevision=" + revision);
		assertTrue(collision.exitCode() != 0, collision.output());
		assertTrue(collision.output().contains("Unsafe duplicate Maven test identities"), collision.output());
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
	void isolatesDifferentCompleteJUnitRuntimesPerModule(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("junit-version-reactor", temp.resolve("reactor"));
		String revision = initializeGit(fixture);
		Result result = maven(fixture, "process-test-classes", PLUGIN + "generate-reactor-head-test-inventory",
				"-DsmartTestPicker.prHeadRevision=" + revision, "-X");
		assertEquals(0, result.exitCode(), result.output());
		var inventory = new HeadTestInventoryCodec().read(fixture.resolve(".stp/head-test-inventory.json").toFile());
		assertEquals(revision, inventory.revision());
		assertEquals(Set.of(new TestIdentity("versions.a.OlderTests", "older"),
				new TestIdentity("versions.a.OlderTests$NestedTests", "nested"),
				new TestIdentity("versions.b.NewerTests", "parameterized", "java.lang.String")),
				Set.copyOf(inventory.runnableTests()));
		assertTrue(result.output().contains("junit-jupiter-api-5.9.3.jar"), result.output());
		assertTrue(result.output().contains("junit-jupiter-api-5.10.2.jar"), result.output());
		assertTrue(result.output().contains("Module fixture:module-zero:jar:1: 0 logical JUnit tests"), result.output());
	}

	@Test
	void reportsModuleAndJUnitOriginsForInternallyBrokenRuntime(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("junit-version-reactor", temp.resolve("reactor"));
		Result result = maven(fixture, "process-test-classes", "-Pbroken",
				PLUGIN + "generate-reactor-head-test-inventory");
		assertTrue(result.exitCode() != 0, result.output());
		assertTrue(result.output().contains("fixture:module-broken:jar:1"), result.output());
		assertTrue(result.output().contains("coherent JUnit boundary"), result.output());
		assertTrue(result.output().contains("junit-jupiter-api-5.9.3.jar"), result.output());
		assertTrue(result.output().contains("junit-jupiter-engine-5.10.2.jar"), result.output());
	}

	@Test
	void dependencyClasspathReuseHasOneOwnerAndConflictingOutputFailsClosed(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("duplicate-ownership-reactor", temp.resolve("reactor"));
		String revision = initializeGit(fixture);
		Result safe = maven(fixture, "package", PLUGIN + "generate-reactor-head-test-inventory",
				"-DskipTests", "-DsmartTestPicker.prHeadRevision=" + revision);
		assertEquals(0, safe.exitCode(), safe.output());
		var inventory = new HeadTestInventoryCodec().read(fixture.resolve(".stp/head-test-inventory.json").toFile());
		assertEquals(Set.of(new TestIdentity("shared.SharedTests", "shared"),
				new TestIdentity("consumer.ConsumerTests", "uniqueConsumer")), Set.copyOf(inventory.runnableTests()));
		assertTrue(safe.output().contains("Module fixture:module-zero:jar:1: 0 logical JUnit tests"), safe.output());

		Result conflict = maven(fixture, "process-test-classes", "-Pconflict", "-DskipTests",
				PLUGIN + "generate-reactor-head-test-inventory", "-DsmartTestPicker.prHeadRevision=" + revision);
		assertTrue(conflict.exitCode() != 0, conflict.output());
		assertTrue(conflict.output().contains("Unsafe duplicate Maven test identities"), conflict.output());
		assertTrue(conflict.output().contains("shared.SharedTests#shared"), conflict.output());
		assertTrue(conflict.output().contains("fixture:module-source:jar:1"), conflict.output());
		assertTrue(conflict.output().contains("fixture:module-conflict:jar:1"), conflict.output());
		assertTrue(conflict.output().contains("compiled definitions differ"), conflict.output());
		assertFalse(Files.exists(fixture.resolve(".stp/head-test-inventory.json")));
	}

	@Test
	void reactorWithNoCompiledTestOutputFailsWithoutPublishing(@TempDir Path temp) throws Exception {
		Path fixture = copyFixture("schema-v2-reactor", temp.resolve("reactor"));
		Path output = Files.createDirectories(fixture.resolve(".stp")).resolve("head-test-inventory.json");
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
				else {
					Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
				}
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
