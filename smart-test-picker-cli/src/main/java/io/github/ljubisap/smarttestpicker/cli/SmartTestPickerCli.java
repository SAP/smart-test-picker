// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;


/**
 * Top-level picocli command and CLI entry point for the smart-test-picker tool.
 *
 * <p>Provides a subcommand-based interface for the full coverage pipeline:</p>
 * <ul>
 *   <li>{@code exec-to-xml} -- convert per-test JaCoCo .exec files to XML reports</li>
 *   <li>{@code generate-map} -- build a JSON coverage map from XML reports</li>
 *   <li>{@code query} -- search the coverage map by test, class, method, or substring</li>
 *   <li>{@code select-tests} -- select tests impacted by code changes</li>
 *   <li>{@code generate-report} -- generate HTML dashboard report</li>
 * </ul>
 *
 * <p>Usage: {@code smart-test-picker <subcommand> [options]}</p>
 *
 * @see ExecToXmlCommand
 * @see GenerateMapCommand
 * @see QueryCommand
 * @see SelectTestsCommand
 * @see GenerateReportCommand
 */
@Command(
		name = "smart-test-picker",
		description = "Regression test selection via per-test runtime coverage",
		mixinStandardHelpOptions = true,
		version = "0.1.11",
		subcommands = {
				ExecToXmlCommand.class,
				GenerateMapCommand.class,
				MergeMapsCommand.class,
				QueryCommand.class,
				SelectTestsCommand.class,
				GenerateReportCommand.class,
				PullMapCommand.class,
				PushMapCommand.class,
				RefreshedReportCommand.class
		}
)
public class SmartTestPickerCli
{

	public static void main(String[] args)
	{
		int exitCode = new CommandLine(new SmartTestPickerCli()).execute(args);
		System.exit(exitCode);
	}
}
