// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalArtifactRequest;

/** Production scope from Maven's collected model, never the third-party dependency graph. */
record MavenAsmScope(String includes, String excludes, List<Path> sources) {

	static MavenAsmScope resolve(MavenSession session, List<MavenProject> active, String includes, String excludes) throws Exception {
		boolean derive = includes == null;
		String normalized = derive ? null : AsmMappingAttachment.prefixes(includes, true);
		String excluded = AsmMappingAttachment.prefixes(excludes, false);
		List<MavenProject> collected = session.getAllProjects() == null ? active : session.getAllProjects();
		Map<String, MavenProject> byGav = new HashMap<>();
		for (var module : collected) byGav.put(gav(module), module);
		Set<String> activeIds = new HashSet<>(); active.forEach(p -> activeIds.add(gav(p)));
		var pending = new ArrayDeque<>(active);
		Set<String> visited = new HashSet<>();
		TreeSet<Path> roots = new TreeSet<>(); TreeSet<String> prefixes = new TreeSet<>();
		while (!pending.isEmpty()) {
			var module = pending.removeFirst();
			if (!visited.add(gav(module))) continue;
			for (var dependency : module.getDependencies()) {
				var reactorDependency = byGav.get(dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + dependency.getVersion());
				if (reactorDependency != null) pending.add(reactorDependency);
			}
			if ("pom".equals(module.getPackaging())) continue;
			if (activeIds.contains(gav(module))) {
				Path output = Path.of(module.getBuild().getOutputDirectory()).toAbsolutePath().normalize();
				if (module.getBuild().getTestOutputDirectory() != null && output.equals(Path.of(module.getBuild().getTestOutputDirectory()).toAbsolutePath().normalize()))
					throw new IllegalArgumentException("ASM scope cannot separate identical production/test output directories: " + output);
				roots.add(output);
				if (derive) {
					if (!Files.isDirectory(output) && !hasSources(module)) continue; // Resource-only modules have no class universe.
					if (!Files.isDirectory(output)) throw new IllegalArgumentException(
							"Cannot derive smartTestPicker.asm.includes: compile reactor production classes first; missing " + output);
					try (var files = Files.walk(output)) {
						var classes = files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).toList();
						if (classes.isEmpty() && hasSources(module)) throw new IllegalArgumentException(
								"Cannot derive smartTestPicker.asm.includes: no compiled production classes in " + output);
						for (Path file : classes) addPackage(output.relativize(file).toString().replace('\\', '/'), prefixes);
					}
				}
				// Maven may supply a packaged active project instead of its classes directory.
				if (module.getBuild().getFinalName() != null && "jar".equals(module.getPackaging()))
					roots.add(Path.of(module.getBuild().getDirectory(), module.getBuild().getFinalName() + ".jar").toAbsolutePath().normalize());
			} else {
				if (!"jar".equals(module.getPackaging())) throw new IllegalArgumentException(
						"ASM scope requires an installed main JAR for omitted reactor module " + gav(module));
				var artifact = new DefaultArtifact(module.getGroupId(), module.getArtifactId(), "", "jar", module.getVersion());
				var repository = session.getRepositorySession();
				var found = repository.getLocalRepositoryManager().find(repository,
						new LocalArtifactRequest(artifact, module.getRemoteProjectRepositories(), "stp-asm-scope"));
				if (found.getFile() == null || !found.getFile().isFile()) throw new IllegalArgumentException(
						"ASM scope requires installed reactor main artifact " + artifact + "; install the exact head before mapping");
				Path jar = found.getFile().toPath().toAbsolutePath().normalize(); roots.add(jar);
				if (derive) try (var archive = new JarFile(jar.toFile())) {
					for (var entry : Collections.list(archive.entries())) addPackage(entry.getName(), prefixes);
				}
			}
		}
		if (derive) {
			if (prefixes.isEmpty()) throw new IllegalArgumentException("Cannot derive smartTestPicker.asm.includes: no compiled reactor production packages");
			normalized = AsmMappingAttachment.prefixes(String.join(",", prefixes), true);
		}
		if (roots.isEmpty()) throw new IllegalArgumentException("ASM mapping has no reactor production code sources");
		return new MavenAsmScope(normalized, excluded, List.copyOf(roots));
	}

	private static String gav(MavenProject p) { return p.getGroupId() + ":" + p.getArtifactId() + ":" + p.getVersion(); }
	private static boolean hasSources(MavenProject project) throws Exception {
		for (String root : project.getCompileSourceRoots()) {
			if (!Files.isDirectory(Path.of(root))) continue;
			try (var files = Files.walk(Path.of(root))) { if (files.anyMatch(Files::isRegularFile)) return true; }
		}
		return false;
	}
	private static void addPackage(String name, Set<String> prefixes) {
		if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class")) return;
		int slash = name.lastIndexOf('/');
		if (slash < 0) throw new IllegalArgumentException("Cannot derive ASM package scope for default-package production class " + name);
		prefixes.add(name.substring(0, slash).replace('/', '.') + ".");
	}
}
