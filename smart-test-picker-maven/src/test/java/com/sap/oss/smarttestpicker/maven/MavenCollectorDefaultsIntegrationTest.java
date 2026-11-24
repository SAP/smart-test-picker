// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import static com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Exercises collector defaults through real Maven preparation, finalization and aggregation. */
class MavenCollectorDefaultsIntegrationTest {
    private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:"
            + System.getProperty("smartTestPicker.version") + ":";
    @TempDir Path temporary;

    @ParameterizedTest
    @CsvSource({"unset,unset", "unset,ASM", "unset,JACOCO", "ASM,unset", "ASM,ASM", "ASM,JACOCO",
            "JACOCO,unset", "JACOCO,ASM", "JACOCO,JACOCO", "unset,blank"})
    void executableMappingHonorsDefaultAndFrozenCollector(String configured, String manifest) throws Exception {
        Path root = fixture();
        String effective = configured.equals("unset") ? "ASM" : configured;
        var arguments = mappingArguments(root);
        if (!configured.equals("unset")) arguments.add("-DsmartTestPicker.coverageCollector=" + configured);
        if (effective.equals("ASM")) arguments.add("-Djacoco.skip=true");
        var result = run(root, manifest, arguments);
        boolean mismatch = !Set.of("unset", "blank", effective).contains(manifest);
        if (mismatch) {
            assertNotEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("Mapping collector mismatch: manifest=" + manifest
                    + ", effective=" + effective), result.output());
            assertFalse(Files.exists(root.resolve("module-b/target/surefire-reports")));
            assertFalse(Files.exists(root.resolve("target/aggregate.json")));
        } else {
            assertMapping(root, result, effective);
        }
    }

    @ParameterizedTest
    @CsvSource({"project,ASM", "project,JACOCO", "jvm,ASM", "jvm,JACOCO"})
    void explicitProjectAndJvmSelectionsKeepTheirBehavior(String source, String collector) throws Exception {
        Path root = fixture();
        if (source.equals("project")) {
            Path pom = root.resolve("pom.xml");
            Files.writeString(pom, Files.readString(pom).replace("<properties>",
                    "<properties><smartTestPicker.coverageCollector>" + collector + "</smartTestPicker.coverageCollector>"));
        } else {
            Files.createDirectories(root.resolve(".mvn"));
            Files.writeString(root.resolve(".mvn/jvm.config"), "-DsmartTestPicker.coverageCollector=" + collector + "\n");
        }
        var arguments = mappingArguments(root);
        if (collector.equals("ASM")) arguments.add("-Djacoco.skip=true");
        assertMapping(root, run(root, "unset", arguments), collector);
    }

    @Test void implicitAsmDoesNotInstallJacocoIsolation() throws Exception {
        Path root = fixture();
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("</executions><configuration><destFile>",
                "<execution><id>project-report</id><phase>verify</phase><goals><goal>report</goal></goals></execution>"
                        + "</executions><configuration><destFile>"));
        var result = run(root, "unset", mappingArguments(root));
        assertMapping(root, result, "ASM");
        assertTrue(Files.size(root.resolve("module-b/target/jacoco/test.exec")) > 0);
        assertFalse(result.output().contains("[STP] Skipping"), result.output());
    }

    @Test void explicitJacocoIsolatesProjectDataAndSkipsConsumers() throws Exception {
        Path root = fixture();
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("</executions><configuration><destFile>",
                "<execution><id>project-report</id><phase>verify</phase><goals><goal>report</goal></goals></execution>"
                        + "</executions><configuration><destFile>"));
        var arguments = mappingArguments(root);
        arguments.add("-DsmartTestPicker.coverageCollector=JACOCO");
        var result = run(root, "unset", arguments);
        assertMapping(root, result, "JACOCO");
        assertFalse(Files.exists(root.resolve("module-b/target/jacoco/test.exec")));
        assertTrue(result.output().contains("jacoco:report (project-report): per-test mapping resets"), result.output());
    }

    @Test void standaloneFinalizationAndConversionDefaultToAsmForSchemaThree() throws Exception {
        Path root = fixture();
        var arguments = mappingArguments(root);
        arguments.addAll(List.of("-DsmartTestPicker.coverageCollector=ASM", "-Djacoco.skip=true"));
        assertMapping(root, run(root, "unset", arguments), "ASM");
        Path stp = root.resolve("module-b/target/stp");
        byte[] fragment = Files.readAllBytes(stp.resolve("coverage-fragment-v3.json"));
        var result = run(root, "unset", List.of("-pl", "module-b", PLUGIN + "generate-reports",
                PLUGIN + "generate-coverage-fragment", "-DsmartTestPicker.schemaVersion=3",
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"),
                "-DsmartTestPicker.executionTarget=maven:module-b",
                "-DsmartTestPicker.moduleFragmentOutput=" + stp.resolve("coverage-fragment-v3.json"),
                "-DsmartTestPicker.moduleEvidenceOutput=" + stp.resolve("execution-evidence-v2.json"),
                "-DsmartTestPicker.moduleDiagnosticOutput=" + stp.resolve("asm-agent-diagnostic.json")));
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("ASM mapping does not require JaCoCo XML reports"), result.output());
        assertArrayEquals(fragment, Files.readAllBytes(stp.resolve("coverage-fragment-v3.json")));
    }

    @Test void legacyMappingWithoutSelectionStillUsesJacoco() throws Exception {
        Path root = copyFixture("schema-v2", temporary.resolve("legacy")).toRealPath();
        assertEquals(0, maven(root, "validate").exitCode());
        var result = run(root, "unset", List.of("verify"));
        assertEquals(0, result.exitCode(), result.output());
        var fragment = new CoverageFragmentCodec().deserialize(Files.readAllBytes(root.resolve("target/coverage-fragment-v2.json")));
        assertTrue(fragment.collectionCompleted());
        assertFalse(fragment.tests().isEmpty());
        assertTrue(Files.isDirectory(root.resolve("target/jacoco-xml")));
        assertFalse(result.output().contains("ASM mapping does not require JaCoCo XML reports"));
    }

    private Path fixture() throws Exception {
        Path root = copyFixture("schema-v3-reactor", temporary.resolve("reactor")).toRealPath();
        var prepared = maven(root, "install", "-DskipTests", "-Djacoco.skip=true");
        assertEquals(0, prepared.exitCode(), prepared.output());
        Files.writeString(root.resolve("assignment.json"), """
                {"version":1,"revision":"reactor-revision","shardId":"reactor-shard",
                 "tests":["maven:module-b::b.BTests#b2"]}
                """);
        return root;
    }

    private static ArrayList<String> mappingArguments(Path root) {
        return new ArrayList<>(List.of(PLUGIN + "prepare-reactor-executable-mapping", "verify",
                PLUGIN + "aggregate-reactor-coverage-fragment", "-DsmartTestPicker.schemaVersion=3",
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"),
                "-DsmartTestPicker.fragmentOutput=" + root.resolve("target/aggregate.json"),
                "-DsmartTestPicker.evidenceOutput=" + root.resolve("target/evidence.json")));
    }

    private static void assertMapping(Path root, Result result, String collector) throws Exception {
        assertEquals(0, result.exitCode(), result.output());
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(root.resolve("target/aggregate.json")));
        var test = ExecutableTestIdentity.parse("maven:module-b::b.BTests#b2");
        assertEquals(Set.of(test), fragment.tests().keySet());
        assertTrue(fragment.collectionCompleted());
        assertTrue(fragment.unmapped().isEmpty());
        assertEquals(Set.of("a.AValue", "b.BValue"), fragment.tests().get(test).coveredClasses());
        Path diagnostic = root.resolve("module-b/target/stp/asm-agent-diagnostic.json");
        if (collector.equals("ASM")) {
            assertEquals("agent-shell-1", JsonParser.parseString(Files.readString(diagnostic))
                    .getAsJsonObject().get("schemaVersion").getAsString());
            assertTrue(result.output().contains("ASM mapping does not require JaCoCo XML reports"), result.output());
            assertFalse(Files.exists(root.resolve("module-b/target/jacoco-xml")));
        } else {
            assertFalse(Files.exists(diagnostic));
            assertTrue(Files.isDirectory(root.resolve("module-b/target/jacoco-xml")));
        }
    }

    private static Result run(Path root, String manifest, List<String> arguments) throws Exception {
        var command = new ArrayList<>(List.of("mvn", "-U", "--batch-mode", "--no-transfer-progress",
                "--settings", root.resolve("functional-test-settings.xml").toString()));
        command.addAll(arguments);
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().remove("STP_MAPPING_COLLECTOR");
        if (!manifest.equals("unset")) builder.environment().put("STP_MAPPING_COLLECTOR", manifest.equals("blank") ? " " : manifest);
        Path log = root.resolve("invocation.log");
        var process = builder.redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "Maven collector fixture timed out");
        return new Result(process.exitValue(), Files.readString(log));
    }
}
