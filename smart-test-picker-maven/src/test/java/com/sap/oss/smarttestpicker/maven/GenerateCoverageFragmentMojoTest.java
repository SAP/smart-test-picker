// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.MethodIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableShardAssignment;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;

class GenerateCoverageFragmentMojoTest
{
	@Test
	void writesDeterministicDescriptorAwareFragmentWithoutInventedSetup(@TempDir Path temp) throws Exception
	{
		Path exec = Files.createDirectories(temp.resolve("jacoco"));
		Path reports = Files.createDirectories(temp.resolve("reports"));
		Path output = temp.resolve("fragment.json");
		writeIdentity(exec.resolve("session_covered.identity"), "example.FixtureTests", "overloaded",
				"org.junit.jupiter.api.TestInfo", "PASS");
		Files.write(exec.resolve("session_covered.exec"), new byte[] { 1 });
		Files.writeString(reports.resolve("session_covered.status"), "COVERED\n");
		Path sourceReport = Path.of(getClass().getClassLoader().getResource("schema-v2-covered.xml").toURI());
		Files.copy(sourceReport, reports.resolve("session_covered.xml"));
		writeIdentity(exec.resolve("session_empty.identity"), "example.ZeroCoverageTests", "zeroCoverage", "", "PASS");
		Files.write(exec.resolve("session_empty.exec"), new byte[] { 1 });
		Files.writeString(reports.resolve("session_empty.status"), "EMPTY\n");

		GenerateCoverageFragmentMojo mojo = mojo(exec, reports, output);
		mojo.execute();
		byte[] first = Files.readAllBytes(output);
		mojo.execute();
		assertArrayEquals(first, Files.readAllBytes(output));

		var fragment = new CoverageFragmentCodec().deserialize(first);
		assertTrue(fragment.collectionCompleted());
		assertEquals("rev-1", fragment.revision().value());
		assertEquals("shard-1", fragment.shardId().value());
		assertTrue(fragment.setupScopes().isEmpty());
		assertEquals(CollectionStatus.COLLECTED_EMPTY,
				fragment.tests().get(new TestIdentity("example.ZeroCoverageTests", "zeroCoverage")).collectionStatus());
		assertTrue(fragment.tests().get(new TestIdentity("example.FixtureTests", "overloaded",
				"org.junit.jupiter.api.TestInfo")).coveredMethods().contains(new MethodIdentity(
				"org.example.service.UserService", "updateAddress", "(Ljava/lang/String;)V")));
	}

	@Test
	void exactSidecarsAggregateMixedAndAllSkippedLogicalIdentities(@TempDir Path temp) throws Exception
	{
		Path exec = Files.createDirectories(temp.resolve("jacoco"));
		Path reports = Files.createDirectories(temp.resolve("reports"));
		Path output = temp.resolve("fragment.json");
		writeIdentity(exec.resolve("session_mixed.identity"), "example.Parameters", "mixed", "java.lang.String", "PASS");
		writeIdentity(exec.resolve("session_mixed.non-executed"), "example.Parameters", "mixed", "java.lang.String", "SKIPPED");
		writeIdentity(exec.resolve("session_all.non-executed"), "example.Parameters", "allSkipped", "int", "SKIPPED");
		Files.write(exec.resolve("session_mixed.exec"), new byte[] {1});
		Files.writeString(reports.resolve("session_mixed.status"), "EMPTY\n");
		mojo(exec,reports,output).execute();
		var evidence=JsonParser.parseString(Files.readString(temp.resolve("execution-evidence-v1.json"))).getAsJsonObject();
		assertEquals("example.Parameters#mixed(java.lang.String)",evidence.getAsJsonArray("EXECUTED").get(0).getAsString());
		assertEquals("example.Parameters#allSkipped(int)",evidence.getAsJsonArray("NON_EXECUTED").get(0).getAsString());
	}

	@Test
	void schemaV3ReconcilesAssignedSkippedSurefireTestsWithExactDescriptor(@TempDir Path temp) throws Exception
	{
		Path exec = Files.createDirectories(temp.resolve("jacoco"));
		Path reports = Files.createDirectories(temp.resolve("reports"));
		Path surefire = Files.createDirectories(temp.resolve("surefire-reports"));
		Files.writeString(surefire.resolve("TEST-example.DisabledTests.xml"), """
				<testsuite><testcase classname="example.DisabledTests" name="disabled(java.lang.String)[1]">
				<skipped message="disabled"/></testcase></testsuite>
				""");
		var target = new ExecutionTarget(BuildTool.MAVEN, "module-a");
		var identity = new TestIdentity("example.DisabledTests", "disabled", "java.lang.String");
		Path assignment = temp.resolve("assignment.json");
		Files.write(assignment, new ExecutableShardAssignmentCodec().serialize(new ExecutableShardAssignment(
				ExecutableShardAssignment.CURRENT_VERSION, new CoverageMapRevision("rev-1"), new ShardId("shard-1"),
				Set.of(new ExecutableTestIdentity(target, identity)))));
		GenerateCoverageFragmentMojo mojo = mojo(exec, reports, temp.resolve("fragment-v3.json"));
		set(mojo, "schemaVersion", 3);
		set(mojo, "executionTarget", target.toString());
		set(mojo, "assignmentFile", assignment.toFile());
		set(mojo, "surefireReportsDir", surefire.toFile());
		mojo.execute();
		var evidence = JsonParser.parseString(Files.readString(temp.resolve("execution-evidence-v1.json"))).getAsJsonObject();
		assertEquals("maven:module-a::example.DisabledTests#disabled(java.lang.String)",
				evidence.getAsJsonArray("NON_EXECUTED").get(0).getAsString());
	}

