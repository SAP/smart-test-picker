// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarFile;

/** Resolves only build-owned production sources already present on the Test runtime classpath. */
record GradleAsmScope(List<String> includes, List<Path> sources) {

	static GradleAsmScope resolve(String task, Collection<File> runtime, Collection<File> production,
			Collection<File> testOutputs, List<String> configured) throws IOException {
		var candidates = paths(production);
		var overlap = new TreeSet<>(candidates);
		overlap.retainAll(paths(testOutputs));
		if (!overlap.isEmpty()) throw new IllegalArgumentException(
				"ASM scope for " + task + " cannot separate identical production/test output directories: " + overlap);
		candidates.retainAll(paths(runtime));
		candidates.removeIf(path -> !Files.isDirectory(path) && !Files.isRegularFile(path));
		if (candidates.isEmpty()) throw new IllegalArgumentException(
				"ASM mapping task " + task + " has no production code sources on its runtime classpath");
		List<String> includes = prefixes(configured);
		if (includes.isEmpty()) {
			var packages = new TreeSet<String>();
			for (Path source : candidates) {
				if (Files.isDirectory(source)) {
					try (var files = Files.walk(source)) {
						for (Path file : files.filter(Files::isRegularFile).toList()) {
							addPackage(source.relativize(file).toString().replace('\\', '/'), packages, task);
						}
					}
				} else {
					try (var archive = new JarFile(source.toFile())) {
						var entries = archive.entries();
						while (entries.hasMoreElements()) addPackage(entries.nextElement().getName(), packages, task);
					}
				}
			}
			if (packages.isEmpty()) throw new IllegalArgumentException(
					"Cannot derive ASM coverageIncludes for " + task + ": no compiled production packages");
			includes = prefixes(packages);
		}
		return new GradleAsmScope(includes, List.copyOf(candidates));
	}

	private static TreeSet<Path> paths(Collection<File> files) throws IOException {
		var paths = new TreeSet<Path>();
		for (File file : files) {
			Path path = file.toPath();
			paths.add(Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize());
		}
		return paths;
	}

	static List<String> prefixes(Collection<String> configured) {
		var result = new TreeSet<String>();
		for (String raw : configured) {
			String prefix = raw.trim().replace('/', '.');
			if (!prefix.endsWith(".")) prefix += ".";
			if (!prefix.matches("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+")) {
				throw new IllegalArgumentException("ASM scope contains an empty or malformed package prefix");
			}
			result.add(prefix);
		}
		return List.copyOf(result);
	}

	private static void addPackage(String name, TreeSet<String> packages, String task) {
		if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class")) return;
		int slash = name.lastIndexOf('/');
		if (slash < 0) throw new IllegalArgumentException(
				"Cannot derive ASM package scope for " + task + ": default-package production class " + name);
		packages.add(name.substring(0, slash).replace('/', '.') + ".");
	}
}
