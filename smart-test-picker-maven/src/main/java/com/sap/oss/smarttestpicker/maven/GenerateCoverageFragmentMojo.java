// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.CollectionStatus;
import com.sap.oss.smarttestpicker.coverage.model.CoverageFragment;
import com.sap.oss.smarttestpicker.coverage.model.CoverageMapRevision;
import com.sap.oss.smarttestpicker.coverage.model.ShardId;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.validation.CoverageMapValidator;
import com.sap.oss.smarttestpicker.mapper.CoverageMapperJaxb;

/** Produces an authoritative schema-v2 fragment from Maven's per-test JaCoCo artifacts. */
@Mojo(name = "generate-coverage-fragment", defaultPhase = LifecyclePhase.VERIFY)
public class GenerateCoverageFragmentMojo extends AbstractMojo
{
	@Parameter(defaultValue = "${project.build.directory}/jacoco", required = true)
	private File execDir;

	@Parameter(defaultValue = "${project.build.directory}/jacoco-xml", required = true)
	private File reportsDir;

	@Parameter(property = "smartTestPicker.revision", required = true)
	private String revision;

	@Parameter(property = "smartTestPicker.shardId", required = true)
	private String shardId;

	@Parameter(defaultValue = "${project.build.directory}/coverage-fragment-v2.json",
			property = "smartTestPicker.fragmentOutput", required = true)
	private File fragmentOutput;

	@Parameter(defaultValue = "${project.build.directory}/execution-evidence-v1.json",
			property = "smartTestPicker.evidenceOutput", required = true)
	private File evidenceOutput;

	@Parameter(defaultValue = "test", property = "smartTestPicker.testTarget")
	private String testTarget;

	@Override
	public void execute() throws MojoExecutionException
	{
		if (revision == null || revision.isBlank()) throw new MojoExecutionException("smartTestPicker.revision must not be blank");
		if (shardId == null || shardId.isBlank()) throw new MojoExecutionException("smartTestPicker.shardId must not be blank");
		if (evidenceOutput == null) evidenceOutput = new File(fragmentOutput.getAbsoluteFile().getParentFile(), "execution-evidence-v1.json");
		if (testTarget == null || testTarget.isBlank()) testTarget = "test";
		try { Files.deleteIfExists(fragmentOutput.toPath()); }
		catch (IOException failure) { throw new MojoExecutionException("Failed to invalidate previous coverage fragment", failure); }
		try { Files.deleteIfExists(evidenceOutput.toPath()); }
		catch (IOException failure) { throw new MojoExecutionException("Failed to invalidate previous execution evidence", failure); }

		CoverageFragment fragment = collect();
		var validation = CoverageMapValidator.validate(fragment);
		if (!validation.isValid()) throw new MojoExecutionException("Invalid coverage fragment: " + validation.errors().get(0));
		CoverageFragmentCodec codec = new CoverageFragmentCodec();
		byte[] bytes = codec.serialize(fragment);
		codec.deserialize(bytes);
		writeAtomically(bytes);
		writeExecutionEvidence();
		getLog().info("[SmartTestPicker] Generated schema-v2 coverage fragment: " + fragmentOutput
				+ " (" + fragment.tests().size() + " mapped, " + fragment.unmapped().size()
				+ " unmapped, completed=" + fragment.collectionCompleted() + ")");
	}

