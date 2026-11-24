// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compares live Gradle conventions with Maven's published parameter defaults and goal behavior. */
class PluginDefaultParityTest {
    // Maven cannot collect legacy ASM fragments; adding that feature is not a default change.
    private static final Map<String, String> EXCEPTIONS = Map.of("legacyCollector",
            "Maven schema 1/2 mapping supports only JaCoCo; Gradle standalone mapping also supports ASM.");

    @Test void sharedDefaultsStayAligned(@TempDir Path temporary) throws Exception {
        var project = ProjectBuilder.builder().withProjectDir(temporary.resolve("gradle").toFile()).build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply("com.sap.oss.smart-test-picker");
        var defaults = project.getExtensions().getByType(SmartTestPickerExtension.class);
        Path repository = Path.of(System.getProperty("stp.test.maven.repository"));
        String version = System.getProperty("stp.test.version");
        Path localRepository = Path.of("build/default-parity-maven-local").toAbsolutePath();
        Path jar = localRepository.resolve("com/sap/oss/smart-test-picker/smart-test-picker-maven/" + version
                + "/smart-test-picker-maven-" + version + ".jar");
        var expected = Map.of("baseBranch", defaults.getBaseBranch().get(),
                "maxCommitDistance", defaults.getMaxCommitDistance().get().toString(),
                "classLevelSelection", defaults.getClassLevelSelection().get().toString(),
                "fullSuiteTriggers", String.join(",", defaults.getFullSuiteTriggers().get()),
                "schemaVersion", defaults.getRuntimeSchemaVersion().get().toString());
        // Collector defaults are schema-dependent, not a literal @Parameter default. Observe the goal.
        Path consumer = Files.createDirectories(temporary.resolve("maven"));
        Files.writeString(consumer.resolve("settings.xml"), "<settings/>");
        Files.writeString(consumer.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>
                  <artifactId>defaults</artifactId><version>1</version>
                  <repositories><repository><id>stp-local</id><url>%s</url></repository></repositories>
                  <pluginRepositories><pluginRepository><id>stp-local</id><url>%s</url></pluginRepository></pluginRepositories>
                </project>
                """.formatted(repository.toUri(), repository.toUri()));
        for (int schema : new int[] {3, 2}) {
            var builder = new ProcessBuilder("mvn", "--batch-mode", "--no-transfer-progress", "-U",
                    "--settings", consumer.resolve("settings.xml").toString(),
                    "-Dmaven.repo.local=" + localRepository,
                    "com.sap.oss.smart-test-picker:smart-test-picker-maven:" + version + ":generate-reports",
                    "-DsmartTestPicker.schemaVersion=" + schema).directory(consumer.toFile()).redirectErrorStream(true);
            builder.environment().remove("STP_MAPPING_COLLECTOR");
            Path log = consumer.resolve("schema-" + schema + ".log");
            var process = builder.redirectOutput(log.toFile()).start();
            assertTrue(process.waitFor(2, TimeUnit.MINUTES), "Maven default probe timed out");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            boolean asm = output.contains("ASM mapping does not require JaCoCo XML reports");
            if (schema == 3) assertEquals(defaults.getCoverageCollector().get().equals("ASM"), asm, output);
            else {
                assertEquals(Set.of("legacyCollector"), EXCEPTIONS.keySet());
                assertFalse(asm, EXCEPTIONS.get("legacyCollector") + "\n" + output);
                assertTrue(output.contains("Exec directory not found"), output);
            }
        }

        // Maven resolves timestamped SNAPSHOT publications into its local repository.
        // Inspect that exact resolved plugin, not an optional unversioned alias in the publication repository.
        Set<String> checked = new TreeSet<>();
        try (var archive = new JarFile(jar.toFile());
                var stream = archive.getInputStream(archive.getJarEntry("META-INF/maven/plugin.xml"))) {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
            var xpath = XPathFactory.newInstance().newXPath();
            var mojos = document.getElementsByTagName("mojo");
            for (int index = 0; index < mojos.getLength(); index++) {
                var mojo = mojos.item(index);
                for (var entry : expected.entrySet()) {
                    var node = xpath.evaluateExpression("configuration/" + entry.getKey(), mojo,
                            org.w3c.dom.Node.class);
                    if (node == null) continue;
                    var value = node.getAttributes().getNamedItem("default-value");
                    assertEquals(entry.getValue(), value == null ? "" : value.getNodeValue(),
                            xpath.evaluate("goal", mojo) + ": " + entry.getKey());
                    checked.add(entry.getKey());
                }
            }
        }
        assertEquals(expected.keySet(), checked, "Every shared parameter must be checked, not silently omitted");
    }
}
