// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedAsmMappingTest {
	@Test void missingAgentFailsClosedAtAlignedPluginVersion(@TempDir Path temp) throws Exception {
		AtomicInteger requests = new AtomicInteger();
		var mojo = mojo(temp, "7.8-test", requests);
		var failure = assertThrows(MojoExecutionException.class, mojo::execute);
		assertTrue(failure.getMessage().contains("Cannot resolve ASM mapping agent com.sap.oss.smart-test-picker:stp-agent:jar:7.8-test"), failure.getMessage());
		assertEquals(1, requests.get());
		assertFalse(Files.exists(temp.resolve("target")), "Resolution failure must precede configuration/attachment");
	}
	@Test void unknownPluginVersionHasNoFallback(@TempDir Path temp) throws Exception {
		AtomicInteger requests = new AtomicInteger();
		var mojo = mojo(temp, null, requests);
		var failure = assertThrows(MojoExecutionException.class, mojo::execute);
		assertTrue(failure.getMessage().contains("Cannot determine the running Maven plugin version"));
		assertEquals(0, requests.get());
	}
	@Test void guardPrecedesResolutionEvenWithUnavailableAgent(@TempDir Path temp) throws Exception {
		AtomicInteger requests = new AtomicInteger();
		var mojo = mojo(temp, "7.8-test", requests);
		var field = PrepareReactorExecutableMappingMojo.class.getDeclaredField("session"); field.setAccessible(true);
		((MavenSession) field.get(mojo)).getUserProperties().setProperty("forkCount", "2");
		var failure = assertThrows(MojoExecutionException.class, mojo::execute);
		assertTrue(failure.getMessage().contains("unsupported forkCount=2"));
		assertEquals(0, requests.get());
	}
	@Test void scopeValidationNormalizesPrefixesAndRejectsEmptyEntries() {
		assertEquals("a.,b.", AsmMappingAttachment.prefixes(" b/ , a., b.", true));
		assertEquals("", AsmMappingAttachment.prefixes(null, false));
		assertThrows(IllegalArgumentException.class, () -> AsmMappingAttachment.prefixes("a.,", true));
		assertThrows(IllegalArgumentException.class, () -> AsmMappingAttachment.prefixes("*", true));
	}

	private static PrepareReactorExecutableMappingMojo mojo(Path temp, String version, AtomicInteger requests) throws Exception {
		var project = new MavenProject(); project.setFile(temp.resolve("pom.xml").toFile());
		project.setArtifactId("fixture"); project.setExecutionRoot(true);
		var build = new Build(); build.setDirectory(temp.resolve("target").toString()); project.setBuild(build);
		var request = new DefaultMavenExecutionRequest();
		request.getUserProperties().setProperty("smartTestPicker.coverageCollector", "ASM");
		var session = new MavenSession(null, new DefaultRepositorySystemSession(), request, new DefaultMavenExecutionResult());
		session.setCurrentProject(project);
		var descriptor = new PluginDescriptor(); descriptor.setVersion(version);
		Path assignment = temp.resolve("assignment.json");
		Files.writeString(assignment, """
			{"version":1,"revision":"rev","shardId":"shard","tests":["maven:.::fixture.Test#test"]}
			""");
		var mojo = new PrepareReactorExecutableMappingMojo();
		set(mojo, "project", project); set(mojo, "session", session); set(mojo, "reactorProjects", List.of(project));
		set(mojo, "pluginDescriptor", descriptor); set(mojo, "assignmentFile", assignment.toFile());
		set(mojo, "revision", "rev"); set(mojo, "shardId", "shard"); set(mojo, "asmIncludes", "fixture.");
		set(mojo, "repositorySystem", Proxy.newProxyInstance(RepositorySystem.class.getClassLoader(), new Class<?>[]{RepositorySystem.class},
				(proxy, method, args) -> {
					assertEquals("resolveArtifact", method.getName()); requests.incrementAndGet();
					var artifact = ((ArtifactRequest) args[1]).getArtifact();
					assertEquals("7.8-test", artifact.getVersion()); assertEquals("", artifact.getClassifier());
					throw new ArtifactResolutionException(List.of(), "fixture agent unavailable");
				}));
		return mojo;
	}
	private static void set(Object target, String name, Object value) throws Exception {
		var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
	}
}