	private void writeExecutionEvidence() throws MojoExecutionException
	{
		try
		{
			Set<TestIdentity> executed = new TreeSet<>(), nonExecuted = new TreeSet<>();
			File[] identities = execDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(".identity"));
			File[] skipped = execDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(".non-executed"));
			if (identities != null) for (File file : identities) executed.add(readTestIdentity(file));
			if (skipped != null) for (File file : skipped) nonExecuted.add(readTestIdentity(file));
			nonExecuted.removeAll(executed);
			JsonObject root = new JsonObject(); root.addProperty("version", 1); root.addProperty("revision", revision);
			root.addProperty("shardId", shardId); root.addProperty("testTarget", testTarget); root.addProperty("buildTool", "maven");
			JsonArray x = new JsonArray(); executed.forEach(id -> x.add(id.toString())); root.add("EXECUTED", x);
			JsonArray n = new JsonArray(); nonExecuted.forEach(id -> n.add(id.toString())); root.add("NON_EXECUTED", n);
			File parent = evidenceOutput.getAbsoluteFile().getParentFile(); if (parent != null) Files.createDirectories(parent.toPath());
			Files.writeString(evidenceOutput.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n");
		}
		catch (Exception failure) { throw new MojoExecutionException("Failed to write exact execution evidence", failure); }
	}

	private static TestIdentity readTestIdentity(File file) throws IOException
	{
		Map<String,String> values = readIdentity(file);
		return new TestIdentity(required(values,"className"),required(values,"methodName"),values.getOrDefault("methodParameterTypes",""));
	}

	private CoverageFragment collect() throws MojoExecutionException
	{
		Map<TestIdentity, TestCoverage> tests = new HashMap<>();
		Map<TestIdentity, UnmappedTest> unmapped = new HashMap<>();
		Set<TestIdentity> seen = new HashSet<>();
		boolean completed = execDir.isDirectory() && reportsDir.isDirectory();
		File[] identities = execDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(".identity"));
		File[] execFiles = execDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(".exec"));
		if (identities == null) identities = new File[0];
		if (execFiles == null) execFiles = new File[0];
		Set<String> identityBases = new HashSet<>();
		CoverageMapperJaxb mapper = new CoverageMapperJaxb(reportsDir);

		for (File identityFile : identities)
		{
			String base = stripSuffix(identityFile.getName(), ".identity");
			identityBases.add(base);
			TestIdentity identity;
			TestOutcome outcome;
			try
			{
				Map<String, String> values = readIdentity(identityFile);
				identity = new TestIdentity(required(values, "className"), required(values, "methodName"),
						values.getOrDefault("methodParameterTypes", ""));
				outcome = TestOutcome.valueOf(required(values, "outcome"));
			}
			catch (Exception malformed)
			{
				completed = false;
				getLog().warn("[SmartTestPicker] Invalid identity artifact " + identityFile + ": " + malformed.getMessage());
				continue;
			}

			if (!seen.add(identity))
			{
				completed = false;
				tests.remove(identity);
				unmapped.put(identity, new UnmappedTest(identity, UnmappedReason.COLLECTION_FAILED));
				continue;
			}

			File exec = new File(execDir, base + ".exec");
			File status = new File(reportsDir, base + ".status");
			if (!exec.isFile() || !status.isFile())
			{
				completed = false;
				unmapped.put(identity, new UnmappedTest(identity, UnmappedReason.COLLECTION_FAILED));
				continue;
			}

			try
			{
				String reportStatus = Files.readString(status.toPath(), StandardCharsets.UTF_8).trim();
				if ("EMPTY".equals(reportStatus))
				{
					tests.put(identity, new TestCoverage(Set.of(), Set.of(), outcome, CollectionStatus.COLLECTED_EMPTY));
				}
				else if ("COVERED".equals(reportStatus))
				{
					var coverage = mapper.readSchemaV2Coverage(new File(reportsDir, base + ".xml"));
					CollectionStatus collectionStatus = coverage.coveredClasses().isEmpty()
							? CollectionStatus.COLLECTED_EMPTY : CollectionStatus.COLLECTED_WITH_COVERAGE;
					tests.put(identity, new TestCoverage(coverage.coveredClasses(), coverage.coveredMethods(), outcome, collectionStatus));
				}
				else
				{
					completed = false;
					unmapped.put(identity, new UnmappedTest(identity, UnmappedReason.COLLECTION_FAILED));
				}
			}
			catch (Exception unsafe)
			{
				completed = false;
				unmapped.put(identity, new UnmappedTest(identity, UnmappedReason.COLLECTION_FAILED));
				getLog().warn("[SmartTestPicker] Unsafe coverage artifact for " + identity + ": " + unsafe.getMessage());
			}
		}

		for (File exec : execFiles)
			if (!identityBases.contains(stripSuffix(exec.getName(), ".exec"))) completed = false;
		File[] nonExecuted = execDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(".non-executed"));
		if (identities.length == 0 && execFiles.length == 0 && (nonExecuted == null || nonExecuted.length == 0)) completed = false;

		return new CoverageFragment(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision(revision),
				new ShardId(shardId), tests, new ArrayList<>(unmapped.values()), List.of(), completed);
	}

	private static Map<String, String> readIdentity(File file) throws IOException
	{
		Map<String, String> values = new HashMap<>();
		for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
		{
			int separator = line.indexOf('=');
			if (separator <= 0 || values.put(line.substring(0, separator), line.substring(separator + 1)) != null)
				throw new IllegalArgumentException("malformed or duplicate field");
		}
		if (!"1".equals(values.get("format"))) throw new IllegalArgumentException("unsupported identity format");
		return values;
	}

	private static String required(Map<String, String> values, String name)
	{
		String value = values.get(name);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + name);
		return value;
	}

	private void writeAtomically(byte[] bytes) throws MojoExecutionException
	{
		try
		{
			File parent = fragmentOutput.getAbsoluteFile().getParentFile();
			if (parent != null) Files.createDirectories(parent.toPath());
			File temporary = new File(parent, fragmentOutput.getName() + ".tmp");
			Files.deleteIfExists(temporary.toPath());
			Files.write(temporary.toPath(), bytes);
			try { Files.move(temporary.toPath(), fragmentOutput.toPath(), StandardCopyOption.ATOMIC_MOVE); }
			catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), fragmentOutput.toPath()); }
		}
		catch (IOException failure)
		{
			throw new MojoExecutionException("Failed to write schema-v2 coverage fragment", failure);
		}
	}

	private static String stripSuffix(String value, String suffix)
	{
		return value.substring(0, value.length() - suffix.length());
	}
}
