// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GradleAsmScopeTest {
	@TempDir Path temporary;

	@Test void rejectsIdenticalProductionAndTestDirectoriesEvenWithExplicitIncludes() throws Exception {
		var output = Files.createDirectory(temporary.resolve("classes")).toFile();
		var failure = assertThrows(IllegalArgumentException.class, () -> GradleAsmScope.resolve(
				":integrationTest", List.of(output), List.of(output), List.of(output), List.of("example.")));
		assertTrue(failure.getMessage().contains("identical production/test output directories"));
	}

	@Test void derivesFromJarAndSkipsMetadataAndModuleDescriptor() throws Exception {
		Path jar = temporary.resolve("production.jar");
		try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
			for (String name : List.of("module-info.class", "META-INF/versions/17/Hidden.class", "example/api/Service.class")) {
				output.putNextEntry(new JarEntry(name)); output.closeEntry();
			}
		}
		var scope = GradleAsmScope.resolve(":test", List.of(jar.toFile()), List.of(jar.toFile()), List.of(), List.of());
		assertEquals(List.of("example.api."), scope.includes());
		assertEquals(List.of(jar.toRealPath()), scope.sources());
	}

	@Test void rejectsMalformedPrefixesAndNormalizesExistingPrefixSyntax() {
		for (String bad : List.of("", " ", "example.*", "example.;instrumentation=off")) {
			assertThrows(IllegalArgumentException.class, () -> GradleAsmScope.prefixes(List.of(bad)));
		}
		assertEquals(List.of("example.api."), GradleAsmScope.prefixes(List.of(" example/api ", "example.api.")));
	}

	@Test void noCompiledPackagesCannotDeriveAnEmptyScope() throws Exception {
		var output = Files.createDirectory(temporary.resolve("classes")).toFile();
		var failure = assertThrows(IllegalArgumentException.class, () -> GradleAsmScope.resolve(
				":test", List.of(output), List.of(output), List.of(), List.of()));
		assertTrue(failure.getMessage().contains("no compiled production packages"));
	}
}
