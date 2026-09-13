// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.logging.Log;

import com.sap.oss.smarttestpicker.coverage.model.TestIdentity;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventory;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.HeadTestInventory;
import com.sap.oss.smarttestpicker.selector.HeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.selector.JUnitHeadTestInventoryGenerator;
import com.sap.oss.smarttestpicker.selector.WorkspaceRevisionVerifier;

/** Shared Maven adapter around the build-tool-neutral JUnit discovery producer. */
final class MavenHeadTestInventory {
	private MavenHeadTestInventory() {}

	static boolean generate(List<MavenProject> projects, File output, Log log) {
		return generate(projects, output, null, null, log);
	}

	static boolean generate(List<MavenProject> projects, File output, File projectDir, String revision, Log log) {
		try {
			if (revision != null) revision = WorkspaceRevisionVerifier.requireHead(projectDir, revision);
			Map<TestIdentity, List<MavenTestIdentityOccurrence>> occurrences = new LinkedHashMap<>();
			boolean discoveredTarget = false;
			for (MavenProject project : projects) {
				if ("pom".equals(project.getPackaging())) continue;
				if (declaresTestsSkipped(project)) {
					log.debug("[SmartTestPicker] Skipping inventory for Maven module with tests disabled: " + project.getId());
					continue;
				}
				File root = new File(project.getBuild().getTestOutputDirectory());
				if (!root.isDirectory()) continue;
				discoveredTarget = true;
				HeadTestInventory inventory;
				try {
					inventory = new JUnitHeadTestInventoryGenerator().generate(null,
							project.getTestClasspathElements().stream().map(File::new).map(File::toPath).toList(),
							List.of(root.toPath()), origin -> log.debug("[SmartTestPicker] " + project.getId() + " " + origin));
				} catch (Exception failure) {
					throw new IllegalStateException("JUnit inventory discovery failed for Maven module "
							+ project.getId() + "; the module runtime could not establish a coherent JUnit boundary", failure);
				}
				log.info("[SmartTestPicker] Module " + project.getId() + ": "
						+ inventory.runnableTests().size() + " logical JUnit tests");
				for (TestIdentity identity : inventory.runnableTests()) occurrences
						.computeIfAbsent(identity, ignored -> new ArrayList<>())
						.add(occurrence(identity, project, root.toPath()));
			}
			if (!discoveredTarget) throw new IllegalStateException("No compiled Maven test output is available");
			rejectUnsafeDuplicates(occurrences);
			List<TestIdentity> merged = new ArrayList<>(occurrences.keySet());
			HeadTestInventory inventory = revision == null ? HeadTestInventory.from(merged)
					: HeadTestInventory.atRevision(revision, merged);
			new HeadTestInventoryCodec().write(output, inventory);
			log.info("[SmartTestPicker] Discovered " + inventory.runnableTests().size() + " logical JUnit tests");
			return true;
		} catch (Exception failure) {
			try { Files.deleteIfExists(output.toPath()); } catch (Exception ignored) { }
			log.error("[SmartTestPicker] Head inventory discovery failed; selection will fail open", failure);
			return false;
		}
	}

	static boolean generateExecutable(List<MavenProject> projects, File output, File reactorRoot,
			String revision, Log log) {
		return generateExecutable(projects, output, reactorRoot, revision, null, null, null, log);
	}

