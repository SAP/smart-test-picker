// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.selector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Read-only Git commit graph access shared by revision safety boundaries. */
final class GitRevisionAccess
{
	private final File directory;

	GitRevisionAccess(File directory) { this.directory = directory; }

	String resolveCommit(String revision)
	{
		return output("rev-parse", "--verify", revision + "^{commit}").trim();
	}

	boolean isAncestor(String revision, String descendant)
	{
		CommandResult result = execute(true, "merge-base", "--is-ancestor", revision, descendant);
		if (result.exitCode == 0) return true;
		if (result.exitCode == 1) return false;
		throw new IllegalStateException("git merge-base --is-ancestor failed with exit " + result.exitCode);
	}

	int commitDistance(String revision, String head)
	{
		return Integer.parseInt(output("rev-list", "--count", revision + ".." + head).trim());
	}

	String output(String... args)
	{
		CommandResult result = execute(false, args);
		if (result.exitCode != 0)
			throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + result.output);
		return result.output;
	}

	private CommandResult execute(boolean statusOnly, String... args)
	{
		try
		{
			List<String> command = new ArrayList<>(); command.add("git"); command.addAll(List.of(args));
			Process process = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exit = process.waitFor();
			return new CommandResult(exit, statusOnly ? "" : output.trim());
		}
		catch (IOException e) { throw new IllegalStateException("Failed to run git", e); }
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while running git", e);
		}
	}

	private record CommandResult(int exitCode, String output) {}
}
