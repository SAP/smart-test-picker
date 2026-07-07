// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import com.sap.oss.smarttestpicker.engine.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.sap.oss.smarttestpicker.store.RemoteStoreClient;


/**
 * Maven Mojo that pulls a coverage map from a remote HTTP store.
 *
 * <p>Downloads the coverage map for the configured base branch and writes it
 * to the local build directory. If no remote map exists (404), the mojo
 * completes without error.</p>
 *
 * <p>Usage: {@code mvn smart-test-picker:pull-map -DremoteUrl=https://...}</p>
 */
@Mojo(name = "pull-map", requiresProject = false)
public class PullCoverageMapMojo extends AbstractMojo
{

	@Parameter(required = true, property = "smartTestPicker.remoteUrl")
	private String remoteUrl;

	@Parameter(defaultValue = "main", property = "smartTestPicker.baseBranch")
	private String baseBranch;

	@Parameter(property = "smartTestPicker.remoteUser")
	private String remoteUser;

	@Parameter(property = "smartTestPicker.remotePassword")
	private String remotePassword;

	@Parameter(defaultValue = "${project.build.directory}/test-coverage-map.json", property = "smartTestPicker.outputFile")
	private File outputFile;

	@Override
	public void execute() throws MojoExecutionException
	{
		getLog().info("[SmartTestPicker] Pulling coverage map from " + remoteUrl);

		RemoteStoreClient client = new RemoteStoreClient(remoteUrl, remoteUser, remotePassword);

		try
		{
			byte[] data = client.pull(baseBranch);

			if (data != null)
			{
				FileUtils.ensureParentDirExists(outputFile);
				Files.write(outputFile.toPath(), data);
				getLog().info("[SmartTestPicker] Pulled coverage map (" + data.length + " bytes) -> "
						+ outputFile.getAbsolutePath());
			}
			else
			{
				getLog().warn("[SmartTestPicker] No remote coverage map found for branch '" + baseBranch + "'");
			}
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to pull coverage map from " + remoteUrl, e);
		}
	}
}
