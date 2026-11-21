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
    void supportedMavenDoesNotDependOnCoreManifestVersion(String goal) throws Exception {
        String executable = System.getenv("STP_TEST_MAVEN_39");
        assumeTrue(executable != null && Files.isExecutable(Path.of(executable)), "Set STP_TEST_MAVEN_39 to a Maven 3.9 executable");
        Path source = Path.of(executable).toAbsolutePath().getParent().getParent();
        Path copy = temporary.resolve("maven-without-manifest-version");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = copy.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        Path core;
        try (var jars = Files.list(copy.resolve("lib"))) {
            core = jars.filter(path -> path.getFileName().toString().matches("maven-core-.*\\.jar")).findFirst().orElseThrow();
        }
        Path replacement = core.resolveSibling("core-replacement.jar");
        try (var jar = new java.util.jar.JarFile(core.toFile())) {
            var manifest = new java.util.jar.Manifest(jar.getManifest());
            manifest.getMainAttributes().remove(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION);
            for (var attributes : manifest.getEntries().values()) {
                attributes.remove(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION);
            }
            try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(replacement), manifest)) {
                for (var entry : java.util.Collections.list(jar.entries())) {
                    if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;
                    output.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                    try (var input = jar.getInputStream(entry)) { input.transferTo(output); }
                    output.closeEntry();
                }
            }
        }
        Files.move(replacement, core, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path root = JacocoParityIntegrationTest.fixture(temporary.resolve("manifest-independent"));
        JacocoParityIntegrationTest.success(maven(root, "install", "-DskipTests", "-Djacoco.skip=true"));
        String revision = JacocoParityIntegrationTest.command(root, "git", "rev-parse", "HEAD").trim();
        var arguments = new ArrayList<>(List.of(copy.resolve("bin/mvn").toString(), "--batch-mode", "--no-transfer-progress",
                "--settings", root.resolve("functional-test-settings.xml").toString(), JacocoParityIntegrationTest.PLUGIN + goal,
                "-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.coverageCollector=ASM",
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"), "-DsmartTestPicker.revision=" + revision,
                "-DsmartTestPicker.prHeadRevision=" + revision, "-DsmartTestPicker.shardId=local",
                "-DsmartTestPicker.outputFile=" + root.resolve("inventory.json")));
        Process process = new ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        Path log = Path.of("build", "maven-version-boundary", "without-manifest-" + goal + ".log");
        Files.createDirectories(log.getParent()); Files.writeString(log, output);
        assertEquals(0, exit, output);
        if (goal.equals("generate-reactor-head-test-inventory")) assertTrue(Files.isRegularFile(root.resolve("inventory.json")));
    }

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
