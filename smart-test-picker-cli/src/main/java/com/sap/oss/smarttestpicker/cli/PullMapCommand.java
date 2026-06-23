// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.sap.oss.smarttestpicker.store.RemoteStoreClient;


/**
 * CLI command to pull a coverage map from a remote store.
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

	@Option(names = "--output", required = true, description = "Output file path")
	private File output;

	@Option(names = "--user", description = "HTTP Basic Auth username")
	private String user;

	@Option(names = "--password", description = "HTTP Basic Auth password")
	private String password;

	@Override
	public void run()
	{
		try
		{
			RemoteStoreClient client = new RemoteStoreClient(url, user, password);
			byte[] data = client.pull(branch);

			if (data != null)
			{
				output.getParentFile().mkdirs();
				Files.write(output.toPath(), data);
				System.out.printf("Pulled coverage map: %s (%d bytes)%n", output, data.length);
			}
			else
			{
				System.out.printf("No coverage map found for branch '%s' at %s%n", branch, url);
			}
		}
		catch (IOException e)
		{
			System.err.println("Failed to pull coverage map: " + e.getMessage());
			System.exit(1);
		}
	}
}
