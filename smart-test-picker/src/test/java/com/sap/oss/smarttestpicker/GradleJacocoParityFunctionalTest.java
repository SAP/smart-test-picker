// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.gradle.testkit.runner.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import static org.junit.jupiter.api.Assertions.*;

/** Real Test-task observations, including project-owned execution data and reports. */
class GradleJacocoParityFunctionalTest {
    @TempDir Path temporary;
    private static final String MAPPING = "generateSmartTestMapping";
    private static final String CONVERSION = ":generateStpJacocoExecutableFragment_test";

    @Test void asmPreservesItsFragmentAndProjectJacocoResults() throws Exception {
        Path root = fixture("coexist", "ASM", false, "");
        run(root, false, "test", "jacocoTestReport", "-Dfixture.mapping=false", "--rerun-tasks");
        var expectedReport = counters(root.resolve("build/reports/jacoco/test/jacocoTestReport.xml"));
        var expectedExec = execution(root.resolve("build/project.exec"), root.resolve("build/classes/java/main"));
        run(root, false, MAPPING, "-Dfixture.jacoco=false");
        byte[] expectedFragment = Files.readAllBytes(fragment(root, "ASM"));
        Files.delete(root.resolve("build/project.exec"));
        var coexist = run(root, false, MAPPING, "jacocoTestReport");
        assertArrayEquals(expectedFragment, Files.readAllBytes(fragment(root, "ASM")));
        assertEquals(expectedReport, counters(root.resolve("build/reports/jacoco/test/jacocoTestReport.xml")));
        assertEquals(expectedExec, execution(root.resolve("build/project.exec"), root.resolve("build/classes/java/main")));
        assertFalse(coexist.getOutput().contains("does not match"), coexist.getOutput());
    }

    @ParameterizedTest @CsvSource({"ASM,test", "JACOCO,test", "JACOCO,conversion"})
    void failedExecutionCannotLeaveAnAcceptedAggregate(String collector, String failure) throws Exception {
        Path root = fixture("stale", collector, false, "");
        run(root, false, MAPPING);
        assertTrue(Files.exists(aggregate(root)));
        var result = run(root, true, MAPPING, "-Dfixture.failure=" + failure);
        assertEquals(TaskOutcome.FAILED, result.task(failure.equals("test") ? ":test" : CONVERSION).getOutcome());
        assertFalse(Files.exists(aggregate(root)), result.getOutput());
    }

    @ParameterizedTest @CsvSource({"ASM,forkEvery,1", "ASM,maxParallelForks,2", "JACOCO,forkEvery,1", "JACOCO,maxParallelForks,2"})
    void lateForkMutationRejectsMapping(String collector, String setting, int value) throws Exception {
        Path root = fixture("late-forks", collector, false,
                "test.doFirst { if (System.getProperty('fixture.mutate') == 'true') " + setting + " = " + value + " }\n");
        run(root, false, MAPPING);
        var failed = run(root, true, MAPPING, "-Dfixture.mutate=true");
        assertTrue(failed.getOutput().contains(":test"), failed.getOutput());
        assertTrue(failed.getOutput().contains(setting + "=" + value), failed.getOutput());
        assertFalse(Files.exists(aggregate(root)));
    }

