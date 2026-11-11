// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import static com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.CollectorCapabilities;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Observes the same small probes as GradleCollectorBackendFunctionalTest through real mapping. */
class CollectorCapabilitiesIntegrationTest {
    private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:"
            + System.getProperty("smartTestPicker.version") + ":";

    @ParameterizedTest @ValueSource(strings = {"ASM", "JACOCO"})
    void capabilitiesMatchCollectedOutput(String collector, @TempDir Path temp) throws Exception {
        Path fixture = copyFixture("schema-v3-reactor", temp.resolve("reactor")).toRealPath();
        Path module = fixture.resolve("module-b");
        for (String kind : List.of("main", "test")) {
            Path destination = Files.createDirectories(module.resolve("src/" + kind + "/java/example"));
            try (var files = Files.list(Path.of("src/test/fixtures/capability-probes", kind))) {
                for (Path file : files.toList()) Files.copy(file, destination.resolve(file.getFileName()));
            }
        }
        Path saved = Files.createDirectories(Path.of("build/stp-capabilities-01/" + collector.toLowerCase()));
        Result installed = maven(fixture, "install", "-DskipTests", "-Djacoco.skip=true");
        save(saved, "install.log", installed);
        assertEquals(0, installed.exitCode(), installed.output());
        Files.writeString(fixture.resolve("assignment.json"), """
            {"version":1,"revision":"reactor-revision","shardId":"reactor-shard",
             "tests":["maven:module-b::example.CapabilitiesTest#probes"]}
            """);
        Result collected = run(fixture, collector, PLUGIN + "prepare-reactor-executable-mapping", "test");
        save(saved, "collect.log", collected);
        assertEquals(0, collected.exitCode(), collected.output());
        // Capture the collector output before any report or fragment-assembly mojo runs.
        retain(module.resolve("target/stp"), saved.resolve("collected/stp"));
        retain(module.resolve("target/jacoco"), saved.resolve("collected/jacoco"));
        retain(module.resolve("target/surefire-reports"), saved.resolve("test-results"));
        boolean direct = false;
        // Never read a previous test run's retained evidence when deriving a capability.
        try (var files = Files.walk(module.resolve("target/stp"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (json.has("tests") && json.has("schemaVersion") && json.get("schemaVersion").getAsString().equals("2")) direct = true;
            }
        }
        Path stp = module.resolve("target/stp");
        Result finalized = run(fixture, collector, "-pl", "module-b", PLUGIN + "generate-reports",
                PLUGIN + "generate-coverage-fragment", "-DsmartTestPicker.executionTarget=maven:module-b",
                "-DsmartTestPicker.moduleFragmentOutput=" + stp.resolve("coverage-fragment-v3.json"),
                "-DsmartTestPicker.moduleEvidenceOutput=" + stp.resolve("execution-evidence-v2.json"),
                "-DsmartTestPicker.moduleDiagnosticOutput=" + stp.resolve("asm-agent-diagnostic.json"));
        save(saved, "finalize.log", finalized);
        assertEquals(0, finalized.exitCode(), finalized.output());
        retain(module.resolve("target/jacoco-xml"), saved.resolve("reports"));
        retain(stp, saved.resolve("finalized"));
        Path fragment = stp.resolve("coverage-fragment-v3.json");
        new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment));
        var json = JsonParser.parseString(Files.readString(fragment)).getAsJsonObject();
        assertTrue(json.getAsJsonObject("collection").get("completed").getAsBoolean());
        assertEquals(0, json.getAsJsonArray("unmapped").size());
        assertEquals(1, json.getAsJsonObject("tests").size());
        var leaf = json.getAsJsonObject("tests").getAsJsonObject("maven:module-b::example.CapabilitiesTest#probes");
        assertNotNull(leaf);
        assertEquals("PASS", leaf.get("outcome").getAsString());
        String methods = leaf.getAsJsonArray("methods").toString();
        assertTrue(methods.contains("example.OverloadedService#value"), methods);
        assertFalse(leaf.getAsJsonArray("classes").toString().contains("example.SetupOnly"));
        boolean setup = json.getAsJsonArray("setupScopes").toString().contains("example.SetupOnly");
        if (setup) assertTrue(json.getAsJsonArray("setupScopes").toString().contains("example.CapabilitiesTest"));
        CollectorCapabilities observed = new CollectorCapabilities(
                leaf.getAsJsonArray("classes").toString().contains("example.OverloadedService"),
                methods.contains("example.ImplicitException#length"), setup,
                methods.contains("example.OverloadedService#value(Ljava/lang/String;)Ljava/lang/String;")
                        && !methods.contains("example.OverloadedService#value(I)I"), direct);
        Files.writeString(saved.resolve("observed.txt"), observed + "\n");
        System.out.println("Maven " + collector + " observed: " + observed);
        assertEquals(observed, MavenCoverageCollectorBackend.valueOf(collector).capabilities());
    }

    private static Result run(Path fixture, String collector, String... goals) throws Exception {
        var args = new ArrayList<>(List.of(goals));
        args.addAll(List.of("-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.coverageCollector=" + collector,
                "-Djacoco.skip=" + collector.equals("ASM"),
                "-DsmartTestPicker.testsFile=" + fixture.resolve("assignment.json")));
        return maven(fixture, args.toArray(String[]::new));
    }

    private static void retain(Path source, Path destination) throws Exception {
        if (!Files.exists(source)) return;
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path output = destination.resolve(source.relativize(file));
                Files.createDirectories(output.getParent());
                Files.copy(file, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void save(Path saved, String name, Result result) throws Exception {
        Files.writeString(saved.resolve(name), "exitCode=" + result.exitCode() + "\n" + result.output());
    }
}
