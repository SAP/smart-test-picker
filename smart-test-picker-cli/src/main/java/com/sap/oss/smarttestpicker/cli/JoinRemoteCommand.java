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

    @Option(names = "--client-manifest", paramLabel = "FILE",
            description = "Select only exact SuiteID/TestID/RequestID matches from a Karate schema-v1 manifest")
    private Path clientManifest;

    @Option(names = "--report", paramLabel = "FILE",
            description = "Write client correlation details and unmatched requests (requires --client-manifest)")
    private Path report;

    @Option(names = "--require-all-matched",
            description = "Exit 2 after writing results if any client request has no remote observation")
    private boolean requireAllMatched;

    @Override public Integer call() {
        Path normalizedOutput = output.toAbsolutePath().normalize();
        try {
            if (clientManifest == null && (report != null || requireAllMatched))
                throw new IllegalArgumentException("--report and --require-all-matched require --client-manifest");
            List<Path> protectedInputs = new ArrayList<>(inputs);
            if (clientManifest != null) protectedInputs.add(clientManifest);
            for (Path input : protectedInputs) {
                rejectSameFile(input, normalizedOutput);
                if (report != null) rejectSameFile(input, report);
            }
            if (report != null) rejectSameFile(normalizedOutput, report);
            String json = RemoteFragmentJoiner.join(inputs);
            RemoteClientManifest.Selection selection = clientManifest == null ? null : RemoteClientManifest.select(clientManifest, json);
            write(normalizedOutput, selection == null ? json : selection.map());
            if (report != null) write(report.toAbsolutePath().normalize(), selection.report());
            System.out.println("Joined " + inputs.size() + " Remote STP fragment(s) into " + normalizedOutput);
            if (selection != null) {
                System.out.println("Client requests: matched=" + selection.matched() + ", unmatched=" + selection.missing()
                        + ", excluded remote requests=" + selection.excluded());
                if (selection.missing() != 0) {
                    System.err.println("Warning: " + selection.missing() + " client request(s) have no observation in the supplied remote fragments.");
                    if (report == null) {
                        var details = com.google.gson.JsonParser.parseString(selection.report()).getAsJsonObject().getAsJsonArray("requests");
                        for (var detail : details) {
                            var request = detail.getAsJsonObject();
                            if (request.get("status").getAsString().equals("UNMATCHED"))
                                System.err.println("Unmatched: " + request.get("testSuiteId").getAsString() + " / "
                                        + request.get("testId").getAsString() + " / " + request.get("requestId").getAsString());
                        }
                    }
                    if (requireAllMatched) return 2;
                }
            }
            return 0;
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            System.err.println("Error: " + message);
            return 1;
        }
    }

    private static void rejectSameFile(Path input, Path destination) throws IOException {
        if (input.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())
                || (Files.exists(input) && Files.exists(destination) && Files.isSameFile(input, destination)))
            throw new IOException("output/report path aliases an input or another output: " + destination);
    }

    private static void write(Path output, String json) throws IOException {
        try {
            Files.createDirectories(output.getParent());
            Path temp = output.resolveSibling(output.getFileName() + ".stp-join-" + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(temp, json);
                try {
                    Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException failure) {
            throw new IOException("cannot write joined Remote STP output " + output + ": " + failure.getMessage(), failure);
        }
    }
}
