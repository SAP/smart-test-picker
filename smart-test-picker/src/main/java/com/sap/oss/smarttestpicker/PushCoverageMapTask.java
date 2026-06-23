// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import io.github.ljubisap.smarttestpicker.store.RemoteStoreClient;


/**
 * Gradle task that pushes a coverage map to a remote store.
 *
 * <p>Uploads the local coverage map for the configured base branch.
 * Typically run on CI after generating the coverage map on the main branch.</p>
 */
public abstract class PushCoverageMapTask extends DefaultTask
{

	@Input
	public abstract Property<String> getUrl();

	@Input
	public abstract Property<String> getBaseBranch();

	@Input
	@Optional
	public abstract Property<String> getUsername();

	@Input
	@Optional
	public abstract Property<String> getPassword();

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputFile();

	@TaskAction
	public void push() throws IOException
	{
		String url = getUrl().get();
		String branch = getBaseBranch().get();
		String user = getUsername().getOrNull();
		String pass = getPassword().getOrNull();

		File input = getInputFile().getAsFile().get();
		byte[] data = Files.readAllBytes(input.toPath());

		RemoteStoreClient client = new RemoteStoreClient(url, user, pass);
		client.push(branch, data);

		getLogger().lifecycle("[SmartTestPicker] Pushed coverage map to {} ({} bytes)", url, data.length);
	}
}