    @Test void jacocoMappingAndConversionExecuteAgainWithBuildCache() throws Exception {
        Path root = fixture("fresh", "JACOCO", false, "");
        run(root, false, MAPPING, "--build-cache");
        byte[] first = Files.readAllBytes(aggregate(root));
        var second = run(root, false, MAPPING, "--build-cache");
        assertEquals(TaskOutcome.SUCCESS, second.task(":test").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, second.task(CONVERSION).getOutcome());
        assertArrayEquals(first, Files.readAllBytes(aggregate(root)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void jacocoIncludesProjectDependencyProductionButNotTestHelpers(boolean jar) throws Exception {
        Path root = fixture("cross-module", "JACOCO", true, "");
        run(root, false, MAPPING, "-Dfixture.jar=" + jar);
        var value = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(aggregate(root)));
        assertEquals(2, value.tests().size());
        for (var coverage : value.tests().values()) {
            assertTrue(coverage.coveredClasses().contains("example.library.Library"));
            assertTrue(coverage.coveredMethods().stream().anyMatch(method -> method.toString().equals("example.library.Library#value()I")));
            assertFalse(coverage.coveredClasses().contains("example.Helper"));
        }
    }

    @ParameterizedTest @CsvSource({"false,true", "true,true", "true,false"})
    void projectExecutionFileRemainsUntouched(boolean present, boolean append) throws Exception {
        Path root = fixture("isolated", "JACOCO", false, "");
        Path projectData = root.resolve("build/project.exec");
        byte[] original = "project-owned execution data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (present) { Files.createDirectories(projectData.getParent()); Files.write(projectData, original); }
        run(root, false, MAPPING, "-Dfixture.append=" + append);
        if (present) assertArrayEquals(original, Files.readAllBytes(projectData));
        else assertFalse(Files.exists(projectData));
        assertTrue(Files.isRegularFile(root.resolve("build/stp/jacoco/_test/local/exec/task.exec")));
        var mapped = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(aggregate(root)));
        assertEquals(2, mapped.tests().size());
        assertTrue(mapped.tests().values().stream().allMatch(test -> test.coveredClasses().contains("example.Service")));
    }

    @Test void projectReportsAndChecksAreSkippedOnlyDuringJacocoMapping() throws Exception {
        Path root = fixture("consumers", "JACOCO", false, "");
        var result = run(root, false, MAPPING, "jacocoTestReport", "jacocoTestCoverageVerification");
        for (String name : List.of("jacocoTestReport", "jacocoTestCoverageVerification")) {
            assertEquals(TaskOutcome.SKIPPED, result.task(":" + name).getOutcome());
            assertTrue(result.getOutput().lines().anyMatch(line -> line.contains(name)
                    && line.toLowerCase(Locale.ROOT).contains("skip") && line.contains("STP")), result.getOutput());
        }
        assertFalse(Files.exists(root.resolve("build/reports/jacoco/test/jacocoTestReport.xml")));
        var ordinary = run(root, false, "test", "jacocoTestReport", "jacocoTestCoverageVerification", "-Dfixture.mapping=false", "--rerun-tasks");
        assertEquals(TaskOutcome.SUCCESS, ordinary.task(":jacocoTestReport").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, ordinary.task(":jacocoTestCoverageVerification").getOutcome());
        assertTrue(Files.isRegularFile(root.resolve("build/project.exec")));
    }

    @Test void missingActiveAgentFailsClearly() throws Exception {
        Path root = fixture("no-agent", "JACOCO", false, "");
        var failed = run(root, true, MAPPING, "-Dfixture.jacoco=false");
        String output = failed.getOutput();
        try (var reports = Files.walk(root.resolve("build/test-results/test"))) {
            for (Path report : reports.filter(path -> path.toString().endsWith(".xml")).toList()) output += Files.readString(report);
        }
        assertTrue(output.contains("Active JaCoCo runtime is unavailable"), output);
        assertFalse(Files.exists(aggregate(root)));
    }

    private Path fixture(String name, String collector, boolean dependency, String early) throws Exception {
        Path root = temporary.resolve(name);
        write(root, "settings.gradle", "rootProject.name='collector-parity'\n" + (dependency ? "include 'library'\n" : ""));
        write(root, ".gitignore", "build/\n.gradle/\nrevision.txt\nassignment.json\n");
        // Apply STP only after this project's early doFirst registration, to exercise late mutation.
        write(root, "build.gradle", """
            plugins { id 'java'; id 'jacoco'; id 'com.sap.oss.smart-test-picker' apply false }
            repositories { mavenCentral() }
            dependencies {
              testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
              testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
            }
            jacoco { toolVersion = '0.8.13' }
            test {
              useJUnitPlatform()
              systemProperty 'fixture.failure', System.getProperty('fixture.failure', '')
              jacoco { destinationFile = file('build/project.exec'); includes = ['example.*']; enabled = System.getProperty('fixture.jacoco', 'true') == 'true' }
            }
            %s
            if (System.getProperty('fixture.append') == 'false') {
              def originalProviders = new ArrayList(test.jvmArgumentProviders)
              test.jvmArgumentProviders.clear()
              originalProviders.each { original ->
                test.jvmArgumentProviders.add({ original.asArguments().collect { it.replace('append=true', 'append=false') } } as org.gradle.process.CommandLineArgumentProvider)
              }
            }
            apply plugin: 'com.sap.oss.smart-test-picker'
            dependencies { stpAgent files('%s'); stpJacocoCollector files('%s') }
            smartTestPicker {
              runtimeSchemaVersion = System.getProperty('fixture.mapping', 'true') == 'true' ? 3 : 2
              coverageCollector = '%s'
              revision = providers.fileContents(layout.projectDirectory.file('revision.txt')).asText.map { it.trim() }
              shardId = 'local'; executableAssignmentFile = 'assignment.json'; mappingTestTasks = [':test']
            }
            jacocoTestReport { reports { xml.required = true } }
            jacocoTestCoverageVerification { violationRules { rule { limit { minimum = 0.1 } } } }
            test.doLast {
              if (System.getProperty('fixture.failure') == 'conversion') {
                fileTree('build/stp/jacoco').matching { include '**/session_*.exec' }.each { it.bytes = [1, 2, 3] as byte[] }
              }
            }
            %s
            """.formatted(early, System.getProperty("stp.test.agent.jar").replace('\\', '/'),
                System.getProperty("stp.test.core.jar").replace('\\', '/'), collector, dependency ? """
            project(':library') { apply plugin: 'java' }
            dependencies {
              if (System.getProperty('fixture.jar') == 'true') implementation files(project(':library').tasks.named('jar'))
              else implementation project(':library')
            }
            """ : ""));
        write(root, "src/main/java/example/Service.java", """
            package example;
            public class Service {
              public static int value() { return %s; }
              public static int unused() { return 9; }
            }
            """.formatted(dependency ? "example.library.Library.value()" : "7"));
        if (dependency) write(root, "library/src/main/java/example/library/Library.java",
                "package example.library; public class Library { public static int value() { return 7; } }");
        write(root, "src/test/java/example/Helper.java", "package example; class Helper { static int value() { return Service.value(); } }");
        write(root, "src/test/java/example/ParityTest.java", """
            package example;
            class ParityTest {
              @org.junit.jupiter.api.Test void first() {
                org.junit.jupiter.api.Assertions.assertEquals(7, Helper.value());
                org.junit.jupiter.api.Assertions.assertNotEquals("test", System.getProperty("fixture.failure"));
              }
              @org.junit.jupiter.api.Test void second() { org.junit.jupiter.api.Assertions.assertEquals(7, Helper.value()); }
            }
            """);
        git(root, "init", "-q"); git(root, "config", "user.name", "Fixture"); git(root, "config", "user.email", "fixture@example.invalid");
        git(root, "config", "commit.gpgsign", "false"); git(root, "config", "tag.gpgsign", "false");
        git(root, "add", "."); git(root, "commit", "-qm", "fixture");
        String revision = git(root, "rev-parse", "HEAD").trim();
        write(root, "revision.txt", revision);
        write(root, "assignment.json", "{\"version\":1,\"revision\":\"" + revision + "\",\"shardId\":\"local\",\"tests\":["
                + "\"gradle::test::example.ParityTest#first\",\"gradle::test::example.ParityTest#second\"]}");
        return root;
    }

    private BuildResult run(Path root, boolean fail, String... arguments) {
        var args = new ArrayList<>(List.of(arguments)); args.addAll(List.of("--stacktrace", "--max-workers=2"));
        var runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(args);
        var result = fail ? runner.buildAndFail() : runner.build();
        System.out.println("PARITY " + root.getFileName() + " " + args);
        result.getTasks().forEach(task -> System.out.println(task.getPath() + " " + task.getOutcome()));
        return result;
    }
    private static Path aggregate(Path root) { return root.resolve("build/stp/executable-fragment.json"); }
    private static Path fragment(Path root, String collector) {
        return root.resolve("build/stp/" + (collector.equals("ASM") ? "coverage" : "jacoco") + "/_test/local/fragment.json");
    }
    private static void write(Path root, String relative, String text) throws Exception {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, text);
    }
    private static String git(Path root, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git")); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()); assertEquals(0, process.waitFor(), output); return output;
    }
    private static Map<String,String> counters(Path report) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var document = factory.newDocumentBuilder().parse(report.toFile());
        var result = new TreeMap<String,String>();
        var classes = document.getElementsByTagName("class");
        for (int i = 0; i < classes.getLength(); i++) {
            var type = (org.w3c.dom.Element) classes.item(i);
            var values = type.getElementsByTagName("counter");
            for (int j = 0; j < values.getLength(); j++) {
                var counter = (org.w3c.dom.Element) values.item(j);
                var parent = (org.w3c.dom.Element) counter.getParentNode();
                result.put(type.getAttribute("name") + "/" + parent.getTagName() + "/" + parent.getAttribute("name")
                        + parent.getAttribute("desc") + "/" + counter.getAttribute("type"),
                        counter.getAttribute("missed") + ":" + counter.getAttribute("covered"));
            }
        }
        assertFalse(result.isEmpty()); return result;
    }
    private static Map<String,String> execution(Path exec, Path classes) throws Exception {
        var loader = new ExecFileLoader(); loader.load(exec.toFile());
        var coverage = new CoverageBuilder(); new Analyzer(loader.getExecutionDataStore(), coverage).analyzeAll(classes.toFile());
        assertTrue(coverage.getNoMatchClasses().isEmpty());
        var result = new TreeMap<String,String>();
        for (var data : loader.getExecutionDataStore().getContents()) result.put(data.getName(), data.getId() + ":" + Arrays.toString(data.getProbes()));
        assertFalse(result.isEmpty()); return result;
    }
}
