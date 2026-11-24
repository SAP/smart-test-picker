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
import com.google.gson.JsonParser;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Cold reactors must compile before deriving scope, without weakening the existing safety gates. */
class MavenColdStartMappingIntegrationTest {
    private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:"
            + System.getProperty("smartTestPicker.version") + ":";
    private static final String TEST = "maven:module-b::b.BTests#b2";
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void coldMappingDerivesScopeAfterCompilation(boolean explicitAsm) throws Exception {
        Path root = fixture(false);
        var args = arguments(root);
        if (explicitAsm) args.add("-DsmartTestPicker.coverageCollector=ASM");
        var result = maven(root, args.toArray(String[]::new));
        assertEquals(0, result.exitCode(), result.output());
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(aggregate(root)));
        assertTrue(fragment.collectionCompleted());
        assertEquals(Set.of(ExecutableTestIdentity.parse(TEST)), fragment.tests().keySet());
        assertTrue(fragment.unmapped().isEmpty());
        assertEquals(Set.of("a.AValue", "b.BValue"), fragment.tests().get(ExecutableTestIdentity.parse(TEST)).coveredClasses());
        var diagnostic = JsonParser.parseString(Files.readString(diagnostic(root))).getAsJsonObject();
        assertTrue(diagnostic.getAsJsonObject("metrics").get("classesTransformed").getAsInt() > 0);
        assertTrue(diagnostic.getAsJsonObject("configuration").getAsJsonArray("includes").size() > 0);
        assertTrue(result.output().contains("ASM mapping does not require JaCoCo XML reports"), result.output());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void emptyColdAssignmentDoesNotRequireCompiledClasses(boolean explicitAsm) throws Exception {
        Path root = fixture(true);
        var args = new ArrayList<>(List.of(PLUGIN + "prepare-reactor-executable-mapping",
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"),
                "-DsmartTestPicker.fragmentOutput=" + aggregate(root)));
        if (explicitAsm) args.add("-DsmartTestPicker.coverageCollector=ASM");
        var result = maven(root, args.toArray(String[]::new));
        assertEquals(0, result.exitCode(), result.output());
        assertFalse(Files.exists(aggregate(root)));
        assertNoTestExecution(root);
        assertFalse(Files.exists(root.resolve("module-b/target/classes")));
        assertFalse(Files.exists(diagnostic(root)));
    }

    @ParameterizedTest @ValueSource(strings = {"identical", "default-package"})
    void structuralScopeErrorsStillRejectBeforeTests(String fault) throws Exception {
        Path root = fixture(false);
        if (fault.equals("identical")) {
            Path pom = root.resolve("module-b/pom.xml");
            Files.writeString(pom, Files.readString(pom).replace("</project>",
                    "<build><testOutputDirectory>${project.build.outputDirectory}</testOutputDirectory></build></project>"));
        } else {
            Files.writeString(root.resolve("module-b/src/main/java/DefaultProduction.java"),
                    "public class DefaultProduction { public static int value() { return 1; } }\n");
        }
        var args = arguments(root);
        args.add("-DsmartTestPicker.coverageCollector=ASM");
        var result = maven(root, args.toArray(String[]::new));
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(fault.equals("identical")
                ? "identical production/test output directories" : "default-package production class"), result.output());
        assertNoTestExecution(root);
        assertFalse(Files.exists(aggregate(root)));
    }

    @ParameterizedTest @ValueSource(strings = {"wrong.prefix.", "a.,b."})
    void zeroInstrumentationRejectsAfterTestsWithoutAcceptingAggregate(String includes) throws Exception {
        Path root = fixture(false);
        var args = arguments(root);
        args.addAll(List.of("-DsmartTestPicker.coverageCollector=ASM", "-DsmartTestPicker.asm.includes=" + includes));
        if (includes.equals("a.,b.")) args.add("-DsmartTestPicker.asm.excludes=a.,b.");
        var result = maven(root, args.toArray(String[]::new));
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("classesTransformed=0"), result.output());
        assertEquals(0, JsonParser.parseString(Files.readString(diagnostic(root))).getAsJsonObject()
                .getAsJsonObject("metrics").get("classesTransformed").getAsInt());
        assertTrue(Files.readString(root.resolve("module-b/target/surefire-reports/TEST-b.BTests.xml"))
                .contains("tests=\"1\""));
        assertFalse(Files.exists(aggregate(root)));
    }

    @ParameterizedTest @ValueSource(strings = {"fork", "manifest", "includes"})
    void earlyValidationStillPrecedesCompilation(String fault) throws Exception {
        Path root = fixture(false);
        var args = arguments(root);
        args.add("-DsmartTestPicker.coverageCollector=ASM");
        String message;
        switch (fault) {
            case "fork" -> { args.add("-DforkCount=2"); message = "forkCount"; }
            case "manifest" -> { args.add("-Denv.STP_MAPPING_COLLECTOR=JACOCO"); message = "Mapping collector mismatch"; }
            default -> { args.add("-DsmartTestPicker.asm.includes=bad*prefix"); message = "prefix"; }
        }
        var result = maven(root, args.toArray(String[]::new));
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(message), result.output());
        assertFalse(Files.exists(root.resolve("module-b/target/classes")));
        assertFalse(Files.exists(root.resolve("module-a/target/classes")));
        assertNoTestExecution(root);
        assertFalse(Files.exists(aggregate(root)));
    }

    private Path fixture(boolean empty) throws Exception {
        Path root = copyFixture("schema-v3-reactor", temporary.resolve("reactor")).toRealPath();
        assertFalse(Files.exists(root.resolve("module-a/target")));
        assertFalse(Files.exists(root.resolve("module-b/target")));
        Files.writeString(root.resolve("assignment.json"),
                "{\"version\":1,\"revision\":\"reactor-revision\",\"shardId\":\"reactor-shard\",\"tests\":["
                        + (empty ? "" : "\"" + TEST + "\"") + "]}");
        Files.createDirectories(aggregate(root).getParent());
        Files.writeString(aggregate(root), "previously accepted aggregate");
        return root;
    }

    private static ArrayList<String> arguments(Path root) {
        return new ArrayList<>(List.of(PLUGIN + "prepare-reactor-executable-mapping", "verify",
                PLUGIN + "aggregate-reactor-coverage-fragment", "-DsmartTestPicker.schemaVersion=3", "-Djacoco.skip=true",
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"),
                "-DsmartTestPicker.fragmentOutput=" + aggregate(root),
                "-DsmartTestPicker.evidenceOutput=" + root.resolve("target/evidence.json")));
    }

    private static Path aggregate(Path root) { return root.resolve("target/aggregate.json"); }
    private static Path diagnostic(Path root) { return root.resolve("module-b/target/stp/asm-agent-diagnostic.json"); }
    private static void assertNoTestExecution(Path root) {
        for (String module : List.of("module-a", "module-b", "module-zero")) {
            assertFalse(Files.exists(root.resolve(module + "/target/surefire-reports")));
            assertFalse(Files.exists(root.resolve(module + "/target/failsafe-reports")));
        }
    }
}
