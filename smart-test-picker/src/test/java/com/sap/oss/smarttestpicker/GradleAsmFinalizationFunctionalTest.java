// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableCoverageFragment;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Acceptance tests mutate artifacts in fixture doLast actions, after the JVM, before STP finalization. */
class GradleAsmFinalizationFunctionalTest {

	@TempDir Path temporary;

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void validMappingsAreAccepted(boolean twoTasks) throws Exception {
		Path project = fixture(twoTasks);
		var result = run(project, false, "none");
		assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome());
		if (twoTasks) assertEquals(TaskOutcome.SUCCESS, result.task(":otherTest").getOutcome());
		var fragment = aggregate(project);
		assertTrue(fragment.collectionCompleted());
		assertEquals(2, fragment.tests().size());
		assertTrue(fragment.unmapped().isEmpty());
		assertFalse(fragment.setupScopes().isEmpty());
		assertTrue(fragment.tests().values().stream().allMatch(c -> c.coveredClasses().equals(Set.of("example.Service"))));
	}

	static Stream<Arguments> rejectedArtifacts() {
		return Stream.of(
				Arguments.of("missing-diagnostic", "diagnostic"),
				Arguments.of("malformed-diagnostic", "diagnostic"),
				Arguments.of("diagnostic-schema", "schema"),
				Arguments.of("diagnostic-revision", "revision"),
				Arguments.of("diagnostic-shard", "shard"),
				Arguments.of("diagnostic-path", "fragment"),
				Arguments.of("diagnostic-missing-revision", "revision"),
				Arguments.of("diagnostic-missing-metrics", "metrics"),
				Arguments.of("zero-transformed", "classesTransformed"),
				Arguments.of("negative-transformed", "classesTransformed"),
				Arguments.of("wrong-target", "target"),
				Arguments.of("other-task-identity", "gradle::test::example.MappingTest#first"),
				Arguments.of("unexpected-identity", "example.MappingTest#unexpected"),
				Arguments.of("TIMEOUT", "TIMEOUT"),
				Arguments.of("ASSIGNED_NOT_REPORTED", "ASSIGNED_NOT_REPORTED"),
				Arguments.of("fragment-revision", "revision"),
				Arguments.of("fragment-shard", "shard"),
				Arguments.of("incomplete", "incomplete"),
				Arguments.of("missing-fragment", "fragment"),
				Arguments.of("malformed-fragment", "fragment"),
				Arguments.of("duplicate-identity", "Duplicate"),
				Arguments.of("duplicate-setup", "Duplicate setup scope"),
				Arguments.of("setup-wrong-owner", "owner"),
				Arguments.of("setup-missing-owner", "owner"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedArtifacts")
	void rejectsFaultInOneTaskAndRemovesPreviouslyAcceptedAggregate(String fault, String rule) throws Exception {
		Path project = fixture(true);
		run(project, false, "none");
		assertEquals(2, aggregate(project).tests().size());
		var result = run(project, true, fault);
		String output = result.getOutput();
		assertAll(
				() -> assertTrue(output.contains("INJECTED " + fault + " :otherTest"), output),
				() -> assertEquals(TaskOutcome.FAILED, result.task(":otherTest").getOutcome()),
				() -> assertTrue(output.contains("Execution failed for task ':otherTest'"), output),
				() -> assertTrue(output.contains(rule), output),
				() -> assertFalse(Files.exists(aggregatePath(project)), "A rejected run must invalidate the previous aggregate"));
		if (fault.equals("zero-transformed") || fault.equals("negative-transformed")) {
			assertAll(() -> assertTrue(output.contains("includes="), output),
					() -> assertTrue(output.contains("excludes="), output),
					() -> assertTrue(output.contains("productionSources="), output),
					() -> assertTrue(output.contains("example."), output));
		}
		if (fault.equals("TIMEOUT") || fault.equals("ASSIGNED_NOT_REPORTED")) {
			assertTrue(output.contains("gradle::otherTest::example.MappingTest#second"), output);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"SKIPPED", "FAILED", "COLLECTION_FAILED"})
	void allowedUnmappedReasonsRemainAccepted(String reason) throws Exception {
		Path project = fixture(false);
		String method = reason.equals("SKIPPED") ? "disabled" : reason.equals("FAILED") ? "failure" : "second";
		if (!reason.equals("COLLECTION_FAILED")) assignment(project, false, method);
		var result = run(project, false, reason);
		assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome());
		var fragment = aggregate(project);
		assertTrue(fragment.collectionCompleted());
		assertEquals(1, fragment.unmapped().size());
		assertEquals(method, fragment.unmapped().get(0).test().test().methodName());
		assertEquals(reason, fragment.unmapped().get(0).reason().name());
		assertTrue(fragment.tests().keySet().stream().anyMatch(i -> i.test().methodName().equals("first")));
	}

	@Test void unassignedTaskLeftoversCannotInfluenceTheAggregate() throws Exception {
		Path project = fixture(true);
		run(project, false, "none");
		Path leftover = project.resolve("build/stp/coverage/_otherTest/finalization-shard/fragment.json");
		Files.writeString(leftover, "not even a fragment");
		assignment(project, false, "second");
		run(project, false, "none");
		assertEquals("not even a fragment", Files.readString(leftover));
		assertEquals(Set.of("gradle::test"), aggregate(project).tests().keySet().stream()
				.map(i -> i.target().toString()).collect(java.util.stream.Collectors.toSet()));
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void missingIdentityIsAbsorbedWithoutChangingSiblingCoverage(boolean twoTasks) throws Exception {
		Path project = fixture(twoTasks);
		run(project, false, "none");
		var original = aggregate(project);
		Path healthy = project.resolve("build/stp/coverage/_test/finalization-shard/fragment.json");
		byte[] healthyBytes = Files.readAllBytes(healthy);
		var result = run(project, false, "missing-identity");
		var actual = aggregate(project);
		String task = twoTasks ? ":otherTest" : ":test";
		var missing = com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity.parse(
				"gradle:" + task + "::example.MappingTest#second");
		assertTrue(actual.collectionCompleted());
		assertEquals(List.of(new com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest(missing,
				com.sap.oss.smarttestpicker.coverage.model.UnmappedReason.COLLECTION_FAILED)), actual.unmapped());
		var expected = new java.util.TreeMap<>(original.tests()); expected.remove(missing);
		assertEquals(expected, actual.tests());
		assertEquals(original.setupScopes(), actual.setupScopes());
		assertAudit(project, task, List.of(missing.toString()));
		for (String text : List.of(task, "1", missing.toString(), "ASSIGNED_TEST_NOT_REPORTED"))
			assertTrue(result.getOutput().contains(text), result.getOutput());
		if (twoTasks) assertArrayEquals(healthyBytes, Files.readAllBytes(healthy));
		assertMandatorySelection(actual, missing);
	}

	@ParameterizedTest(name = "missing identity plus {0}") @MethodSource("rejectedArtifacts")
	void absorptionCannotRepairRejectedArtifacts(String fault, String rule) throws Exception {
		Path project = fixture(true);
		run(project, false, "none");
		addThirdAssignment(project);
		var result = run(project, true, fault, "-PremoveThird=true");
		assertTrue(result.getOutput().contains(":otherTest"), result.getOutput());
		assertTrue(result.getOutput().contains(rule), result.getOutput());
		assertFalse(Files.exists(aggregatePath(project)));
	}

	@ParameterizedTest @ValueSource(strings = {"valid", "reason", "count", "identity"})
	void existingAbsorptionAuditIsValidatedAndMerged(String kind) throws Exception {
		Path project = fixture(true);
		addThirdAssignment(project);
		boolean fail = !kind.equals("valid");
		var result = run(project, fail, "audit-" + kind, "-PremoveThird=true");
		if (fail) {
			assertTrue(result.getOutput().contains(":otherTest"), result.getOutput());
			assertTrue(result.getOutput().toLowerCase().contains("absorption"), result.getOutput());
			assertFalse(Files.exists(aggregatePath(project)));
		} else {
			assertEquals(2, aggregate(project).unmapped().size());
			assertAudit(project, ":otherTest", List.of("gradle::otherTest::example.MappingTest#second",
					"gradle::otherTest::example.MappingTest#third"));
		}
	}

	private void addThirdAssignment(Path project) throws Exception {
		Path file = project.resolve("assignment.json");
		var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		json.getAsJsonArray("tests").add("gradle::otherTest::example.MappingTest#third");
		Files.writeString(file, json.toString());
	}

	@Test void reportsNaturalContainerFailuresWithoutLosingHealthyCoverage() throws Exception {
		Path project = fixture(false);
		for (String kind : List.of("Setup", "Initialization")) {
			String setup = kind.equals("Setup")
					? "@org.junit.jupiter.api.BeforeAll static void setup() { throw new IllegalStateException(\"setup failure\"); }"
					: "static { if (Boolean.parseBoolean(\"true\")) throw new IllegalStateException(\"initialization failure\"); }";
			write(project, "src/test/java/example/" + kind + "FailureTest.java", "package example; public class "
					+ kind + "FailureTest { " + setup + " @org.junit.jupiter.api.Test void observed() { Service.value(); } }\n");
		}
		Path file = project.resolve("assignment.json");
		var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		for (String kind : List.of("Setup", "Initialization")) json.getAsJsonArray("tests").add(
				"gradle::test::example." + kind + "FailureTest#observed");
		Files.writeString(file, json.toString());
		var result = run(project, false, "none");
		var fragment = aggregate(project);
		assertTrue(fragment.collectionCompleted());
		assertEquals(2, fragment.tests().size());
		assertEquals(2, fragment.unmapped().size());
		Path retained = Path.of("build/absorption-natural"); Files.createDirectories(retained);
		Files.copy(project.resolve("build/stp/coverage/_test/finalization-shard/fragment.json"),
				retained.resolve("fragment.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.writeString(retained.resolve("build.log"), result.getOutput());
	}

	private void assertAudit(Path project, String task, List<String> expected) throws Exception {
		Path file = project.resolve("build/stp/coverage/" + task.replace(':', '_') + "/finalization-shard/fragment.json");
		var audit = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject()
				.getAsJsonObject("assignmentAbsorption");
		assertNotNull(audit);
		assertEquals("ASSIGNED_TEST_NOT_REPORTED", audit.get("reason").getAsString());
		assertFalse(audit.get("executionObserved").getAsBoolean());
		assertEquals(expected.size(), audit.get("count").getAsInt());
		assertEquals(expected, java.util.stream.StreamSupport.stream(audit.getAsJsonArray("tests").spliterator(), false)
				.map(com.google.gson.JsonElement::getAsString).toList());
	}

	private void assertMandatorySelection(ExecutableCoverageFragment fragment,
			com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity missing) throws Exception {
		var assigned = new java.util.TreeSet<>(fragment.tests().keySet()); assigned.add(missing);
		var inventory = new com.sap.oss.smarttestpicker.coverage.model.ExecutableTestInventory(fragment.revision(), assigned);
		var map = new com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentJoiner().join(inventory,
				new com.sap.oss.smarttestpicker.coverage.model.ExecutableShardPlan(fragment.revision(),
						java.util.Map.of(fragment.shardId(), assigned)), List.of(fragment), Set.of(), java.time.Instant.EPOCH,
				new com.sap.oss.smarttestpicker.coverage.model.GeneratorProvenance("test", "ASM", "none", "17"));
		Path mapFile = temporary.resolve("selection-map.json.gz");
		new com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageMapCodec().write(map, mapFile);
		var logical = com.sap.oss.smarttestpicker.selector.ExplicitPrSelectorFlow.selectionMap(mapFile);
		var head = assigned.stream().map(com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity::test)
				.collect(java.util.stream.Collectors.toSet());
		var selected = new com.sap.oss.smarttestpicker.selector.SchemaV2TestSelector().select(
				new com.sap.oss.smarttestpicker.selector.SelectionContext(logical, fragment.revision(), fragment.revision().value(),
						Set.of("example.Unrelated"), Set.of(), head, Set.of(), Set.of(), Set.of()));
		assertEquals(List.of(missing.test().toString()), selected.getSelectedTests());
	}

	private Path fixture(boolean twoTasks) throws Exception {
		Path project = temporary.resolve("fixture");
		write(project, "settings.gradle", "rootProject.name='finalization'\n");
		write(project, "build.gradle", """
			plugins { id 'java'; id 'com.sap.oss.smart-test-picker' }
			repositories { mavenCentral() }
			dependencies {
			 testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
			 testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
			 stpAgent files('%s')
			}
			tasks.register('otherTest', Test) {
			 testClassesDirs = sourceSets.test.output.classesDirs
			 classpath = sourceSets.test.runtimeClasspath
			 mustRunAfter(test)
			}
			smartTestPicker {
			 runtimeSchemaVersion = 3; revision = 'finalization-revision'; shardId = 'finalization-shard'
			 executableAssignmentFile = 'assignment.json'
			 mappingTestTasks = %s
			}
			def fault = providers.gradleProperty('fault').getOrElse('none')
			def faultTask = '%s'
			def removeThird = providers.gradleProperty('removeThird').getOrElse('false') == 'true'
			tasks.withType(Test).configureEach {
			 useJUnitPlatform()
			 ignoreFailures = true
			 def taskName = name
			 def taskPath = path
			 def outputDir = layout.buildDirectory.dir('stp/coverage/_' + name + '/finalization-shard').get().asFile
			 doLast {
			  if (taskName != faultTask || fault in ['none', 'SKIPPED', 'FAILED']) return
			  def diagnosticFile = new File(outputDir, 'agent-diagnostic.json')
			  def fragmentFile = new File(outputDir, 'fragment.json')
			  def diagnostic = new groovy.json.JsonSlurper().parse(diagnosticFile)
			  def fragment = new groovy.json.JsonSlurper().parse(fragmentFile)
			  def identity = 'gradle:' + taskPath + '::example.MappingTest#second'
			  switch (fault) {
			   case 'diagnostic-schema': diagnostic.schemaVersion = 'unknown'; break
			   case 'diagnostic-revision': diagnostic.configuration.revision = 'other-revision'; break
			   case 'diagnostic-shard': diagnostic.configuration.shardId = 'other-shard'; break
			   case 'diagnostic-path': diagnostic.configuration.fragmentOutput = new File(outputDir, 'elsewhere.json').path; break
			   case 'diagnostic-missing-revision': diagnostic.configuration.remove('revision'); break
			   case 'diagnostic-missing-metrics': diagnostic.remove('metrics'); break
			   case 'zero-transformed': diagnostic.metrics.classesTransformed = 0; break
			   case 'negative-transformed': diagnostic.metrics.classesTransformed = -1; break
			   case 'wrong-target':
			    fragment.tests = fragment.tests.collectEntries { k, v -> [(k.replace('gradle::otherTest::', 'gradle::foreignTest::')):v] }; break
			   case 'other-task-identity': fragment.tests['gradle::test::example.MappingTest#first'] = fragment.tests.values().first(); break
			   case 'unexpected-identity': fragment.tests['gradle:' + taskPath + '::example.MappingTest#unexpected'] = fragment.tests.values().first(); break
			   case 'TIMEOUT': case 'ASSIGNED_NOT_REPORTED': case 'COLLECTION_FAILED':
			    fragment.tests.remove(identity); fragment.unmapped.add([test:identity, reason:fault]); break
			   case 'missing-identity': fragment.tests.remove(identity); break
			   case 'fragment-revision': fragment.revision = 'other-revision'; break
			   case 'fragment-shard': fragment.shardId = 'other-shard'; break
			   case 'incomplete': fragment.collection.completed = false; break
			   case 'duplicate-setup': fragment.setupScopes.add(fragment.setupScopes.first()); break
			   case 'setup-wrong-owner': case 'setup-missing-owner':
			    def scope = [id:'observed-setup', type:'CONTAINER', coveredClasses:['example.Service'], affectedContainers:['example.MappingTest']]
			    if (fault == 'setup-wrong-owner') scope.owner = 'gradle::foreignTest'
			    fragment.setupScopes.add(scope); break
			  }
			  if (fault.startsWith('audit-')) {
			   fragment.tests.remove(identity)
			   fragment.unmapped.add([test:identity, reason:'COLLECTION_FAILED'])
			   fragment.assignmentAbsorption = [reason:'ASSIGNED_TEST_NOT_REPORTED', executionObserved:false, count:1, tests:[identity]]
			   if (fault == 'audit-reason') fragment.assignmentAbsorption.reason = 'OTHER'
			   if (fault == 'audit-count') fragment.assignmentAbsorption.count = 2
			   if (fault == 'audit-identity') fragment.assignmentAbsorption.tests = [identity.replace('#second', '#third')]
			  }
			  if (removeThird) fragment.tests.remove(identity.replace('#second', '#third'))
			  diagnosticFile.text = groovy.json.JsonOutput.toJson(diagnostic)
			  fragmentFile.text = groovy.json.JsonOutput.toJson(fragment)
			  if (fault == 'missing-diagnostic') assert diagnosticFile.delete()
			  if (fault == 'malformed-diagnostic') diagnosticFile.text = '{'
			  if (fault == 'missing-fragment') assert fragmentFile.delete()
			  if (fault == 'malformed-fragment') fragmentFile.text = '{'
			  if (fault == 'duplicate-identity') {
			   def key = groovy.json.JsonOutput.toJson(identity)
			   def value = groovy.json.JsonOutput.toJson(fragment.tests[identity])
			   fragmentFile.text = fragmentFile.text.replace('"tests":{', '"tests":{' + key + ':' + value + ',')
			  }
			  println 'INJECTED ' + fault + ' ' + taskPath
			 }
			}
			""".formatted(System.getProperty("stp.test.agent.jar").replace('\\', '/'),
				twoTasks ? "[':test', ':otherTest']" : "[':test']", twoTasks ? "otherTest" : "test"));
		write(project, "src/main/java/example/Service.java",
				"package example; public class Service { public static int value() { return 1; } }\n");
		write(project, "src/test/java/example/MappingTest.java", """
			package example;
			import org.junit.jupiter.api.Test;
			import org.junit.jupiter.api.Disabled;
			import org.junit.jupiter.api.BeforeAll;
			import static org.junit.jupiter.api.Assertions.*;
			class MappingTest {
			 @BeforeAll static void setup() { assertEquals(1, Service.value()); }
			 @Test void first() { assertEquals(1, Service.value()); }
			 @Test void second() { assertEquals(1, Service.value()); }
			 @Test void third() { assertEquals(1, Service.value()); }
			 @Test @Disabled void disabled() { fail("must never execute"); }
			 @Test void failure() { assertEquals(2, Service.value()); }
			}
			""");
		assignment(project, twoTasks, "second");
		return project;
	}

	private void assignment(Path project, boolean twoTasks, String secondMethod) throws Exception {
		write(project, "assignment.json", """
			{"version":1,"revision":"finalization-revision","shardId":"finalization-shard","tests":[
			"gradle::test::example.MappingTest#first","gradle::%s::example.MappingTest#%s"]}
			""".formatted(twoTasks ? "otherTest" : "test", secondMethod));
	}

	private BuildResult run(Path project, boolean fail, String fault, String... extra) {
		var args = new ArrayList<>(List.of("generateSmartTestMapping", "--stacktrace", "--max-workers=2", "-Pfault=" + fault));
		args.addAll(List.of(extra));
		var runner = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(args);
		return fail ? runner.buildAndFail() : runner.build();
	}
	private Path aggregatePath(Path project) { return project.resolve("build/stp/executable-fragment.json"); }
	private ExecutableCoverageFragment aggregate(Path project) throws Exception {
		return new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(aggregatePath(project)));
	}
	private void write(Path root, String name, String text) throws Exception {
		Path path = root.resolve(name);
		Files.createDirectories(path.getParent());
		Files.writeString(path, text);
	}
}
