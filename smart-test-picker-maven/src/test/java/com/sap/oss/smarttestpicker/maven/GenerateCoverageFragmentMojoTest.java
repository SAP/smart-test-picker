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
