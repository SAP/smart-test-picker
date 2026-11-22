// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipFile;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CentralBundleFunctionalTest {
    @TempDir Path temporary;

    @Test
    void bundleDoesNotRetainStalePublicationFiles() throws Exception {
        Path source = Path.of(System.getProperty("stp.test.source.root"));
        Path root = Files.createDirectories(temporary.resolve("project"));
        SourceTreeFixture.copy(source, root);
        String version = "0.0.0-bundle-test";
        String stale = "com/sap/oss/smart-test-picker/stp-agent/" + version + "/obsolete.jar";
        Path repository = root.resolve("build/readiness/central-repository");
        Files.createDirectories(repository.resolve(stale).getParent());
        Files.writeString(repository.resolve(stale), "stale publication");
        Files.writeString(repository.resolve(stale + ".asc"), "stale signature");
        // GnuPG's agent uses a Unix socket, whose path length is limited on macOS.
        Path keyHome = Files.createTempDirectory(Files.createDirectories(source.resolve("build")), "g");
        String gpg = System.getenv().getOrDefault("STP_TEST_GPG", "gpg");
        try {
            command(root, gpg, "--homedir", keyHome.toString(), "--batch", "--pinentry-mode", "loopback",
                    "--passphrase", "", "--quick-generate-key", "Bundle fixture <fixture@example.invalid>",
                    "rsa2048", "sign", "0");
            String key = command(root, gpg, "--homedir", keyHome.toString(), "--batch", "--armor", "--export-secret-keys");
            var environment = new HashMap<>(System.getenv());
            environment.put("STP_SIGNING_KEY", key);
            environment.put("STP_SIGNING_PASSWORD", "");
            GradleRunner.create().withProjectDir(root.toFile()).withEnvironment(environment)
                    .withArguments("centralBundle", "-PstpVersion=" + version, "--max-workers=2", "--console=plain")
                    .build();
            Path bundle = root.resolve("build/readiness/stp-" + version + "-central.zip");
            try (ZipFile zip = new ZipFile(bundle.toFile())) {
                assertNull(zip.getEntry(stale), "Stale publication must not enter the signed bundle");
                assertNull(zip.getEntry(stale + ".asc"));
                assertNotNull(zip.getEntry("com/sap/oss/smart-test-picker/stp-agent/" + version
                        + "/stp-agent-" + version + ".jar"));
            }
            assertFalse(Files.exists(repository.resolve(stale)));
        } finally {
            try {
                String gpgconf = Path.of(gpg).resolveSibling("gpgconf").toString();
                command(root, gpgconf, "--homedir", keyHome.toString(), "--kill", "gpg-agent");
            } finally {
                // The agent may remove its socket concurrently after gpgconf returns.
                Files.walkFileTree(keyHome, new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file,
                            java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                        Files.deleteIfExists(file);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFileFailed(Path file, java.io.IOException error)
                            throws java.io.IOException {
                        if (!(error instanceof java.nio.file.NoSuchFileException)) throw error;
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult postVisitDirectory(Path directory, java.io.IOException error)
                            throws java.io.IOException {
                        if (error != null && !(error instanceof java.nio.file.NoSuchFileException)) throw error;
                        Files.deleteIfExists(directory);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    private static String command(Path root, String... arguments) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(List.of(arguments)))
                .directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "Command failed: " + arguments[0]);
        return output;
    }
}
