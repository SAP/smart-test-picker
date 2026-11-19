// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** The oracle is a normal Test execution, including skipped tests, not STP discovery. */
class GradleInventoryFiltersFunctionalTest {
    @TempDir Path temporary;
    private static final String INVENTORY = "generateGradleExecutableHeadTestInventory";
    private static final String MAPPING = "generateSmartTestMapping";

    static Stream<Arguments> selections() {
        return Stream.of("8.14", "9.5.1", "9.7.1").flatMap(version -> Stream.of(
                new String[] {"none", ""},
                new String[] {"class-files", "include '**/*Fast*'; exclude '**/*IT.class'"},
                new String[] {"qualified-class", "filter { includeTestsMatching 'example.FastTest' }"},
                new String[] {"qualified-method", "filter { includeTestsMatching 'example.FastTest.alpha' }"},
                new String[] {"wildcard", "filter { includeTestsMatching '*.alpha' }"},
                new String[] {"simple-class", "filter { includeTestsMatching 'FastTest' }"},
                new String[] {"exclude", "filter { excludeTestsMatching '*.beta' }"},
                new String[] {"include-exclude", "filter { includeTestsMatching '*Test'; excludeTestsMatching 'SlowTest' }"},
                new String[] {"include-tag", "useJUnitPlatform { includeTags 'fast' }"},
                new String[] {"exclude-tag", "useJUnitPlatform { excludeTags 'slow' }"},
                new String[] {"tag-expression", "useJUnitPlatform { includeTags 'fast & !slow' }"},
                new String[] {"include-engine", "useJUnitPlatform { includeEngines 'junit-vintage' }"},
                new String[] {"exclude-engine", "useJUnitPlatform { excludeEngines 'junit-vintage' }"},
                new String[] {"combined", "include '**/*Test.class'; exclude '**/Slow*'; filter { includeTestsMatching '*Test'; excludeTestsMatching '*.beta' }; useJUnitPlatform { includeTags 'fast & !slow'; excludeTags 'other'; includeEngines 'junit-jupiter'; excludeEngines 'junit-vintage' }"},
                new String[] {"shapes", "filter { includeTestsMatching 'ShapesTest*' }"},
                new String[] {"two-tasks", ""}
        ).map(row -> Arguments.of(version, row[0], row[1])));
    }

    @ParameterizedTest(name = "{0} {1}") @MethodSource("selections")
    void inventoryEqualsNormalExecution(String version, String scenario, String selection) throws Exception {
        boolean two = scenario.equals("two-tasks");
        Path root = fixture(selection, two, false);
        run(root, version, false, two ? List.of("test", "integrationTest") : List.of("test"));
        Set<String> observed = reported(root, "test");
        if (two) observed.addAll(reported(root, "integrationTest"));
        assertFalse(observed.isEmpty(), "The execution oracle must observe tests");
        run(root, version, false, List.of(INVENTORY));
        Set<ExecutableTestIdentity> identities = inventory(root);
        Set<String> discovered = identities.stream().map(id -> id.target() + "::" + id.test().className()
                + "#" + id.test().methodName()).collect(Collectors.toCollection(TreeSet::new));
        System.out.println("DIFFERENTIAL " + version + " " + scenario + " observed=" + observed + " inventory=" + discovered);
        assertEquals(observed, discovered);
        if (scenario.equals("shapes")) {
            assertTrue(identities.stream().map(Object::toString).collect(Collectors.toSet()).containsAll(Set.of(
                    "gradle::test::example.ShapesTest#overloaded(int)",
                    "gradle::test::example.ShapesTest#overloaded(java.lang.String)",
                    "gradle::test::example.ShapesTest#disabled",
                    "gradle::test::example.ShapesTest$NestedGroup#nested")));
        }
        if (two) {
            Set<String> first = identities.stream().filter(id -> id.target().toString().equals("gradle::test"))
                    .map(id -> id.test().toString()).collect(Collectors.toSet());
            Set<String> second = identities.stream().filter(id -> id.target().toString().equals("gradle::integrationTest"))
                    .map(id -> id.test().toString()).collect(Collectors.toSet());
            assertFalse(first.isEmpty()); assertFalse(second.isEmpty());
            assertTrue(java.util.Collections.disjoint(first, second));
        }
    }

