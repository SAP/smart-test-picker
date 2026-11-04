// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.apache.maven.execution.*;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MavenAsmScopeTest {
	@Test void derivedPackagesIgnoreGroupIdAndTestOutputsWhileExplicitScopeOverrides(@TempDir Path temp) throws Exception {
		var project = project(temp); var session = session(project);
		Path output = Files.createDirectories(temp.resolve("target/classes/actual/nested"));
		Files.write(output.resolve("Value.class"), new byte[0]);
		Files.createDirectories(temp.resolve("target/test-classes/tests"));
		Files.write(temp.resolve("target/test-classes/tests/Test.class"), new byte[0]);
		assertEquals("actual.nested.", MavenAsmScope.resolve(session, List.of(project), null, null).includes());
		var override = MavenAsmScope.resolve(session, List.of(project), "other.", "other.generated.");
		assertEquals("other.", override.includes()); assertEquals("other.generated.", override.excludes());
		assertFalse(override.sources().contains(temp.resolve("target/test-classes")));
	}

	@ParameterizedTest @ValueSource(strings = {"missing-output", "empty-output", "default-package", "overlapping-output", "blank-override"})
	void refusesUnrepresentableOrIncompleteScope(String defect, @TempDir Path temp) throws Exception {
		var project = project(temp); var session = session(project);
		Files.createDirectories(temp.resolve("src/main/java")); Files.writeString(temp.resolve("src/main/java/Value.java"), "class Value {}");
		if (!defect.equals("missing-output")) Files.createDirectories(temp.resolve("target/classes"));
		if (defect.equals("default-package")) Files.write(temp.resolve("target/classes/Value.class"), new byte[0]);
		if (defect.equals("overlapping-output")) project.getBuild().setTestOutputDirectory(project.getBuild().getOutputDirectory());
		assertThrows(IllegalArgumentException.class, () -> MavenAsmScope.resolve(session, List.of(project),
				defect.equals("blank-override") ? " " : null, null));
	}

	private static MavenProject project(Path temp) {
		var project = new MavenProject(); project.setGroupId("not.the.package"); project.setArtifactId("fixture"); project.setVersion("1");
		project.setFile(temp.resolve("pom.xml").toFile()); project.addCompileSourceRoot(temp.resolve("src/main/java").toString());
		var build = new Build(); build.setDirectory(temp.resolve("target").toString());
		build.setOutputDirectory(temp.resolve("target/classes").toString()); build.setTestOutputDirectory(temp.resolve("target/test-classes").toString());
		project.setBuild(build); return project;
	}
	@SuppressWarnings("deprecation")
	private static MavenSession session(MavenProject project) {
		var session = new MavenSession(null, new DefaultRepositorySystemSession(), new DefaultMavenExecutionRequest(), new DefaultMavenExecutionResult());
		session.setAllProjects(List.of(project)); return session;
	}
}
