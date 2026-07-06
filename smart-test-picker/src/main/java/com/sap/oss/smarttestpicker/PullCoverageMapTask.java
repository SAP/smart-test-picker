// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import com.sap.oss.smarttestpicker.engine.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import com.sap.oss.smarttestpicker.store.RemoteStoreClient;


/**
 * Gradle task that pulls a coverage map from a remote store.
 *
 * <p>Downloads the coverage map for the configured base branch and writes it
 * to the local build directory. If no remote map exists (404), the task
 * completes without error — selectTests will fall back to full suite.</p>
 */
public abstract class PullCoverageMapTask extends DefaultTask
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

	@OutputFile
	public abstract RegularFileProperty getOutputFile();

	@TaskAction
	public void pull() throws IOException
	{
		String url = getUrl().get();
		String branch = getBaseBranch().get();
		String user = getUsername().getOrNull();
		String pass = getPassword().getOrNull();

		RemoteStoreClient client = new RemoteStoreClient(url, user, pass);
		byte[] data = client.pull(branch);

		if (data != null)
		{
			File output = getOutputFile().getAsFile().get();
			FileUtils.ensureParentDirExists(output);
			Files.write(output.toPath(), data);
			getLogger().lifecycle("[SmartTestPicker] Pulled coverage map from {} ({} bytes)",
					url, data.length);
		}
		else
		{
			getLogger().lifecycle("[SmartTestPicker] No remote coverage map found for branch '{}'", branch);
		}
	}
}
