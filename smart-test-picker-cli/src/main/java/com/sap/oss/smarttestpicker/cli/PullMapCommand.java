// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.sap.oss.smarttestpicker.store.CoverageMapResolver;
import com.sap.oss.smarttestpicker.store.RemoteStoreClient;


/**
 * CLI command to pull a coverage map from a remote store.
 *
 * <p>Downloads the coverage map and stores it in the local cache at
 * {@code ~/.gradle/smart-test-picker/PROJECT_NAME/remote-coverage-map.json}
 * unless {@code --output} is specified for a custom location.</p>
 */
@Command(
		name = "pull-map",
		description = "Pull a coverage map from a remote store",
		mixinStandardHelpOptions = true
)
public class PullMapCommand implements Runnable
{

	@Option(names = "--url", required = true, description = "Remote store base URL")
	private String url;

	@Option(names = "--branch", defaultValue = "main", description = "Base branch (default: main)")
	private String branch;

	@Option(names = "--output", description = "Output file path (default: local cache)")
	private File output;

	@Option(names = "--project-dir",
			description = "Project root directory (default: current dir)")
	private File projectDir;

	@Option(names = "--user", description = "HTTP Basic Auth username")
	private String user;

	@Option(names = "--password", description = "HTTP Basic Auth password")
	private String password;

	@Override
	public void run()
	{
		if (projectDir == null)
		{
			projectDir = new File(System.getProperty("user.dir"));
		}

		ConsoleLogger logger = new ConsoleLogger();

		// Determine output location: explicit --output or cache directory
		File targetFile;
		if (output != null)
		{
			targetFile = output;
		}
		else
		{
			CoverageMapResolver resolver = new CoverageMapResolver(projectDir, logger);
			targetFile = resolver.getRemoteMapPath();
		}

		logger.info("[SmartTestPicker] Pulling coverage map from: {}/{}/test-coverage-map.json", url, branch);
		logger.info("[SmartTestPicker] Target: {}", targetFile.getAbsolutePath());

		try
		{
			RemoteStoreClient client = new RemoteStoreClient(url, user, password);
			byte[] data = client.pull(branch);

			if (data != null)
			{
				targetFile.getParentFile().mkdirs();
				Files.write(targetFile.toPath(), data);
				logger.info("[SmartTestPicker] Pulled coverage map: {} ({} bytes)", targetFile, data.length);
			}
			else
			{
				logger.warn("[SmartTestPicker] No coverage map found for branch '{}' at {}", branch, url);
			}
		}
		catch (IOException e)
		{
			System.err.println("Failed to pull coverage map: " + e.getMessage());
			System.exit(1);
		}
	}
}
