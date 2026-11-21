// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.sap.oss.smarttestpicker.store.RemoteStoreClient;


/**
 * Maven Mojo that pushes a coverage map to a remote HTTP store.
 *
 * <p>Uploads the local coverage map for the configured base branch.
 * Typically run on CI after generating the coverage map on the main branch.</p>
 *
 * <p>Usage: {@code mvn smart-test-picker:push-map -DremoteUrl=https://...}</p>
 */
@Mojo(name = "push-map", defaultPhase = LifecyclePhase.VERIFY)
public class PushCoverageMapMojo extends AbstractMojo
{

	/** Remote store base URL; the transport stores coverage-map bytes by base branch. Default: {@code unset}. */
	@Parameter(required = true, property = "smartTestPicker.remoteUrl")
	private String remoteUrl;

	/** Git base branch used by the legacy change-detection and storage flow. Default: {@code main}. */
	@Parameter(defaultValue = "main", property = "smartTestPicker.baseBranch")
	private String baseBranch;

	/** HTTP Basic Auth username for the remote store. Default: {@code unset}. */
	@Parameter(property = "smartTestPicker.remoteUser")
	private String remoteUser;

	/** HTTP Basic Auth password for the remote store; supply credentials externally rather than in source. Default: {@code unset}. */
	@Parameter(property = "smartTestPicker.remotePassword")
	private String remotePassword;

	/** Coverage-map bytes to upload to the configured remote store. Default: {@code ${project.build.directory}/test-coverage-map.json}. */
	@Parameter(defaultValue = "${project.build.directory}/test-coverage-map.json", property = "smartTestPicker.inputFile")
	private File inputFile;

	@Override
	public void execute() throws MojoExecutionException
	{
		if (!inputFile.exists())
		{
			throw new MojoExecutionException("Coverage map not found: " + inputFile.getAbsolutePath()
					+ ". Run generate-coverage-map first.");
		}

		getLog().info("[SmartTestPicker] Pushing coverage map to " + remoteUrl);

		try
		{
			byte[] data = Files.readAllBytes(inputFile.toPath());
			RemoteStoreClient client = new RemoteStoreClient(remoteUrl, remoteUser, remotePassword);
			client.push(baseBranch, data);

			getLog().info("[SmartTestPicker] Pushed coverage map (" + data.length + " bytes) for branch '"
					+ baseBranch + "'");
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failed to push coverage map to " + remoteUrl, e);
		}
	}
}