	static boolean generateExecutable(List<MavenProject> projects, File output, File reactorRoot,
			String revision, String executionType, String executionId, String profile, Log log) {
		try {
			revision = WorkspaceRevisionVerifier.requireHead(reactorRoot, revision);
			var resolver = new MavenExecutionTargetResolver();
			List<ExecutableTestIdentity> discovered = new ArrayList<>();
			boolean discoveredTarget = false;
			for (MavenProject project : projects) {
				if ("pom".equals(project.getPackaging()) || declaresTestsSkipped(project)) continue;
				File root = new File(project.getBuild().getTestOutputDirectory());
				if (!root.isDirectory()) continue;
				discoveredTarget = true;
				var target = resolver.resolve(reactorRoot, project, executionType, executionId, profile);
				HeadTestInventory inventory = new JUnitHeadTestInventoryGenerator().generate(null,
						project.getTestClasspathElements().stream().map(File::new).map(File::toPath).toList(),
						List.of(root.toPath()), origin -> log.debug("[SmartTestPicker] " + project.getId() + " " + origin));
				if ("surefire".equals(executionType)) inventory = HeadTestInventory.from(inventory.runnableTests().stream()
						.filter(MavenSurefireTestFilter.from(project)).toList());
				log.info("[SmartTestPicker] Module " + target + ": " + inventory.runnableTests().size()
						+ " logical JUnit tests");
				for (TestIdentity identity : inventory.runnableTests())
					discovered.add(new ExecutableTestIdentity(target, identity));
			}
			if (!discoveredTarget) throw new IllegalStateException("No compiled Maven test output is available");
			if (new java.util.HashSet<>(discovered).size() != discovered.size())
				throw new IllegalStateException("Duplicate executable Maven test identity produced at inventory boundary");
			ExecutableHeadTestInventory inventory = ExecutableHeadTestInventory.atRevision(revision, discovered);
			new ExecutableHeadTestInventoryCodec().write(output, inventory);
			log.info("[SmartTestPicker] Discovered " + inventory.runnableTests().size() + " executable JUnit tests");
			return true;
		} catch (Exception failure) {
			try { Files.deleteIfExists(output.toPath()); } catch (Exception ignored) { }
			log.error("[SmartTestPicker] Executable head inventory discovery failed", failure);
			return false;
		}
	}

	private static MavenTestIdentityOccurrence occurrence(TestIdentity identity, MavenProject project, Path root)
			throws Exception {
		String relativeClass = identity.className().replace('.', File.separatorChar) + ".class";
		Path classFile = root.resolve(relativeClass);
		Path source = source(project, identity.className());
		return new MavenTestIdentityOccurrence(identity, project.getId(), classFile,
				source, hash(classFile), source == null ? null : hash(source));
	}

	private static Path source(MavenProject project, String className) {
		int nested = className.indexOf('$');
		String topLevel = nested < 0 ? className : className.substring(0, nested);
		String relative = topLevel.replace('.', File.separatorChar) + ".java";
		for (String root : project.getTestCompileSourceRoots()) {
			Path candidate = Path.of(root).resolve(relative);
			if (Files.isRegularFile(candidate)) return candidate;
		}
		return null;
	}

	private static String hash(Path path) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
		return java.util.HexFormat.of().formatHex(digest);
	}

	private static void rejectUnsafeDuplicates(Map<TestIdentity, List<MavenTestIdentityOccurrence>> occurrences) {
		List<Map.Entry<TestIdentity, List<MavenTestIdentityOccurrence>>> duplicates = occurrences.entrySet().stream()
				.filter(entry -> entry.getValue().size() > 1).sorted(Map.Entry.comparingByKey()).toList();
		if (duplicates.isEmpty()) return;
		StringBuilder message = new StringBuilder("Unsafe duplicate Maven test identities:");
		for (var duplicate : duplicates) {
			boolean definitionsDiffer = duplicate.getValue().stream().map(MavenTestIdentityOccurrence::classHash)
					.distinct().count() > 1;
			message.append("\nidentity: ").append(duplicate.getKey())
					.append("\nreason: ").append(definitionsDiffer
							? "compiled definitions differ and execution ownership is ambiguous"
							: "multiple module test outputs can execute the same identity");
			for (MavenTestIdentityOccurrence occurrence : duplicate.getValue()) message
					.append("\nmodule: ").append(occurrence.projectId())
					.append("\ntestOutputPath: ").append(occurrence.classFile())
					.append("\nsourcePath: ").append(occurrence.sourceFile() == null ? "unresolved" : occurrence.sourceFile())
					.append("\ncompiledClassSha256: ").append(occurrence.classHash())
					.append("\nsourceSha256: ").append(occurrence.sourceHash() == null ? "unavailable" : occurrence.sourceHash());
		}
		throw new IllegalStateException(message.toString());
	}

	private record MavenTestIdentityOccurrence(TestIdentity identity, String projectId, Path classFile,
			Path sourceFile, String classHash, String sourceHash) { }

	private static boolean declaresTestsSkipped(MavenProject project) {
		var properties = project.getProperties();
		return Boolean.parseBoolean(properties.getProperty("skipTests"))
				|| Boolean.parseBoolean(properties.getProperty("maven.test.skip"));
	}
}
