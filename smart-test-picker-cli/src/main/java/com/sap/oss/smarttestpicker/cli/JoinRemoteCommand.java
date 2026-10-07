// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "join-remote", mixinStandardHelpOptions = true,
		description = "Join Remote STP schema-v2 observation fragments")
public class JoinRemoteCommand implements Callable<Integer> {
	@Option(names = {"--input", "-i"}, required = true, arity = "1..*", paramLabel = "FILE",
			description = "Remote STP fragment JSON file(s); repeat --input or pass shell-expanded paths")
	private List<Path> inputs = new ArrayList<>();

	@Option(names = "--output", required = true, paramLabel = "FILE", description = "Joined map JSON output path")
	private Path output;

	@Override public Integer call() {
		Path normalizedOutput = output.toAbsolutePath().normalize();
		try {
			for (Path input : inputs) {
				if (input.toAbsolutePath().normalize().equals(normalizedOutput)) {
					System.err.println("Error: output path is also an input fragment: " + normalizedOutput);
					return 1;
				}
			}
			String json = RemoteFragmentJoiner.join(inputs);
			Path parent = normalizedOutput.getParent();
			try {
				if (parent != null) Files.createDirectories(parent);
				Path temp = normalizedOutput.resolveSibling(normalizedOutput.getFileName() + ".stp-join-" + UUID.randomUUID() + ".tmp");
				try {
					Files.writeString(temp, json);
					try {
						Files.move(temp, normalizedOutput, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
					} catch (AtomicMoveNotSupportedException unsupported) {
						Files.move(temp, normalizedOutput, StandardCopyOption.REPLACE_EXISTING);
					}
				} finally {
					Files.deleteIfExists(temp);
				}
			} catch (IOException writeFailure) {
				throw new IOException("cannot write joined Remote STP output " + normalizedOutput + ": " + writeFailure.getMessage(), writeFailure);
			}
			System.out.println("Joined " + inputs.size() + " Remote STP fragment(s) into " + normalizedOutput);
			return 0;
		} catch (Exception failure) {
			String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
			System.err.println("Error: " + message);
			return 1;
		}
	}
}
