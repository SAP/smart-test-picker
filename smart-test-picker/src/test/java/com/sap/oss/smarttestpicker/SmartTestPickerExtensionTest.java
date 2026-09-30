// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class SmartTestPickerExtensionTest
{

	private Project project;
	private SmartTestPickerExtension extension;

	@BeforeEach
	void setUp()
	{
		project = ProjectBuilder.builder().build();
		project.getPluginManager().apply("com.sap.oss.smart-test-picker");
		extension = project.getExtensions().getByType(SmartTestPickerExtension.class);
	}

	@Test
	void defaultBaseBranch()
	{
		assertEquals("main", extension.getBaseBranch().get());
	}

	@Test
	void remoteStoreExtensionIsAccessible()
	{
		assertNotNull(extension.getRemoteStore());
	}

	@Test
	void remoteStoreUrlCanBeSet()
	{
		extension.remoteStore(rs -> {
			rs.getUrl().set("https://nexus.example.com/repo");
		});

		assertEquals("https://nexus.example.com/repo", extension.getRemoteStore().getUrl().get());
	}

	@Test
	void remoteStorePushDefaultsToNotPresent()
	{
		assertFalse(extension.getRemoteStore().getPush().isPresent());
	}

	@Test
	void remoteStoreCredentialsCanBeSet()
	{
		extension.remoteStore(rs -> {
			rs.credentials(creds -> {
				creds.getUsername().set("user1");
				creds.getPassword().set("pass1");
			});
		});

		assertEquals("user1", extension.getRemoteStore().getCredentials().getUsername().get());
		assertEquals("pass1", extension.getRemoteStore().getCredentials().getPassword().get());
	}

	@Test
	void pullCoverageMapTaskIsRegistered()
	{
		assertNotNull(project.getTasks().findByName("pullCoverageMap"));
	}

	@Test
	void pushCoverageMapTaskIsRegistered()
	{
		assertNotNull(project.getTasks().findByName("pushCoverageMap"));
	}

	@Test
	void pullTaskIsSkippedWhenUrlNotSet()
	{
		PullCoverageMapTask task = (PullCoverageMapTask) project.getTasks().getByName("pullCoverageMap");
		assertFalse(task.getUrl().isPresent());
	}

	@Test
	void perTestJacocoOutputWinsOverSubjectTestConfigurationAndUsesCustomBuildDirectory()
	{
		Project custom = ProjectBuilder.builder().build();
		custom.getLayout().getBuildDirectory().set(custom.getProjectDir().toPath().resolve("custom-target").toFile());
		custom.getPluginManager().apply("java");
		custom.getPluginManager().apply("jacoco");
		custom.getPluginManager().apply("com.sap.oss.smart-test-picker");

		org.gradle.api.tasks.testing.Test test = (org.gradle.api.tasks.testing.Test) custom.getTasks()
				.getByName("test");
		test.setSystemProperties(java.util.Map.of("subject.property", "preserved"));
		test.setMaxParallelForks(4);
		JacocoTaskExtension jacoco = test.getExtensions().getByType(JacocoTaskExtension.class);
		jacoco.setEnabled(false);
		jacoco.setDestinationFile(custom.file("subject-native.exec"));

		((ProjectInternal) custom).evaluate();

		java.io.File expectedDir = custom.file("custom-target/jacoco");
		assertEquals(expectedDir.getAbsolutePath(), test.getSystemProperties().get("stp.exec.dir"));
		assertEquals("preserved", test.getSystemProperties().get("subject.property"));
		assertEquals(1, test.getMaxParallelForks());
		assertTrue(jacoco.isEnabled());
		assertEquals(new java.io.File(expectedDir, "test.exec"), jacoco.getDestinationFile());

		// Simulate a test-acceleration/convention plugin changing JaCoCo after
		// project evaluation. STP's leading execution action must restore it.
		jacoco.setEnabled(false);
		test.getActions().get(0).execute(test);
		assertTrue(jacoco.isEnabled());

		GenerateTestCoverageJsonTask mapTask = (GenerateTestCoverageJsonTask) custom.getTasks()
				.getByName("generateTestCoverageJson");
		assertEquals(custom.file("custom-target/jacoco-xml"), mapTask.getReportsDir().get().getAsFile());
	}
}