	@Test
	void missingLocalReportStatusProducesIncompleteUnmappedFragment(@TempDir Path temp) throws Exception
	{
		Path exec = Files.createDirectories(temp.resolve("jacoco"));
		Path reports = Files.createDirectories(temp.resolve("reports"));
		Path output = temp.resolve("fragment.json");
		writeIdentity(exec.resolve("session_missing.identity"), "example.FixtureTests", "ordinary", "", "PASS");
		Files.write(exec.resolve("session_missing.exec"), new byte[] { 1 });

		mojo(exec, reports, output).execute();
		var fragment = new CoverageFragmentCodec().deserialize(Files.readAllBytes(output));
		assertFalse(fragment.collectionCompleted());
		assertEquals(1, fragment.unmapped().size());
		assertTrue(fragment.tests().isEmpty());
	}

	@Test
	void jacocoFactsProduceExecutableMappedAndUnmappedSchemaV3Fragment(@TempDir Path temp) throws Exception
	{
		Path exec = Files.createDirectories(temp.resolve("jacoco"));
		Path reports = Files.createDirectories(temp.resolve("reports"));
		Path output = temp.resolve("fragment-v3.json");
		writeIdentity(exec.resolve("session_covered.identity"),
				"org.sonar.java.checks.helpers.ReassignmentFinderTest", "parameter_with_usage", "", "PASS");
		Files.write(exec.resolve("session_covered.exec"), new byte[] { 1 });
		Files.writeString(reports.resolve("session_covered.status"), "EMPTY\n");
		writeIdentity(exec.resolve("session_failed.identity"), "example.FailedTest", "fails", "", "FAIL");
		Files.write(exec.resolve("session_failed.exec"), new byte[] { 1 });

		GenerateCoverageFragmentMojo mojo = mojo(exec, reports, output);
		set(mojo, "schemaVersion", 3);
		set(mojo, "executionTarget", "maven:java-checks");
		mojo.execute();
		var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(output));
		assertEquals(3, fragment.schemaVersion());
		assertTrue(fragment.tests().keySet().stream().anyMatch(value -> value.toString().equals(
				"maven:java-checks::org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage")));
		assertEquals("maven:java-checks", fragment.unmapped().get(0).test().target().toString());
		assertEquals(UnmappedReason.FAILED, fragment.unmapped().get(0).reason());
		assertFalse(fragment.tests().keySet().stream().anyMatch(value -> value.test().className().equals("example.FailedTest")));
	}

	@Test
	void failedParameterizedInvocationDominatesSuccessfulSiblingRegardlessOfOrder(@TempDir Path temp) throws Exception
	{
		Path exec = Files.createDirectories(temp.resolve("jacoco"));
		Path reports = Files.createDirectories(temp.resolve("reports"));
		writeIdentity(exec.resolve("session_failed.identity"), "example.Parameters", "mixed", "java.lang.String", "FAIL");
		Files.write(exec.resolve("session_failed.exec"), new byte[] { 1 });
		Files.writeString(reports.resolve("session_failed.status"), "EMPTY\n");
		writeIdentity(exec.resolve("session_passed.identity"), "example.Parameters", "mixed", "java.lang.String", "PASS");
		Files.write(exec.resolve("session_passed.exec"), new byte[] { 1 });
		Files.writeString(reports.resolve("session_passed.status"), "EMPTY\n");

		Path output = temp.resolve("fragment.json");
		mojo(exec, reports, output).execute();
		var fragment = new CoverageFragmentCodec().deserialize(Files.readAllBytes(output));
		assertTrue(fragment.collectionCompleted());
		assertTrue(fragment.tests().isEmpty());
		assertEquals(UnmappedReason.FAILED, fragment.unmapped().get(0).reason());
	}

	@Test
	void schemaV3FailsClosedForMissingAndMalformedTarget(@TempDir Path temp) throws Exception
	{
		GenerateCoverageFragmentMojo missing = mojo(temp, temp, temp.resolve("missing.json"));
		set(missing, "schemaVersion", 3);
		assertTrue(assertThrows(org.apache.maven.plugin.MojoExecutionException.class, missing::execute)
				.getMessage().contains("requires an execution target"));
		GenerateCoverageFragmentMojo malformed = mojo(temp, temp, temp.resolve("malformed.json"));
		set(malformed, "schemaVersion", 3);
		set(malformed, "executionTarget", "gradle:spring-core:test");
		assertTrue(assertThrows(org.apache.maven.plugin.MojoExecutionException.class, malformed::execute)
				.getMessage().contains("Malformed execution target"));
	}

	private static GenerateCoverageFragmentMojo mojo(Path exec, Path reports, Path output) throws Exception
	{
		GenerateCoverageFragmentMojo mojo = new GenerateCoverageFragmentMojo();
		set(mojo, "execDir", exec.toFile());
		set(mojo, "reportsDir", reports.toFile());
		set(mojo, "revision", "rev-1");
		set(mojo, "shardId", "shard-1");
		set(mojo, "fragmentOutput", output.toFile());
		return mojo;
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static void writeIdentity(Path file, String className, String methodName,
			String parameters, String outcome) throws Exception
	{
		Files.writeString(file, "format=1\nclassName=" + className + "\nmethodName=" + methodName
				+ "\nmethodParameterTypes=" + parameters + "\noutcome=" + outcome + "\n");
	}
}