    @Test void nonPlatformTaskFailsWithoutPartialInventory() throws Exception {
        Path root = fixture("", false, true);
        BuildResult result = run(root, "8.14", true, List.of(INVENTORY));
        assertTrue(result.getOutput().contains(":test"));
        assertTrue(result.getOutput().contains("JUnit Platform"));
        assertFalse(Files.exists(root.resolve("build/executable-head-test-inventory.json")));
        assertFalse(Files.exists(root.resolve("build/stp/executable-project-inventory.json")));
    }

    @ParameterizedTest(name = "unloadable test class: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"8.14", "9.5.1", "9.7.1"})
    void unloadableTestClassFailsWithoutPartialInventory(String version) throws Exception {
        Path root = fixture("filter { includeTestsMatching 'example.FastTest' }", false, false);
        Files.writeString(root.resolve("build.gradle"),
                "\ntasks.named('test') { classpath = configurations.testRuntimeClasspath }\n",
                java.nio.file.StandardOpenOption.APPEND);
        git(root, "add", "build.gradle");
        git(root, "commit", "-qm", "unloadable test layout");
        write(root, "revision.txt", git(root, "rev-parse", "HEAD").trim());

        BuildResult normal = run(root, version, true, List.of("test"));
        StringBuilder reports = new StringBuilder(normal.getOutput());
        try (var files = Files.list(root.resolve("build/test-results/test"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".xml")).toList()) {
                reports.append(Files.readString(file));
            }
        }
        assertTrue(reports.toString().contains("ClassNotFoundException: example.FastTest"), reports.toString());
        BuildResult result = run(root, version, true, List.of(INVENTORY));
        assertTrue(result.getOutput().contains("Gradle inventory :test cannot load test class example.FastTest"),
                result.getOutput());
        assertFalse(Files.exists(root.resolve("build/executable-head-test-inventory.json")));
        assertFalse(Files.exists(root.resolve("build/stp/executable-project-inventory.json")));
        assertFalse(Files.exists(root.resolve("test-body-ran")));
    }

    static Stream<Arguments> drift() {
        return Stream.of(
                Arguments.of("tag", "useJUnitPlatform { excludeTags 'fast' }", "example.FastTest#alpha"),
                Arguments.of("exclude", "filter { excludeTestsMatching '*.alpha' }", "example.FastTest#alpha"),
                Arguments.of("include", "filter { includeTestsMatching '*.alpha' }", "example.FastTest#beta"),
                Arguments.of("class-file", "exclude '**/FastTest.class'", "example.FastTest#alpha"),
                Arguments.of("engine", "useJUnitPlatform { includeEngines 'junit-vintage' }", "example.FastTest#alpha"));
    }

    @ParameterizedTest(name = "assignment drift: {0}") @MethodSource("drift")
    void excludedAssignmentFailsBeforeTests(String name, String selection, String identity) throws Exception {
        Path root = fixture(selection, false, false);
        assignment(root, Set.of("gradle::test::" + identity));
        BuildResult result = run(root, "8.14", true, List.of(MAPPING, "-Dstp.executableInventoryDiscovery=false"));
        assertTrue(result.getOutput().contains(":test"), result.getOutput());
        assertTrue(result.getOutput().contains(identity), result.getOutput());
        assertFalse(Files.exists(root.resolve("test-body-ran")), "No assigned or sibling test may start");
        assertFalse(Files.exists(root.resolve("build/stp/executable-fragment.json")));
    }

    @Test void assignmentOnlyNarrowsOriginalSelection() throws Exception {
        Path root = fixture("filter { includeTestsMatching 'FastTest' }; useJUnitPlatform { includeTags 'fast' }", false, false);
        assignment(root, Set.of("gradle::test::example.FastTest#alpha"));
        run(root, "8.14", false, List.of(MAPPING, "-Dstp.executableInventoryDiscovery=false"));
        assertEquals(Set.of("gradle::test::example.FastTest#alpha"), aggregate(root));
        assertEquals(Set.of("gradle::test::example.FastTest#alpha"), reported(root, "test"));
    }

