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
 * CLI command to push a coverage map to a remote store.
 */
@Command(
		name = "push-map",
		description = "Push a coverage map to a remote store",
		mixinStandardHelpOptions = true
)
public class PushMapCommand implements Runnable
{

	@Option(names = "--url", required = true, description = "Remote store base URL")
	private String url;

	@Option(names = "--branch", defaultValue = "main", description = "Base branch (default: main)")
	private String branch;

	@Option(names = "--input", required = true, description = "Coverage map file to upload")
	private File input;

	@Option(names = "--user", description = "HTTP Basic Auth username")
	private String user;

	@Option(names = "--password", description = "HTTP Basic Auth password")
	private String password;

	@Override
	public void run()
	{
		if (!input.exists())
		{
			System.err.println("Coverage map file not found: " + input);
			System.exit(1);
		}

		try
		{
			byte[] data = Files.readAllBytes(input.toPath());
			RemoteStoreClient client = new RemoteStoreClient(url, user, password);
			client.push(branch, data);
			System.out.printf("Pushed coverage map: %s (%d bytes)%n", input, data.length);
		}
		catch (IOException e)
		{
			System.err.println("Failed to push coverage map: " + e.getMessage());
			System.exit(1);
		}
	}
}
