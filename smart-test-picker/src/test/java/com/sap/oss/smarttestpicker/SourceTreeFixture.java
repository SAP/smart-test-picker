// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** Copies tracked sources, or source-archive files without generated output. */
final class SourceTreeFixture {
    private SourceTreeFixture() { }

    static void copy(Path source, Path destination) throws Exception {
        Process probe;
        try {
            probe = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                    .directory(source.toFile()).redirectErrorStream(true).start();
        } catch (IOException unavailableGit) {
            copyArchive(source, destination);
            return;
        }
        String top = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (probe.waitFor() == 0 && Path.of(top).toRealPath().equals(source.toRealPath())) {
            Process files = new ProcessBuilder("git", "ls-files", "-z")
                    .directory(source.toFile()).redirectErrorStream(true).start();
            String names = new String(files.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (files.waitFor() != 0) throw new IOException("Cannot list fixture sources: " + names);
            for (String name : names.split("\u0000")) {
                if (!name.isEmpty()) copyFile(source.resolve(name), destination.resolve(name));
            }
        } else {
            copyArchive(source, destination);
        }
    }

    private static void copyArchive(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                Path relative = source.relativize(directory);
                if ((!directory.equals(source)
                        && List.of(".git", ".gradle", "build", "target").contains(directory.getFileName().toString()))
                        || relative.equals(Path.of("docs", "history"))
                        || relative.equals(Path.of("verification"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                copyFile(file, destination.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyFile(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
    }
}
