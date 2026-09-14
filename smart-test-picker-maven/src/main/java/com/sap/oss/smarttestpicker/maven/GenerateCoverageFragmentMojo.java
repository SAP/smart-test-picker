// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
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
import com.sap.oss.smarttestpicker.coverage.model.SetupScope;
import com.sap.oss.smarttestpicker.coverage.model.SetupScopeType;
import com.sap.oss.smarttestpicker.coverage.model.TestContainer;
import com.sap.oss.smarttestpicker.coverage.model.TestCoverage;
import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.TestOutcome;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedReason;
import com.sap.oss.smarttestpicker.coverage.model.UnmappedTest;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget;
import com.sap.oss.smarttestpicker.coverage.model.BuildTool;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentQualifier;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
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

	@Parameter(defaultValue = "${project.build.directory}/surefire-reports")
	private File surefireReportsDir;

	@Parameter(defaultValue = "${project.build.directory}/failsafe-reports")
	private File failsafeReportsDir;

	@Parameter(defaultValue = "${env.STP_MAPPING_TESTS_FILE}", property = "smartTestPicker.testsFile")
	private File assignmentFile;

	@Parameter(property = "smartTestPicker.revision", required = true)
	private String revision;

	@Parameter(property = "smartTestPicker.shardId", required = true)
	private String shardId;

	@Parameter(defaultValue = "2", property = "smartTestPicker.schemaVersion", required = true)
	private int schemaVersion = CoverageMapContract.SCHEMA_VERSION;

	@Parameter(property = "smartTestPicker.executionTarget")
	private String executionTarget;

	@Parameter(defaultValue = "${project.build.directory}/coverage-fragment-v2.json",
			property = "smartTestPicker.fragmentOutput", required = true)
	private File fragmentOutput;

	@Parameter(defaultValue = "${project.build.directory}/execution-evidence-v1.json",
			property = "smartTestPicker.evidenceOutput", required = true)
	private File evidenceOutput;

	@Parameter(property = "smartTestPicker.moduleFragmentOutput")
	private File moduleFragmentOutput;

	@Parameter(property = "smartTestPicker.moduleEvidenceOutput")
	private File moduleEvidenceOutput;

	@Parameter(defaultValue = "test", property = "smartTestPicker.testTarget")
	private String testTarget;

	@Override
	public void execute() throws MojoExecutionException
	{
		if (revision == null || revision.isBlank()) throw new MojoExecutionException("smartTestPicker.revision must not be blank");
		if (shardId == null || shardId.isBlank()) throw new MojoExecutionException("smartTestPicker.shardId must not be blank");
		ExecutionTarget target = validateRuntimeContext();
		selectOutputs();
		if (evidenceOutput == null) evidenceOutput = new File(fragmentOutput.getAbsoluteFile().getParentFile(), "execution-evidence-v1.json");
		if (testTarget == null || testTarget.isBlank()) testTarget = "test";
		try { Files.deleteIfExists(fragmentOutput.toPath()); }
		catch (IOException failure) { throw new MojoExecutionException("Failed to invalidate previous coverage fragment", failure); }
		try { Files.deleteIfExists(evidenceOutput.toPath()); }
		catch (IOException failure) { throw new MojoExecutionException("Failed to invalidate previous execution evidence", failure); }
		if (schemaVersion == CoverageMapContract.SCHEMA_V3) reconcileSkippedReports(target);

		CoverageFragment fragment = collect();
		var validation = CoverageMapValidator.validate(fragment);
		if (!validation.isValid()) throw new MojoExecutionException("Invalid coverage fragment: " + validation.errors().get(0));
		byte[] bytes;
		if (schemaVersion == CoverageMapContract.SCHEMA_VERSION) {
			CoverageFragmentCodec codec = new CoverageFragmentCodec();
			bytes = codec.serialize(fragment);
			codec.deserialize(bytes);
		} else {
			var qualifier = new ExecutableCoverageFragmentQualifier();
			var executable = qualifier.qualify(fragment, target);
			qualifier.requireTarget(executable, target);
			ExecutableCoverageFragmentCodec codec = new ExecutableCoverageFragmentCodec();
			bytes = codec.serialize(executable);
			codec.deserialize(bytes);
		}
		writeAtomically(bytes);
		writeExecutionEvidence(target);
		getLog().info("[SmartTestPicker] Generated schema-v" + schemaVersion + " coverage fragment: " + fragmentOutput
				+ " (" + fragment.tests().size() + " mapped, " + fragment.unmapped().size()
				+ " unmapped, completed=" + fragment.collectionCompleted() + ")");
	}

	private void reconcileSkippedReports(ExecutionTarget target) throws MojoExecutionException
	{
		if (assignmentFile == null || !assignmentFile.isFile()) return;
		try
		{
			var assignment = new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(assignmentFile.toPath()));
			Set<TestIdentity> assigned = new TreeSet<>();
			assignment.tests().stream().filter(test -> test.target().equals(target))
					.forEach(test -> assigned.add(test.test()));
			Set<String> skipped = new HashSet<>();
			readSkippedReports(surefireReportsDir, skipped);
			readSkippedReports(failsafeReportsDir, skipped);
			for (TestIdentity identity : assigned)
			{
				String key = identity.className() + "#" + identity.methodName();
				if (!skipped.contains(key)) continue;
				String fileName = "session_report-skipped-" + Integer.toHexString(identity.toString().hashCode()) + ".non-executed";
				Path file = execDir.toPath().resolve(fileName);
				Files.createDirectories(file.getParent());
				Files.writeString(file, "format=1\nclassName=" + identity.className() + "\nmethodName="
						+ identity.methodName() + "\nmethodParameterTypes=" + identity.methodParameterTypes() + "\n");
			}
		}
		catch (Exception failure)
		{
			throw new MojoExecutionException("Failed to reconcile authoritative Maven skipped-test reports", failure);
		}
	}

	private static void readSkippedReports(File directory, Set<String> skipped) throws Exception
	{
		if (directory == null || !directory.isDirectory()) return;
		File[] reports = directory.listFiles((dir, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
		if (reports == null) return;
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		for (File report : reports)
		{
			var cases = factory.newDocumentBuilder().parse(report).getElementsByTagName("testcase");
			for (int index = 0; index < cases.getLength(); index++)
			{
				var testCase = (org.w3c.dom.Element) cases.item(index);
				if (testCase.getElementsByTagName("skipped").getLength() == 0) continue;
				String method = testCase.getAttribute("name").replaceFirst("[\\[(].*$", "");
				skipped.add(testCase.getAttribute("classname") + "#" + method);
			}
		}
	}

	private ExecutionTarget validateRuntimeContext() throws MojoExecutionException {
		if (schemaVersion != CoverageMapContract.SCHEMA_VERSION && schemaVersion != CoverageMapContract.SCHEMA_V3)
			throw new MojoExecutionException("Unsupported runtime schema version: " + schemaVersion);
		if (schemaVersion == CoverageMapContract.SCHEMA_VERSION) {
			if (executionTarget != null)
				throw new MojoExecutionException("Schema-v2 mapping does not accept an execution target");
			return null;
		}
		if (executionTarget == null || executionTarget.isBlank())
			throw new MojoExecutionException("Schema-v3 mapping requires an execution target");
		try {
			ExecutionTarget parsed = ExecutionTarget.parse(executionTarget);
			if (parsed.buildTool() != BuildTool.MAVEN)
				throw new IllegalArgumentException("Maven mapping requires a Maven execution target");
			return parsed;
		}
		catch (IllegalArgumentException failure) {
			throw new MojoExecutionException("Malformed execution target: " + executionTarget, failure);
		}
	}

	private void selectOutputs() throws MojoExecutionException
	{
		boolean moduleFragmentConfigured = moduleFragmentOutput != null;
		boolean moduleEvidenceConfigured = moduleEvidenceOutput != null;
		if (moduleFragmentConfigured != moduleEvidenceConfigured)
			throw new MojoExecutionException("smartTestPicker.moduleFragmentOutput and smartTestPicker.moduleEvidenceOutput must be supplied together");
		if (moduleFragmentConfigured)
		{
			fragmentOutput = moduleFragmentOutput;
			evidenceOutput = moduleEvidenceOutput;
		}
	}

	private void writeExecutionEvidence(ExecutionTarget target) throws MojoExecutionException
	{
		try
		{
			Set<TestIdentity> executed = new TreeSet<>(), nonExecuted = new TreeSet<>();
			File[] identities = execDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(".identity"));
			File[] skipped = execDir.listFiles((dir, name) -> name.startsWith("session_") && name.endsWith(".non-executed"));
			if (identities != null) for (File file : identities) executed.add(readTestIdentity(file));
			if (skipped != null) for (File file : skipped) nonExecuted.add(readTestIdentity(file));
			nonExecuted.removeAll(executed);
			JsonObject root = new JsonObject(); root.addProperty("version", schemaVersion == 3 ? 2 : 1); root.addProperty("revision", revision);
			root.addProperty("shardId", shardId);
			if (schemaVersion == 2) { root.addProperty("testTarget", testTarget); root.addProperty("buildTool", "maven"); }
			else root.addProperty("executionTarget", target.toString());
			JsonArray x = new JsonArray(); executed.forEach(id -> x.add(schemaVersion == 3
					? new ExecutableTestIdentity(target, id).toString() : id.toString())); root.add("EXECUTED", x);
			JsonArray n = new JsonArray(); nonExecuted.forEach(id -> n.add(schemaVersion == 3
					? new ExecutableTestIdentity(target, id).toString() : id.toString())); root.add("NON_EXECUTED", n);
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
		File[] execFiles = execDir.listFiles((dir, name) -> name.startsWith("session_")
				&& !name.startsWith("session_setup_") && name.endsWith(".exec"));
		if (identities == null) identities = new File[0];
		if (execFiles == null) execFiles = new File[0];
		Set<String> identityBases = new HashSet<>();
		CoverageMapperJaxb mapper = new CoverageMapperJaxb(reportsDir);
		List<SetupScope> setupScopes = new ArrayList<>();

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

			if (outcome == TestOutcome.FAIL)
			{
				seen.add(identity);
				tests.remove(identity);
				unmapped.put(identity, new UnmappedTest(identity, UnmappedReason.FAILED));
				continue;
			}

			if (!seen.add(identity))
			{
				if (unmapped.containsKey(identity) && unmapped.get(identity).reason() == UnmappedReason.FAILED)
					continue;
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
		File[] setup = execDir.listFiles((dir, name) -> name.startsWith("session_setup_") && name.endsWith(".setup"));
		if (setup != null) for (File setupFile : setup)
		{
			String base = stripSuffix(setupFile.getName(), ".setup");
			try
			{
				String container = required(readIdentity(setupFile), "container");
				File status = new File(reportsDir, base + ".status");
				if (!status.isFile()) throw new IllegalArgumentException("missing setup report status");
				String state = Files.readString(status.toPath(), StandardCharsets.UTF_8).trim();
				Set<String> classes = Set.of();
				if ("COVERED".equals(state)) classes = mapper.readSchemaV2Coverage(
						new File(reportsDir, base + ".xml")).coveredClasses();
				else if (!"EMPTY".equals(state)) throw new IllegalArgumentException("unsafe setup report status " + state);
				if (!classes.isEmpty()) setupScopes.add(new SetupScope("junit-container:" + container,
						SetupScopeType.FRAMEWORK_SETUP, classes, Set.of(new TestContainer(container))));
			}
			catch (Exception unsafe)
			{
				completed = false;
				getLog().warn("[SmartTestPicker] Unsafe setup coverage artifact " + setupFile + ": " + unsafe.getMessage());
			}
		}

		return new CoverageFragment(CoverageMapContract.SCHEMA_VERSION, new CoverageMapRevision(revision),
				new ShardId(shardId), tests, new ArrayList<>(unmapped.values()), setupScopes, completed);
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
			throw new MojoExecutionException("Failed to write schema-v" + schemaVersion + " coverage fragment", failure);
		}
	}

	private static String stripSuffix(String value, String suffix)
	{
		return value.substring(0, value.length() - suffix.length());
	}
}
