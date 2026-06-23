// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
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
		project.getPluginManager().apply("io.github.ljubisap.smart-test-picker");
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
}