    @Test void preflightScopeFailureRemovesPreviousAggregate() throws Exception {
        Path root = fixture("filter { includeTestsMatching 'example.FastTest.alpha' }", false, false);
        assignment(root, Set.of("gradle::test::example.FastTest#alpha"));
        run(root, "8.14", false, List.of(MAPPING, "-Dstp.executableInventoryDiscovery=false"));
        assertEquals(Set.of("gradle::test::example.FastTest#alpha"), aggregate(root));
        Files.writeString(root.resolve("build.gradle"),
                "\ntasks.named('test') { testClassesDirs = sourceSets.main.output.classesDirs }\n",
                java.nio.file.StandardOpenOption.APPEND);
        BuildResult result = run(root, "8.14", true, List.of(MAPPING, "-Dstp.executableInventoryDiscovery=false"));
        assertTrue(result.getOutput().contains(":test"), result.getOutput());
        assertTrue(result.getOutput().contains("production"), result.getOutput());
        assertFalse(Files.exists(root.resolve("build/stp/executable-fragment.json")),
                "A failed preflight must not leave the previously accepted aggregate");
    }

    @Test void buildExclusionsNeedNoExternalInventoryFiltering() throws Exception {
        Path root = fixture("filter { excludeTestsMatching 'example.FastIT'; excludeTestsMatching 'example.SlowTest' }; useJUnitPlatform { includeEngines 'junit-jupiter' }; exclude '**/ShapesTest*'", false, false);
        run(root, "8.14", false, List.of("test"));
        Set<String> expected = reported(root, "test");
        run(root, "8.14", false, List.of(INVENTORY));
        Set<String> identities = inventory(root).stream().map(Object::toString).collect(Collectors.toSet());
        assertEquals(expected, identities);
        assignment(root, identities);
        run(root, "8.14", false, List.of(MAPPING, "-Dstp.executableInventoryDiscovery=false"));
        assertEquals(expected, aggregate(root));
    }

    @Test void filteredInventoryAndMappingReuseConfigurationCache() throws Exception {
        Path root = fixture("filter { includeTestsMatching 'example.FastTest.alpha' }; useJUnitPlatform { includeTags 'fast' }", false, false);
        for (int i = 0; i < 2; i++) {
            BuildResult result = run(root, "8.14", false, List.of(INVENTORY, "--configuration-cache"));
            if (i == 1) assertTrue(result.getOutput().contains("Reusing configuration cache."));
            assertEquals(Set.of("gradle::test::example.FastTest#alpha"), inventory(root).stream().map(Object::toString).collect(Collectors.toSet()));
        }
        assignment(root, Set.of("gradle::test::example.FastTest#alpha"));
        for (int i = 0; i < 2; i++) {
            BuildResult result = run(root, "8.14", false, List.of(MAPPING, "-Dstp.executableInventoryDiscovery=false", "--configuration-cache"));
            if (i == 1) assertTrue(result.getOutput().contains("Reusing configuration cache."));
            assertEquals(Set.of("gradle::test::example.FastTest#alpha"), aggregate(root));
        }
    }

