// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

class StpVersionConsistencyFunctionalTest {
    @TempDir Path temporary;

    @Test
    void checksCurrentVersionsWithoutPython() throws Exception {
        Path root = fixture();
        var result = runner(root).build();
        assertEquals(SUCCESS, result.task(":verifyStpVersionConsistency").getOutcome());
        assertFalse(Files.exists(root.resolve("python-invoked")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.sap.oss.smart-test-picker:smart-test-picker-core:0.0.0-stale-doc",
            "id 'com.sap.oss.smart-test-picker' version '0.0.0-stale-doc'",
            "smart-test-picker-cli-0.0.0-SNAPSHOT.jar",
            "<groupId>com.sap.oss.smart-test-picker</groupId><artifactId>smart-test-picker-core</artifactId><version>0.0.0-stale-doc</version>",
            "com.sap.oss.smart-test-picker:smart-test-picker-cli:jar:0.0.0-stale-doc"
    })
    void rejectsStaleDocumentationWithoutPython(String example) throws Exception {
        Path root = fixture();
        Files.writeString(root.resolve("README.md"), Files.readString(root.resolve("README.md")) + "\n" + example + "\n");
        var result = runner(root).buildAndFail();
        assertTrue(result.getOutput().contains("README.md"), result.getOutput());
        assertTrue(result.getOutput().contains("0.0.0"), result.getOutput());
        assertTrue(result.getOutput().contains("STP version consistency failed"), result.getOutput());
        assertFalse(Files.exists(root.resolve("python-invoked")));
    }

    @Test
    void historicalVersionsRemainAllowedWithoutPython() throws Exception {
        Path root = fixture();
        Path history = root.resolve("docs/history/version-fixture.md");
        Files.writeString(history, "com.sap.oss.smart-test-picker:smart-test-picker-core:0.0.0-historical\n");
        Files.writeString(root.resolve("CHANGELOG.md"), Files.readString(root.resolve("CHANGELOG.md"))
                + "\n## [0.0.0] - 2000-01-01\ncom.sap.oss.smart-test-picker:smart-test-picker-core:0.0.0-historical\n");
        git(root, "add", "docs/history/version-fixture.md");
        assertEquals(SUCCESS, runner(root).build().task(":verifyStpVersionConsistency").getOutcome());
        assertFalse(Files.exists(root.resolve("python-invoked")));
    }

    @Test
    void stagingOverrideDoesNotChangeDocumentationVersionWithoutPython() throws Exception {
        Path root = fixture();
        assertEquals(SUCCESS, runner(root).withArguments("verifyStpVersionConsistency", "-PstpVersion=0.0.0-staging",
                "--console=plain", "--stacktrace").build().task(":verifyStpVersionConsistency").getOutcome());
        assertFalse(Files.exists(root.resolve("python-invoked")));
    }

    private Path fixture() throws Exception {
        Path source = Path.of(System.getProperty("stp.test.source.root"));
        Path root = Files.createDirectories(temporary.resolve("project"));
        for (String name : git(source, "ls-files", "-z").split("\u0000")) {
            if (name.isEmpty()) continue;
            Path destination = root.resolve(name);
            Files.createDirectories(destination.getParent());
            Files.copy(source.resolve(name), destination);
        }
        git(root, "init", "-q");
        git(root, "config", "commit.gpgsign", "false");
        git(root, "config", "tag.gpgsign", "false");
        git(root, "add", ".");
        Path commands = Files.createDirectories(root.resolve("no-python"));
        for (String name : List.of("python", "python3")) {
            Path command = commands.resolve(name);
            Files.writeString(command, "#!/bin/sh\n: > '" + root.resolve("python-invoked")
                    + "'\necho 'Python must not be invoked by the version check' >&2\nexit 97\n");
            assertTrue(command.toFile().setExecutable(true));
        }
        return root;
    }

    private GradleRunner runner(Path root) {
        var environment = new HashMap<>(System.getenv());
        environment.put("PYTHON", root.resolve("no-python/python3").toString());
        environment.put("PATH", root.resolve("no-python") + File.pathSeparator + environment.getOrDefault("PATH", ""));
        return GradleRunner.create().withProjectDir(root.toFile()).withEnvironment(environment)
                .withArguments("verifyStpVersionConsistency", "--console=plain", "--stacktrace");
    }

    private static String git(Path root, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
