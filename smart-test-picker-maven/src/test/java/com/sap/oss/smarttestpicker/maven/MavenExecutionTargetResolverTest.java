// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenExecutionTargetResolverTest {
	@Test void resolvesRootChildAndNestedAndRejectsEscape(@TempDir Path root) {
		var resolver = new MavenExecutionTargetResolver();
		assertEquals("maven:.", resolver.resolve(root.toFile(), project(root)).toString());
		assertEquals("maven:child", resolver.resolve(root.toFile(), project(root.resolve("child"))).toString());
		assertEquals("maven:parent/child", resolver.resolve(root.toFile(), project(root.resolve("parent/child"))).toString());
		assertEquals("maven:parent/child@surefire@default-test", resolver.resolve(root.toFile(),
				project(root.resolve("parent/child")), "surefire", "default-test", null).toString());
		assertEquals("maven:parent/child@surefire@default-test@it-plugin", resolver.resolve(root.toFile(),
				project(root.resolve("parent/child")), "surefire", "default-test", "it-plugin").toString());
		assertThrows(IllegalArgumentException.class, () -> resolver.resolve(root.toFile(), project(root.resolveSibling("escape"))));
		assertThrows(IllegalArgumentException.class, () -> resolver.resolve(root.toFile(), project(root), "failsafe", "", null));
	}
	@Test void rejectsMissingModuleBaseDirectory(@TempDir Path root) {
		assertThrows(IllegalArgumentException.class, () -> new MavenExecutionTargetResolver().resolve(root.toFile(), new MavenProject()));
	}
	private static MavenProject project(Path base) { MavenProject p = new MavenProject(); p.setFile(base.resolve("pom.xml").toFile()); return p; }
}