    private Path fixture(String selection, boolean two, boolean legacy) throws Exception {
        Path root = temporary.resolve("fixture");
        write(root, "settings.gradle", "rootProject.name='inventory-selection'\n");
        write(root, ".gitignore", "build/\n.gradle/\nassignment.json\nrevision.txt\ntest-body-ran\n");
        write(root, "build.gradle", """
                plugins { id 'java'; id 'com.sap.oss.smart-test-picker' }
                repositories { mavenCentral() }
                dependencies {
                  testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
                  testImplementation 'junit:junit:4.13.2'
                  testRuntimeOnly 'org.junit.vintage:junit-vintage-engine:5.9.3'
                  testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.9.3'
                  stpAgent files('%s')
                }
                test { %s; %s }
                %s
                smartTestPicker {
                  runtimeSchemaVersion = 3
                  executableInventoryDiscovery = providers.systemProperty('stp.executableInventoryDiscovery').orElse('true').map { Boolean.parseBoolean(it) }
                  revision = providers.fileContents(layout.projectDirectory.file('revision.txt')).asText.map { it.trim() }
                  shardId = 'local'; executableAssignmentFile = 'assignment.json'
                  mappingTestTasks = %s
                }
                """.formatted(System.getProperty("stp.test.agent.jar").replace('\\', '/'),
                legacy ? "useJUnit()" : "useJUnitPlatform()", selection,
                two ? "test { useJUnitPlatform { excludeTags 'slow' } }; tasks.register('integrationTest', Test) { testClassesDirs = sourceSets.test.output.classesDirs; classpath = sourceSets.test.runtimeClasspath; useJUnitPlatform { includeTags 'slow' } }" : "",
                two ? "[':test', ':integrationTest']" : "[':test']"));
        write(root, "src/main/java/example/Value.java", "package example; public class Value { public static int get() { return 1; } }");
        write(root, "src/test/java/example/Probe.java", """
                package example;
                class Probe { static void hit() throws Exception {
                  java.nio.file.Files.writeString(java.nio.file.Path.of("test-body-ran"), "yes");
                  org.junit.jupiter.api.Assertions.assertEquals(1, Value.get());
                } }
                """);
        for (String type : List.of("FastTest", "FastIT", "SlowTest")) {
            write(root, "src/test/java/example/" + type + ".java", """
                    package example;
                    import org.junit.jupiter.api.*;
                    @Tag("%s") class %s {
                      @Test void alpha() throws Exception { Probe.hit(); }
                      @Test void beta() throws Exception { Probe.hit(); }
                    }
                    """.formatted(type.equals("SlowTest") ? "slow" : "fast", type));
        }
        write(root, "src/test/java/example/LegacyTest.java", "package example; public class LegacyTest { @org.junit.Test public void legacy() throws Exception { Probe.hit(); } }");
        write(root, "src/test/java/example/ShapesTest.java", """
                package example;
                import org.junit.jupiter.api.*;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;
                @Tag("fast") class ShapesTest {
                  @Test void ordinary() throws Exception { Probe.hit(); }
                  @Disabled @Test void disabled() { throw new AssertionError("disabled test executed"); }
                  @ParameterizedTest(name="overloaded(int)") @ValueSource(ints={1,2})
                  void overloaded(int value) throws Exception { Assertions.assertTrue(value > 0); Probe.hit(); }
                  @ParameterizedTest(name="overloaded(java.lang.String)") @ValueSource(strings={"a","b"})
                  void overloaded(String value) throws Exception { Assertions.assertFalse(value.isEmpty()); Probe.hit(); }
                  @Nested class NestedGroup { @Test void nested() throws Exception { Probe.hit(); } }
                  static class InnerTest { @Test void inner() throws Exception { Probe.hit(); } }
                }
                """);
        git(root, "init", "-q"); git(root, "config", "user.name", "Fixture");
        git(root, "config", "user.email", "fixture@example.invalid");
        git(root, "add", "."); git(root, "commit", "-qm", "fixture");
        write(root, "revision.txt", git(root, "rev-parse", "HEAD").trim());
        return root;
    }

    private BuildResult run(Path root, String version, boolean failure, List<String> arguments) {
        List<String> args = new ArrayList<>(arguments); args.addAll(List.of("--stacktrace", "--max-workers=2"));
        GradleRunner runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withGradleVersion(version).withArguments(args);
        BuildResult result = failure ? runner.buildAndFail() : runner.build();
        System.out.println("RUN " + version + " " + args + " " + result.getTasks());
        return result;
    }
    private Set<ExecutableTestIdentity> inventory(Path root) throws Exception {
        return new ExecutableHeadTestInventoryCodec().read(root.resolve("build/executable-head-test-inventory.json").toFile()).runnableTests();
    }
    private Set<String> aggregate(Path root) throws Exception {
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(root.resolve("build/stp/executable-fragment.json")));
        Set<String> all = fragment.tests().keySet().stream().map(Object::toString).collect(Collectors.toCollection(TreeSet::new));
        fragment.unmapped().forEach(test -> all.add(test.test().toString()));
        return all;
    }
    private Set<String> reported(Path root, String task) throws Exception {
        Set<String> result = new TreeSet<>();
        try (var reports = Files.list(root.resolve("build/test-results/" + task))) {
            for (Path report : reports.filter(p -> p.getFileName().toString().endsWith(".xml")).toList()) {
                var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var document = factory.newDocumentBuilder().parse(report.toFile());
                var tests = document.getElementsByTagName("testcase");
                for (int i = 0; i < tests.getLength(); i++) {
                    var test = (org.w3c.dom.Element) tests.item(i);
                    String method = test.getAttribute("name").split("\\(", 2)[0];
                    result.add("gradle::" + task + "::" + test.getAttribute("classname") + "#" + method);
                }
            }
        }
        return result;
    }
    private void assignment(Path root, Set<String> tests) throws Exception {
        write(root, "assignment.json", "{\"version\":1,\"revision\":\"" + Files.readString(root.resolve("revision.txt"))
                + "\",\"shardId\":\"local\",\"tests\":" + new com.google.gson.Gson().toJson(tests) + "}");
    }
    private void write(Path root, String name, String text) throws Exception {
        Path file = root.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }
    private String git(Path root, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output;
    }
}
