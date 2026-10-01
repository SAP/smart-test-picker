// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;

class SmartTestMojoInvocationTest
{
	private static void setUserProperties(SmartTestMojo mojo, Properties properties)
	{
		try
		{
			var field = SmartTestMojo.class.getDeclaredField("userProperties");
			field.setAccessible(true);
			field.set(mojo, properties);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	@Test void preservesGenericMavenUserPropertiesWithoutMutatingParent()
	{
		Properties parent = new Properties();
		parent.setProperty("foo", "bar");
		parent.setProperty("animal.sniffer.skip", "true");
		parent.setProperty("skipTests", "false");
		SmartTestMojo mojo = new SmartTestMojo();
		setUserProperties(mojo, parent);

		var request = mojo.createInvocationRequest(new File("."), "module-a", "p.T#x");

		assertEquals("bar", request.getProperties().getProperty("foo"));
		assertEquals("true", request.getProperties().getProperty("animal.sniffer.skip"));
		assertEquals("false", request.getProperties().getProperty("skipTests"));
		assertNotSame(parent, request.getProperties());
		request.getProperties().setProperty("foo", "child-value");
		assertEquals("bar", parent.getProperty("foo"));
	}

	@Test void stpOwnedPropertiesOverrideConflictingParentValues()
	{
		Properties parent = new Properties();
		parent.setProperty("test", "incorrect-parent-selector");
		parent.setProperty("failIfNoSpecifiedTests", "false");
		parent.setProperty("maven.repo.local", "/tmp/incorrect-parent-repository");
		SmartTestMojo mojo = new SmartTestMojo();
		setUserProperties(mojo, parent);

		String previous = System.getProperty("maven.repo.local");
		try
		{
			System.setProperty("maven.repo.local", "/tmp/frozen-evaluation-repository");
			var request = mojo.createInvocationRequest(
					new File("."), "module-a", "GeneratedClass#selectedMethod");
			assertEquals("GeneratedClass#selectedMethod", request.getProperties().getProperty("test"));
			assertEquals("true", request.getProperties().getProperty("failIfNoSpecifiedTests"));
			assertEquals("/tmp/frozen-evaluation-repository",
					request.getProperties().getProperty("maven.repo.local"));
		}
		finally
		{
			if (previous == null) System.clearProperty("maven.repo.local");
			else System.setProperty("maven.repo.local", previous);
		}

		assertEquals("incorrect-parent-selector", parent.getProperty("test"));
		assertEquals("false", parent.getProperty("failIfNoSpecifiedTests"));
		assertEquals("/tmp/incorrect-parent-repository", parent.getProperty("maven.repo.local"));
	}

	@Test void putsExactSelectorInInvocationRequestProperties()
	{
		String selector = "a.Outer$Inner#testFoo+testBar,b.OtherTest#testBaz[*]";
		var request = new SmartTestMojo().createInvocationRequest(new File("."), "module-a", selector);
		assertEquals(selector, request.getProperties().getProperty("test"));
		assertEquals("true", request.getProperties().getProperty("failIfNoSpecifiedTests"));
		assertEquals(java.util.List.of("module-a"), request.getProjects());
	}

	@Test void fullSuiteRequestDoesNotInventTestFilter()
	{
		var request = new SmartTestMojo().createInvocationRequest(new File("."), "module-a", null);
		assertNull(request.getProperties().getProperty("test"));
	}

	@Test void singleExecutionRootDoesNotPassInvalidProjectList() throws Exception
	{
		SmartTestMojo mojo = new SmartTestMojo(); MavenProject root = new MavenProject();
		root.setArtifactId("root-only"); root.setPackaging("jar"); root.setExecutionRoot(true); root.setBuild(new Build());
		var field = SmartTestMojo.class.getDeclaredField("reactorProjects"); field.setAccessible(true); field.set(mojo, java.util.List.of(root));
		var request = mojo.createInvocationRequest(new File("."), "root-only", "p.T#x");
		assertTrue(request.getProjects() == null || request.getProjects().isEmpty());
	}

	@Test void explicitlyPinnedLocalRepositoryReachesForkedMaven()
	{
		String previous = System.getProperty("maven.repo.local");
		try
		{
			System.setProperty("maven.repo.local", "/tmp/frozen-evaluation-repository");
			var request = new SmartTestMojo().createInvocationRequest(new File("."), "module-a", "p.T#x");
			assertEquals("/tmp/frozen-evaluation-repository",
					request.getProperties().getProperty("maven.repo.local"));
		}
		finally
		{
			if (previous == null) System.clearProperty("maven.repo.local");
			else System.setProperty("maven.repo.local", previous);
		}
	}

	@Test void childModuleExecutionUsesParentReactorPom() throws Exception
	{
		File reactor = new File("build/test-reactor").getAbsoluteFile();
		File module = new File(reactor, "module-a");
		MavenProject parent = new MavenProject();
		parent.setArtifactId("parent");
		parent.setPackaging("pom");
		parent.setFile(new File(reactor, "pom.xml"));
		MavenProject child = new MavenProject();
		child.setArtifactId("module-a");
		child.setPackaging("jar");
		child.setExecutionRoot(true);
		child.setFile(new File(module, "pom.xml"));

		SmartTestMojo mojo = new SmartTestMojo();
		var field = SmartTestMojo.class.getDeclaredField("reactorProjects");
		field.setAccessible(true);
		field.set(mojo, java.util.List.of(parent, child));

		assertEquals(reactor.toPath().normalize(),
				mojo.findReactorInvocationBaseDir(module).toPath().normalize());
	}

	@Test void childModuleExecutionUsesMavenMultiModuleDirectory() throws Exception
	{
		File reactor = new File("build/test-maven-root").getAbsoluteFile();
		File module = new File(reactor, "nested/module-a");
		assertTrue(reactor.mkdirs() || reactor.isDirectory());
		assertTrue(new File(reactor, "pom.xml").createNewFile()
				|| new File(reactor, "pom.xml").isFile());
		String previous = System.getProperty("maven.multiModuleProjectDirectory");
		try
		{
			System.setProperty("maven.multiModuleProjectDirectory", reactor.getAbsolutePath());
			assertEquals(reactor.toPath().normalize(), new SmartTestMojo()
					.findReactorInvocationBaseDir(module).toPath().normalize());
		}
		finally
		{
			if (previous == null) System.clearProperty("maven.multiModuleProjectDirectory");
			else System.setProperty("maven.multiModuleProjectDirectory", previous);
		}
	}

	@Test void standaloneModulePomDoesNotExpandChildInvocationToRepositoryRoot() throws Exception
	{
		File reactor = new File("build/test-standalone-root").getAbsoluteFile();
		File module = new File(reactor, "nested/module-a");
		assertTrue(reactor.mkdirs() || reactor.isDirectory());
		assertTrue(new File(reactor, "pom.xml").createNewFile()
				|| new File(reactor, "pom.xml").isFile());
		MavenProject child = new MavenProject();
		child.setArtifactId("module-a");
		child.setPackaging("jar");
		child.setExecutionRoot(true);
		child.setFile(new File(module, "pom.xml"));

		SmartTestMojo mojo = new SmartTestMojo();
		var field = SmartTestMojo.class.getDeclaredField("reactorProjects");
		field.setAccessible(true);
		field.set(mojo, java.util.List.of(child));
		String previous = System.getProperty("maven.multiModuleProjectDirectory");
		try
		{
			System.setProperty("maven.multiModuleProjectDirectory", reactor.getAbsolutePath());
			assertEquals(module.toPath().normalize(),
					mojo.findReactorInvocationBaseDir(module).toPath().normalize());
		}
		finally
		{
			if (previous == null) System.clearProperty("maven.multiModuleProjectDirectory");
			else System.setProperty("maven.multiModuleProjectDirectory", previous);
		}
	}
}
