// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;

class SmartTestMojoInvocationTest
{
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
}
