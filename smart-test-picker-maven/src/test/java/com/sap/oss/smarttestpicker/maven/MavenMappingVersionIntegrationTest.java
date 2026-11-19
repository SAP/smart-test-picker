// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.maven;

/** Uses an actual older Maven process, rather than mocking Maven's version metadata. */
class MavenMappingVersionIntegrationTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"generate-reactor-head-test-inventory", "prepare-reactor-executable-mapping"})
    void unsupportedMavenNamesItsVersionAndTheRequirement(String goal) throws Exception {
        String executable = System.getenv("STP_TEST_MAVEN_38");
        assumeTrue(executable != null && Files.isExecutable(Path.of(executable)), "Set STP_TEST_MAVEN_38 to a Maven 3.8 executable");
        Path root = JacocoParityIntegrationTest.fixture(temporary.resolve("version"));
        JacocoParityIntegrationTest.success(maven(root, "install", "-DskipTests", "-Djacoco.skip=true"));
        String version = JacocoParityIntegrationTest.command(root, executable, "--version");
        var matcher = java.util.regex.Pattern.compile("Apache Maven (3\\.8\\.\\d+)").matcher(version);
        assertTrue(matcher.find(), version);
        String running = matcher.group(1);
        String revision = JacocoParityIntegrationTest.command(root, "git", "rev-parse", "HEAD").trim();
        var arguments = new ArrayList<>(List.of(executable, "--batch-mode", "--no-transfer-progress", "--settings",
                root.resolve("functional-test-settings.xml").toString(), JacocoParityIntegrationTest.PLUGIN + goal,
                "-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.coverageCollector=ASM",
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"),
                "-DsmartTestPicker.revision=" + revision, "-DsmartTestPicker.prHeadRevision=" + revision,
                "-DsmartTestPicker.shardId=local", "-DsmartTestPicker.outputFile=" + root.resolve("inventory.json")));
        Process process = new ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        Path log = Path.of("build", "maven-version-boundary", goal + ".log");
        Files.createDirectories(log.getParent()); Files.writeString(log, output);
        assertNotEquals(0, exit, output);
        assertTrue(output.contains("Maven " + running) && output.contains("3.9"), output);
        assertFalse(Files.exists(root.resolve("inventory.json")));
    }
}
