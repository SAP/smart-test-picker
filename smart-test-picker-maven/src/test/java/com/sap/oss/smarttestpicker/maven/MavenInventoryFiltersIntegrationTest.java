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
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Ordinary provider execution, including skipped XML entries, is the selection oracle. */
class MavenInventoryFiltersIntegrationTest {
    private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:"
            + System.getProperty("smartTestPicker.version") + ":";
    private static final List<String> VERSIONS = List.of("3.2.5", "3.5.6", "3.6.1");
    @TempDir Path temporary;

    static Stream<Arguments> selections() {
        return VERSIONS.stream().flatMap(version -> Stream.of("surefire", "failsafe").flatMap(provider ->
                Stream.of("defaults", "plain", "regex", "negative", "files", "files-and-lists", "pom-test-class",
                        "pom-test-method", "pom-test-wildcard", "tags", "tag-expression", "include-engine",
                        "exclude-engine", "execution", "profile", "shapes", "combined")
                        .map(scenario -> Arguments.of(version, provider, scenario))));
    }

    @ParameterizedTest(name = "{0} {1} {2}") @MethodSource("selections")
    void inventoryEqualsOrdinaryProvider(String version, String provider, String scenario) throws Exception {
        Path root = fixture(version, provider, scenario, "");
        Result ordinary = invoke(root, "process-test-classes", goal(version, provider));
        save(version, provider, scenario, "ordinary.log", ordinary);
        assertEquals(0, ordinary.exitCode(), ordinary.output());
        Set<String> reported = reported(root, provider);
        assertFalse(reported.isEmpty(), "The ordinary execution oracle must observe at least one test");
        Result discovered = inventory(root, provider);
        save(version, provider, scenario, "inventory.log", discovered);
        assertEquals(0, discovered.exitCode(), discovered.output());
        var identities = identities(root);
        Set<String> actual = identities.stream().map(i -> i.test().className() + "#" + i.test().methodName())
                .collect(Collectors.toCollection(TreeSet::new));
        System.out.println("MAVEN_DIFFERENTIAL " + version + " " + provider + " " + scenario
                + " ordinary=" + reported + " inventory=" + actual);
        assertEquals(reported, actual);
        if (scenario.equals("shapes")) {
            assertTrue(identities.stream().map(i -> i.test().toString()).collect(Collectors.toSet()).containsAll(Set.of(
                    "example.ShapesTest#overloaded(int)", "example.ShapesTest#overloaded(java.lang.String)",
                    "example.ShapesTest#disabled", "example.ShapesTest$NestedGroup#nested")));
        }
    }

    @Test void twoProviderExecutionsHaveDifferentInventories() throws Exception {
        Path root = fixture("3.5.6", "surefire", "defaults", "");
        assertEquals(0, invoke(root, "process-test-classes", goal("3.5.6", "surefire"), goal("3.5.6", "failsafe")).exitCode());
        Set<String> first = reported(root, "surefire"), second = reported(root, "failsafe");
        assertFalse(first.isEmpty()); assertFalse(second.isEmpty());
        assertTrue(java.util.Collections.disjoint(first, second));
        for (String provider : List.of("surefire", "failsafe")) {
            Result run = inventory(root, provider);
            assertEquals(0, run.exitCode(), run.output());
            assertEquals(provider.equals("surefire") ? first : second, identities(root).stream()
                    .map(i -> i.test().className() + "#" + i.test().methodName()).collect(Collectors.toSet()));
            assertTrue(identities(root).stream().allMatch(i -> i.target().toString().equals(target(provider))));
        }
    }

    static Stream<Arguments> unsupported() {
        return VERSIONS.stream().flatMap(version -> Stream.of("surefire", "failsafe").flatMap(provider ->
                Stream.of("dependenciesToScan", "suiteXmlFiles", "legacy-provider", "cli-test")
                        .map(kind -> Arguments.of(version, provider, kind))));
    }

    static Stream<Arguments> olderProviders() {
        return Stream.of("2.22.2", "3.0.0", "3.1.2").flatMap(version ->
                Stream.of("surefire", "failsafe").map(provider -> Arguments.of(version, provider)));
    }

    @ParameterizedTest @MethodSource("olderProviders")
    void olderProviderDefaultsMatchOrRejectWithVersionContext(String version, String provider) throws Exception {
        Path root = fixture(version, provider, "defaults", "");
        Result ordinary = invoke(root, "process-test-classes", goal(version, provider));
        assertEquals(0, ordinary.exitCode(), ordinary.output());
        Set<String> expected = reported(root, provider);
        assertFalse(expected.isEmpty());
        Result discovery = inventory(root, provider);
        save(version, provider, "review-defaults", "inventory.log", discovery);
        if (discovery.exitCode() == 0) {
            assertEquals(expected, identities(root).stream().map(i -> i.test().className() + "#" + i.test().methodName())
                    .collect(Collectors.toSet()));
        } else {
            assertVersionContext(discovery, version, provider);
            assertFalse(Files.exists(root.resolve("inventory.json")));
        }
        String selected = provider.equals("surefire") ? "example.FastTest#alpha" : "example.ExampleIT#alpha";
        assignment(root, provider, Set.of(selected));
        Result prepared = mapping(root, version, provider, List.of());
        save(version, provider, "review-defaults", "mapping.log", prepared);
        if (prepared.exitCode() == 0) {
            var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment(root, provider)));
            assertEquals(Set.of(selected), decoded.tests().keySet().stream().map(i -> i.test().toString()).collect(Collectors.toSet()));
        } else {
            assertVersionContext(prepared, version, provider);
            assertFalse(Files.exists(fragment(root, provider)));
        }
        System.out.println("REVIEW_PROVIDER " + version + " " + provider + " ordinary=" + expected
                + " inventoryExit=" + discovery.exitCode() + " mappingExit=" + prepared.exitCode());
    }

    private static void assertVersionContext(Result result, String version, String provider) {
        for (String text : List.of("selection-fixture", provider, "fixture-run", "resolved version " + version,
                "qualified versions", "3.2.5", "3.5.6", "3.6.1")) {
            assertTrue(result.output().contains(text), "Missing compatibility context: " + text + "\n" + result.output());
        }
    }

    @ParameterizedTest @MethodSource("olderProviders")
    void olderProviderPreparationMatchesOrRejectsWithVersionContext(String version, String provider) throws Exception {
        Path root = fixture(version, provider, "defaults", "");
        Result ordinary = invoke(root, "process-test-classes", goal(version, provider));
        assertEquals(0, ordinary.exitCode(), ordinary.output());
        String selected = provider.equals("surefire") ? "example.FastTest#alpha" : "example.ExampleIT#alpha";
        assertTrue(reported(root, provider).contains(selected));
        assignment(root, provider, Set.of(selected));
        Result prepared = mapping(root, version, provider, List.of());
        save(version, provider, "review-preparation", "mapping.log", prepared);
        if (prepared.exitCode() == 0) {
            var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment(root, provider)));
            assertEquals(Set.of(selected), decoded.tests().keySet().stream().map(i -> i.test().toString()).collect(Collectors.toSet()));
        } else {
            assertVersionContext(prepared, version, provider);
            assertFalse(Files.exists(fragment(root, provider)));
        }
    }

    @ParameterizedTest @MethodSource("overloadedMappings")
    void providerOnlyEnginesRejectWithoutPartialInventory(String version, String provider) throws Exception {
        Path root = fixture(version, provider, "defaults", "");
        Path pom = root.resolve("pom.xml");
        String text = Files.readString(pom).replace("<artifactId>junit-jupiter</artifactId>", "<artifactId>junit-jupiter-api</artifactId>")
                .replace("<dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId><version>1.9.3</version><scope>test</scope></dependency>",
                        "<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-params</artifactId><version>5.9.3</version><scope>test</scope></dependency>")
                .replace("<dependency><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId><version>5.9.3</version><scope>test</scope></dependency>", "")
                .replace("<artifactId>maven-" + provider + "-plugin</artifactId>",
                        "<artifactId>maven-" + provider + "-plugin</artifactId><dependencies>"
                        + "<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-engine</artifactId><version>5.9.3</version></dependency>"
                        + "<dependency><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId><version>5.9.3</version></dependency></dependencies>");
        // Without a project launcher, these providers use their own Platform runtime.
        // Align the API and plugin-only engines so the ordinary-run control is valid.
        String junitVersion = version.equals("3.6.1") ? "5.14.4" : version.equals("3.5.6") ? "5.12.2" : "5.9.3";
        text = text.replace("<version>5.9.3</version>", "<version>" + junitVersion + "</version>");
        Files.writeString(pom, text);
        commit(root);
        Result ordinary = invoke(root, "process-test-classes", goal(version, provider));
        assertEquals(0, ordinary.exitCode(), ordinary.output());
        assertFalse(reported(root, provider).isEmpty());
        Result discovery = inventory(root, provider);
        save(version, provider, "review-plugin-engine", "inventory.log", discovery);
        assertNotEquals(0, discovery.exitCode(), discovery.output());
        for (String expected : List.of("selection-fixture", provider, "fixture-run", "JUnit Platform",
                "project test runtime", "provider-only engine")) {
            assertTrue(discovery.output().contains(expected), discovery.output());
        }
        assertFalse(Files.exists(root.resolve("inventory.json")));
    }

    @ParameterizedTest(name = "unsupported {0} {1} {2}") @MethodSource("unsupported")
    void unsupportedSelectionFailsWithoutPartialInventory(String version, String provider, String kind) throws Exception {
        String setting = switch (kind) {
            case "dependenciesToScan" -> "<dependenciesToScan><dependency>example:external-tests</dependency></dependenciesToScan>";
            case "suiteXmlFiles" -> "<suiteXmlFiles><suiteXmlFile>suite.xml</suiteXmlFile></suiteXmlFiles>";
            default -> "";
        };
        Path root = fixture(version, provider, "defaults", setting);
        if (kind.equals("legacy-provider")) {
            Path pom = root.resolve("pom.xml");
            Files.writeString(pom, Files.readString(pom).replace("<artifactId>maven-" + provider + "-plugin</artifactId>",
                    "<artifactId>maven-" + provider + "-plugin</artifactId><dependencies><dependency>"
                    + "<groupId>org.apache.maven.surefire</groupId><artifactId>surefire-junit4</artifactId><version>3.5.6</version>"
                    + "</dependency></dependencies>"));
            commit(root);
        }
        assertEquals(0, invoke(root, "process-test-classes").exitCode());
        Result result = inventory(root, provider, kind.equals("cli-test") ? List.of("-Dtest=FastTest") : List.of());
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("selection-fixture"), result.output());
        assertTrue(result.output().contains(provider), result.output());
        assertTrue(result.output().contains("fixture-run"), result.output());
        assertFalse(Files.exists(root.resolve("inventory.json")));
        assertFalse(Files.exists(root.resolve("test-body-ran")));
    }

    @ParameterizedTest @ValueSource(strings = {"includes", "excludes", "tags", "engines", "execution", "cli-test"})
    void outsideAssignmentFailsBeforeAnyTest(String exclusion) throws Exception {
        String configuration = switch (exclusion) {
            case "includes" -> includes("**/SlowTest.java");
            case "excludes" -> "<excludes><exclude>**/FastTest.java</exclude></excludes>";
            case "tags" -> "<excludedGroups>fast</excludedGroups>";
            case "engines" -> "<includeJUnit5Engines><engine>junit-vintage</engine></includeJUnit5Engines>";
            default -> "";
        };
        Path root = fixture("3.5.6", "surefire", exclusion.equals("execution") ? "execution" : "defaults", configuration);
        assertEquals(0, invoke(root, "process-test-classes").exitCode());
        assignment(root, "surefire", Set.of("example.FastTest#alpha"));
        Result result = mapping(root, "3.5.6", "surefire", exclusion.equals("cli-test") ? List.of("-Dtest=FastTest") : List.of());
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(target("surefire")), result.output());
        if (!exclusion.equals("cli-test")) assertTrue(result.output().contains("example.FastTest#alpha"), result.output());
        assertFalse(Files.exists(root.resolve("test-body-ran")));
        assertFalse(Files.exists(root.resolve("target/surefire-reports")));
    }

    static Stream<Arguments> exactMappings() {
        return VERSIONS.stream().flatMap(version -> Stream.of("surefire", "failsafe").flatMap(provider ->
                Stream.of("includes", "pom-test", "file", "execution").map(kind -> Arguments.of(version, provider, kind))));
    }

    @ParameterizedTest(name = "exact mapping {0} {1} {2}") @MethodSource("exactMappings")
    void preparedMappingRunsExactlyAssignment(String version, String provider, String kind) throws Exception {
        String configuration = switch (kind) {
            case "includes" -> includes("**/FastTest.java");
            case "pom-test" -> "<test>FastTest</test>";
            case "file" -> "<includesFile>${project.basedir}/includes.txt</includesFile>";
            default -> "";
        };
        Path root = fixture(version, provider, kind.equals("execution") ? "execution-mapping" : "defaults", configuration);
        assertEquals(0, invoke(root, "process-test-classes").exitCode());
        assignment(root, provider, Set.of("example.FastTest#alpha"));
        Result result = mapping(root, version, provider, List.of());
        save(version, provider, "mapping-" + kind, "mapping.log", result);
        assertEquals(0, result.exitCode(), result.output());
        assertEquals(Set.of("example.FastTest#alpha"), reported(root, provider));
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment(root, provider)));
        assertTrue(fragment.collectionCompleted()); assertTrue(fragment.unmapped().isEmpty());
        assertEquals(Set.of(target(provider) + "::example.FastTest#alpha"), fragment.tests().keySet().stream()
                .map(Object::toString).collect(Collectors.toSet()));
        var evidence = MavenMappingEvidence.read(fragment(root, provider).resolveSibling(
                "execution-evidence-v2-._" + provider + "_fixture-run.json"));
        assertEquals(fragment.tests().keySet(), evidence.executed());
    }

    @Test void explicitStpFilterOnlyNarrowsProviderSelection() throws Exception {
        Path root = fixture("3.5.6", "surefire", "defaults", includes("**/FastTest.java"));
        assertEquals(0, invoke(root, "process-test-classes").exitCode());
        Result result = inventory(root, "surefire", List.of("-DsmartTestPicker.testFilter=example.FastTest#alpha"));
        assertEquals(0, result.exitCode(), result.output());
        assertEquals(Set.of(target("surefire") + "::example.FastTest#alpha"), identities(root).stream()
                .map(Object::toString).collect(Collectors.toSet()));
    }

    static Stream<Arguments> overloadedMappings() {
        return VERSIONS.stream().flatMap(version -> Stream.of("surefire", "failsafe")
                .map(provider -> Arguments.of(version, provider)));
    }

    @ParameterizedTest @MethodSource("overloadedMappings")
    void preparedMappingDoesNotWidenAnOverloadedMethodAssignment(String version, String provider) throws Exception {
        Path root = fixture(version, provider, "shapes", "");
        assertEquals(0, invoke(root, "process-test-classes").exitCode());
        assignment(root, provider, Set.of("example.ShapesTest#overloaded(int)"));
        Result result = mapping(root, version, provider, List.of());
        assertNotEquals(0, result.exitCode(), result.output());
        for (String expected : List.of(target(provider), "overload", "example.ShapesTest#overloaded(int)",
                "example.ShapesTest#overloaded(java.lang.String)"))
            assertTrue(result.output().contains(expected), result.output());
        assertFalse(Files.exists(root.resolve("target/" + provider + "-reports")));
        assertFalse(Files.exists(fragment(root, provider)));
    }

    @ParameterizedTest @MethodSource("overloadedMappings")
    void preparedMappingAcceptsAllOverloads(String version, String provider) throws Exception {
        Path root = fixture(version, provider, "shapes", "");
        assertEquals(0, invoke(root, "process-test-classes").exitCode());
        Set<String> assigned = Set.of("example.ShapesTest#overloaded(int)", "example.ShapesTest#overloaded(java.lang.String)");
        assignment(root, provider, assigned);
        Result result = mapping(root, version, provider, List.of());
        assertEquals(0, result.exitCode(), result.output());
        var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment(root, provider)));
        assertTrue(decoded.collectionCompleted());
        assertTrue(decoded.unmapped().isEmpty());
        assertEquals(assigned, decoded.tests().keySet().stream().map(i -> i.test().toString()).collect(Collectors.toSet()));
        decoded.tests().values().forEach(coverage -> assertTrue(coverage.coveredClasses().contains("example.Service")));
    }

    @Test void automaticLegacyProviderFailsWithoutPartialInventory() throws Exception {
        Path root = temporary.resolve("legacy");
        write(root, "pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>selection-fixture</artifactId><version>1</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency></dependencies>
                  <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                    <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin></plugins></build>
                </project>
                """);
        write(root, "src/test/java/example/LegacyTest.java", "package example; public class LegacyTest { @org.junit.Test public void test() { } }\n");
        commit(root);
        assertEquals(0, invoke(root, "test-compile").exitCode());
        Result result = inventory(root, "surefire");
        assertNotEquals(0, result.exitCode(), result.output());
        for (String part : List.of("selection-fixture", "surefire", "fixture-run", "JUnit Platform"))
            assertTrue(result.output().contains(part), result.output());
        assertFalse(Files.exists(root.resolve("inventory.json")));
    }

    @Test void twoModuleBothProviderInventoriesProduceAcceptedReactorAggregate() throws Exception {
        Path root = copyFixture("schema-v3-reactor", temporary.resolve("reactor"));
        String revision = commit(root);
        assertEquals(0, maven(root, "-Pfailsafe", "test-compile", "-Djacoco.skip=true").exitCode());
        Set<ExecutableTestIdentity> all = new TreeSet<>();
        for (String provider : List.of("surefire", "failsafe")) {
            String execution = provider.equals("surefire") ? "default-test" : "fixture-it";
            Path inventory = root.resolve(provider + "-inventory.json");
            Result discovery = maven(root, "-Pfailsafe", "process-test-classes", PLUGIN + "generate-reactor-head-test-inventory",
                    "-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.prHeadRevision=" + revision,
                    "-DsmartTestPicker.executionType=" + provider, "-DsmartTestPicker.executionId=" + execution,
                    "-DsmartTestPicker.outputFile=" + inventory, "-Djacoco.skip=true");
            assertEquals(0, discovery.exitCode(), discovery.output());
            var tests = new ExecutableHeadTestInventoryCodec().read(inventory.toFile()).runnableTests();
            assertFalse(tests.isEmpty()); all.addAll(tests);
            Path assignment = root.resolve(provider + "-assignment.json");
            Files.write(assignment, new ExecutableShardAssignmentCodec().serialize(new ExecutableShardAssignment(1,
                    new CoverageMapRevision(revision), new ShardId("s"), tests)));
            Result mapping = maven(root, "-Pfailsafe", PLUGIN + "prepare-reactor-executable-mapping", "verify",
                    "-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.testsFile=" + assignment,
                    "-DsmartTestPicker.revision=" + revision, "-DsmartTestPicker.shardId=s",
                    "-DsmartTestPicker.executionType=" + provider, "-DsmartTestPicker.executionId=" + execution,
                    "-DsmartTestPicker.coverageCollector=ASM", "-Djacoco.skip=true", "-Dmaven.test.failure.ignore=true");
            assertEquals(0, mapping.exitCode(), mapping.output());
        }
        Path assignment = root.resolve("all-assignment.json"), aggregate = root.resolve("aggregate.json");
        Files.write(assignment, new ExecutableShardAssignmentCodec().serialize(new ExecutableShardAssignment(1,
                new CoverageMapRevision(revision), new ShardId("s"), all)));
        Result joined = maven(root, PLUGIN + "aggregate-reactor-coverage-fragment", "-DsmartTestPicker.schemaVersion=3",
                "-DsmartTestPicker.testsFile=" + assignment, "-DsmartTestPicker.revision=" + revision,
                "-DsmartTestPicker.shardId=s", "-DsmartTestPicker.fragmentOutput=" + aggregate,
                "-DsmartTestPicker.evidenceOutput=" + root.resolve("aggregate-evidence.json"));
        assertEquals(0, joined.exitCode(), joined.output());
        var decoded = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(aggregate));
        assertTrue(decoded.collectionCompleted());
        Set<ExecutableTestIdentity> accounted = new TreeSet<>(decoded.tests().keySet());
        decoded.unmapped().forEach(u -> accounted.add(u.test()));
        assertEquals(all, accounted);
        var proof = MavenMappingEvidence.read(root.resolve("aggregate-evidence.json"));
        Set<ExecutableTestIdentity> evidence = new TreeSet<>(proof.executed()); evidence.addAll(proof.nonExecuted());
        assertEquals(all, evidence);
    }

    @Test void originalSurefireFixtureInventoryEqualsNineOrdinaryIdentities() throws Exception {
        Path root = copyFixture("schema-v3-reactor", temporary.resolve("reactor"));
        String revision = commit(root);
        Result normal = maven(root, "test", "-Djacoco.skip=true", "-Dmaven.test.failure.ignore=true");
        assertEquals(0, normal.exitCode(), normal.output());
        Set<String> observed = new TreeSet<>();
        for (String module : List.of("module-a", "module-b")) for (String name : reported(root.resolve(module), "surefire"))
            observed.add("maven:" + module + "::" + name);
        assertEquals(9, observed.size());
        Result result = maven(root, PLUGIN + "generate-reactor-head-test-inventory", "-DsmartTestPicker.schemaVersion=3",
                "-DsmartTestPicker.prHeadRevision=" + revision);
        assertEquals(0, result.exitCode(), result.output());
        var actual = new ExecutableHeadTestInventoryCodec().read(root.resolve(".stp/head-test-inventory.json").toFile());
        assertEquals(observed, actual.runnableTests().stream().map(i -> i.target() + "::" + i.test().className()
                + "#" + i.test().methodName()).collect(Collectors.toSet()));
        assertFalse(actual.runnableTests().stream().anyMatch(i -> i.test().className().equals("a.AValueIT")));
    }

    @Test void originalFailsafeFilterCannotIntroduceClassSourceOnlyTest() throws Exception {
        Path root = fixture("3.5.6", "failsafe", "defaults", "");
        write(root, "src/test/java/a/ClassSourceOnlyTest.java", """
                package a;
                @org.junit.runner.RunWith(ClassSourceOnlyTest.RuleRunner.class)
                public class ClassSourceOnlyTest {
                  public static class RuleRunner extends org.junit.runner.Runner {
                    private final org.junit.runner.Description root;
                    public RuleRunner(Class<?> type) {
                      root = org.junit.runner.Description.createSuiteDescription(type);
                      root.addChild(org.junit.runner.Description.createTestDescription(type, "architecture_rule"));
                    }
                    public org.junit.runner.Description getDescription() { return root; }
                    public void run(org.junit.runner.notification.RunNotifier notifier) {
                      for (var child : root.getChildren()) { notifier.fireTestStarted(child); notifier.fireTestFinished(child); }
                    }
                  }
                }
                """);
        commit(root);
        Result normal = invoke(root, "process-test-classes", goal("3.5.6", "failsafe"));
        assertEquals(0, normal.exitCode(), normal.output());
        assertFalse(reported(root, "failsafe").contains("a.ClassSourceOnlyTest#architecture_rule"));
        Result result = inventory(root, "failsafe", List.of("-DsmartTestPicker.testFilter=a.ClassSourceOnlyTest"));
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(identities(root).isEmpty());
    }

    @Test void originalFailsafeOutcomeAssignmentIsRejectedBeforeTests() throws Exception {
        Path root = copyFixture("schema-v3-reactor", temporary.resolve("reactor"));
        // Keep the old provider configuration. The assigned classes are real, compiled classes.
        write(root, "module-b/src/test/java/b/OutcomeTests.java", """
                package b;
                public class OutcomeTests {
                  @org.junit.jupiter.api.Test void plain() { }
                  @org.junit.jupiter.api.Test void failure() { }
                  @org.junit.jupiter.api.RepeatedTest(2) void mixed(org.junit.jupiter.api.RepetitionInfo repetition) { }
                  @org.junit.jupiter.api.Disabled @org.junit.jupiter.api.Test void disabled() { }
                  @org.junit.jupiter.api.Test void aborted() { }
                }
                """);
        write(root, "module-b/src/test/java/b/DisabledTests.java", """
                package b;
                @org.junit.jupiter.api.Disabled public class DisabledTests {
                  @org.junit.jupiter.api.Test void disabledClass() { }
                }
                """);
        String revision = commit(root);
        assertEquals(0, maven(root, "-Pfailsafe", "test-compile", "-Djacoco.skip=true").exitCode());
        var tests = Stream.of("b.BTests#b2", "shared.SharedTest#same", "b.OutcomeTests#plain", "b.OutcomeTests#failure",
                "b.OutcomeTests#mixed(org.junit.jupiter.api.RepetitionInfo)", "b.OutcomeTests#disabled", "b.OutcomeTests#aborted",
                "b.DisabledTests#disabledClass").map(s -> ExecutableTestIdentity.parse("maven:module-b@failsafe@fixture-it::" + s))
                .collect(Collectors.toSet());
        Files.write(root.resolve("assignment.json"), new ExecutableShardAssignmentCodec().serialize(
                new ExecutableShardAssignment(1, new CoverageMapRevision(revision), new ShardId("s"), tests)));
        Result run = maven(root, "-Pfailsafe", PLUGIN + "prepare-reactor-executable-mapping", "verify",
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"), "-DsmartTestPicker.revision=" + revision,
                "-DsmartTestPicker.shardId=s", "-DsmartTestPicker.executionType=failsafe", "-DsmartTestPicker.executionId=fixture-it",
                "-DsmartTestPicker.coverageCollector=ASM", "-Djacoco.skip=true");
        assertNotEquals(0, run.exitCode(), run.output());
        for (var test : tests) assertTrue(run.output().contains(test.test().toString()), run.output());
        assertTrue(run.output().contains("maven:module-b@failsafe@fixture-it"), run.output());
        for (String module : List.of("module-a", "module-b")) {
            assertFalse(Files.exists(root.resolve(module + "/target/surefire-reports")));
            assertFalse(Files.exists(root.resolve(module + "/target/failsafe-reports")));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"surefire", "failsafe"})
    void originalMappingFixtureSelectionRejectsExcludedAssignedClasses(String provider) throws Exception {
        Path root = copyFixture("schema-v3-reactor", temporary.resolve("reactor"));
        if (provider.equals("surefire")) {
            Path pom = root.resolve("pom.xml");
            Files.writeString(pom, Files.readString(pom).replace("<includesFile>${surefire.includesFile}</includesFile>",
                    includes("**/*Test.java") + "<includesFile>${surefire.includesFile}</includesFile>"));
        }
        String revision = commit(root);
        assertEquals(0, maven(root, "-Pfailsafe", "test-compile", "-Djacoco.skip=true").exitCode());
        String qualifier = provider.equals("failsafe") ? "@failsafe@fixture-it" : "";
        Set<ExecutableTestIdentity> tests = new TreeSet<>();
        tests.add(ExecutableTestIdentity.parse("maven:module-a" + qualifier + "::a.ATests#a1"));
        if (provider.equals("failsafe"))
            tests.add(ExecutableTestIdentity.parse("maven:module-b" + qualifier + "::b.BTests#b2"));
        Files.write(root.resolve("assignment.json"), new ExecutableShardAssignmentCodec().serialize(
                new ExecutableShardAssignment(1, new CoverageMapRevision(revision), new ShardId("s"), tests)));
        List<String> args = new ArrayList<>(List.of("-Pfailsafe", PLUGIN + "prepare-reactor-executable-mapping", "verify",
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"), "-DsmartTestPicker.revision=" + revision,
                "-DsmartTestPicker.shardId=s", "-DsmartTestPicker.coverageCollector=ASM", "-Djacoco.skip=true"));
        if (provider.equals("failsafe")) args.addAll(List.of("-DsmartTestPicker.executionType=failsafe",
                "-DsmartTestPicker.executionId=fixture-it"));
        Result run = maven(root, args.toArray(String[]::new));
        assertNotEquals(0, run.exitCode(), run.output());
        for (var test : tests) {
            assertTrue(run.output().contains(test.target().toString()), run.output());
            assertTrue(run.output().contains(test.test().toString()), run.output());
        }
        for (String module : List.of("module-a", "module-b")) {
            assertFalse(Files.exists(root.resolve(module + "/target/surefire-reports")));
            assertFalse(Files.exists(root.resolve(module + "/target/failsafe-reports")));
        }
    }

    @Test void naturalContainerFailuresKeepHealthyCoverage() throws Exception {
        Path root = fixture("3.5.6", "surefire", "defaults", "");
        for (String kind : List.of("Setup", "Initialization")) {
            String setup = kind.equals("Setup")
                    ? "@org.junit.jupiter.api.BeforeAll static void setup() { throw new IllegalStateException(\"setup failure\"); }"
                    : "static { if (Boolean.parseBoolean(\"true\")) throw new IllegalStateException(\"initialization failure\"); }";
            write(root, "src/test/java/example/" + kind + "FailureTest.java", "package example; public class "
                    + kind + "FailureTest { " + setup + " @org.junit.jupiter.api.Test void observed() { Service.value(); } }\n");
        }
        commit(root);
        assignment(root, "surefire", Set.of("example.FastTest#alpha", "example.SetupFailureTest#observed",
                "example.InitializationFailureTest#observed"));
        Result compiled = invoke(root, "test-compile");
        assertEquals(0, compiled.exitCode(), compiled.output());
        Result run = mapping(root, "3.5.6", "surefire", List.of("-Dmaven.test.failure.ignore=true"));
        assertEquals(0, run.exitCode(), run.output());
        var value = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment(root, "surefire")));
        assertTrue(value.collectionCompleted());
        assertEquals(1, value.tests().size());
        assertEquals(2, value.unmapped().size());
        Path retained = Path.of("build/absorption-natural"); Files.createDirectories(retained);
        Files.copy(fragment(root, "surefire"), retained.resolve("fragment.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(retained.resolve("build.log"), run.output());
    }


    private Path fixture(String version, String provider, String scenario, String extra) throws Exception {
        Path root = temporary.resolve("project");
        String selection = switch (scenario) {
            case "plain" -> includes("**/*Test.java") + "<excludes><exclude>**/SlowTest.java</exclude></excludes>";
            case "regex" -> includes("%regex[example/.*Test.class]") + "<excludes><exclude>%regex[.*Slow.*]</exclude></excludes>";
            case "negative" -> includes("**/*Test.java,!**/SlowTest.java");
            case "files" -> "<includesFile>${project.basedir}/includes.txt</includesFile><excludesFile>${project.basedir}/excludes.txt</excludesFile>";
            case "files-and-lists" -> includes("**/SlowTest.java") + "<includesFile>${project.basedir}/includes.txt</includesFile>"
                    + "<excludes><exclude>**/VintageTest.java</exclude></excludes><excludesFile>${project.basedir}/excludes.txt</excludesFile>";
            case "pom-test-class" -> "<test>FastTest</test>";
            case "pom-test-method" -> "<test>FastTest#alpha</test>";
            case "pom-test-wildcard" -> "<test>*Test#alpha*</test>";
            case "tags" -> includes("**/*Test.java") + "<groups>fast</groups><excludedGroups>slow</excludedGroups>";
            case "tag-expression" -> includes("**/*Test.java") + "<groups>fast &amp; !slow</groups>";
            case "include-engine" -> includes("**/*Test.java") + "<includeJUnit5Engines><engine>junit-vintage</engine></includeJUnit5Engines>";
            case "exclude-engine" -> includes("**/*Test.java") + "<excludeJUnit5Engines><engine>junit-vintage</engine></excludeJUnit5Engines>";
            case "execution" -> includes("**/FastTest.java");
            case "shapes" -> includes("**/ShapesTest.java");
            case "combined" -> includes("%regex[example/.*Test.class]") + "<includesFile>${project.basedir}/includes.txt</includesFile>"
                    + "<excludesFile>${project.basedir}/excludes.txt</excludesFile><groups>fast &amp; !slow</groups>"
                    + "<excludeJUnit5Engines><engine>junit-vintage</engine></excludeJUnit5Engines>";
            default -> "";
        };
        String executions = scenario.equals("execution") ? includes("**/SlowTest.java")
                : scenario.equals("execution-mapping") ? includes("**/FastTest.java") : "";
        StringBuilder plugins = new StringBuilder();
        for (String current : List.of("surefire", "failsafe")) plugins.append("""
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-%s-plugin</artifactId><version>%s</version>
                  <configuration><failIfNoTests>false</failIfNoTests>%s</configuration>
                  <executions><execution><id>fixture-run</id><configuration>%s</configuration></execution></executions>
                </plugin>
                """.formatted(current, version, current.equals(provider) ? selection + extra : "", current.equals(provider) ? executions : ""));
        String profile = scenario.equals("profile") ? "<profiles><profile><id>selected</id><activation><activeByDefault>true</activeByDefault></activation>"
                + "<properties><selected.test>**/SlowTest.java</selected.test></properties><build><plugins><plugin>"
                + "<artifactId>maven-" + provider + "-plugin</artifactId><configuration>" + includes("${selected.test}")
                + "</configuration></plugin></plugins></build></profile></profiles>" : "";
        write(root, "pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>selection-fixture</artifactId><version>1</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <dependencies>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.9.3</version><scope>test</scope></dependency>
                    <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId><version>1.9.3</version><scope>test</scope></dependency>
                    <dependency><groupId>org.junit.vintage</groupId><artifactId>junit-vintage-engine</artifactId><version>5.9.3</version><scope>test</scope></dependency>
                    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
                  </dependencies>
                  <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>%s</plugins></build>%s
                </project>
                """.formatted(plugins, profile));
        write(root, "includes.txt", "**/FastTest.java\n");
        write(root, "excludes.txt", "**/ShapesTest.java\n");
        write(root, "src/main/java/example/Service.java", """
                package example;
                public class Service { public static int value() { return 7; } }
                """);
        write(root, "src/test/java/example/FastTest.java", """
                package example;
                @org.junit.jupiter.api.Tag("fast") public class FastTest {
                  @org.junit.jupiter.api.Test void alpha() throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("test-body-ran"), "alpha");
                    org.junit.jupiter.api.Assertions.assertEquals(7, Service.value());
                  }
                  @org.junit.jupiter.api.Tag("slow") @org.junit.jupiter.api.Test void beta() { Service.value(); }
                  public static class Inner { @org.junit.jupiter.api.Test void inner() { Service.value(); } }
                }
                """);
        for (String name : List.of("SlowTest", "ExampleIT", "ITExample", "ExampleITCase", "TestFirst", "Unconventional"))
            write(root, "src/test/java/example/" + name + ".java", "package example; public class " + name
                    + " { @org.junit.jupiter.api.Test void alpha() { Service.value(); } }\n");
        write(root, "src/test/java/example/VintageTest.java", """
                package example;
                public class VintageTest { @org.junit.Test public void vintage() { org.junit.Assert.assertEquals(7, Service.value()); } }
                """);
        write(root, "src/test/java/example/ShapesTest.java", """
                package example;
                public class ShapesTest {
                  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints=1)
                  void overloaded(int value) { org.junit.jupiter.api.Assertions.assertEquals(1, value); org.junit.jupiter.api.Assertions.assertEquals(7, Service.value()); }
                  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings="x")
                  void overloaded(String value) { org.junit.jupiter.api.Assertions.assertEquals("x", value); org.junit.jupiter.api.Assertions.assertEquals(7, Service.value()); }
                  @org.junit.jupiter.api.Disabled @org.junit.jupiter.api.Test void disabled() { throw new AssertionError(); }
                  @org.junit.jupiter.api.Nested class NestedGroup { @org.junit.jupiter.api.Test void nested() { Service.value(); } }
                }
                """);
        commit(root);
        return root;
    }

    private static String includes(String pattern) { return "<includes><include>" + pattern + "</include></includes>"; }
    private static String target(String provider) { return "maven:.@" + provider + "@fixture-run"; }
    private static String goal(String version, String provider) {
        return "org.apache.maven.plugins:maven-" + provider + "-plugin:" + version + ":"
                + (provider.equals("surefire") ? "test" : "integration-test") + "@fixture-run";
    }
    private static Path fragment(Path root, String provider) { return root.resolve("target/stp/coverage-fragment-v3-._" + provider + "_fixture-run.json"); }
    private static Set<ExecutableTestIdentity> identities(Path root) throws Exception {
        return new ExecutableHeadTestInventoryCodec().read(root.resolve("inventory.json").toFile()).runnableTests();
    }
    private static Result inventory(Path root, String provider) throws Exception { return inventory(root, provider, List.of()); }
    private static Result inventory(Path root, String provider, List<String> extra) throws Exception {
        var args = new ArrayList<>(List.of(PLUGIN + "generate-reactor-head-test-inventory", "-DsmartTestPicker.schemaVersion=3",
                "-DsmartTestPicker.prHeadRevision=" + git(root, "rev-parse", "HEAD").trim(),
                "-DsmartTestPicker.executionType=" + provider, "-DsmartTestPicker.executionId=fixture-run",
                "-DsmartTestPicker.outputFile=" + root.resolve("inventory.json")));
        args.addAll(extra); return invoke(root, args.toArray(String[]::new));
    }
    private static Result mapping(Path root, String version, String provider, List<String> extra) throws Exception {
        var args = new ArrayList<>(List.of(PLUGIN + "prepare-reactor-executable-mapping", goal(version, provider),
                PLUGIN + "generate-coverage-fragment", "-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.coverageCollector=ASM",
                "-DsmartTestPicker.revision=" + git(root, "rev-parse", "HEAD").trim(), "-DsmartTestPicker.shardId=s",
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"), "-DsmartTestPicker.executionType=" + provider,
                "-DsmartTestPicker.executionId=fixture-run"));
        args.addAll(extra); return invoke(root, args.toArray(String[]::new));
    }
    private static void assignment(Path root, String provider, Set<String> tests) throws Exception {
        Files.write(root.resolve("assignment.json"), new ExecutableShardAssignmentCodec().serialize(new ExecutableShardAssignment(1,
                new CoverageMapRevision(git(root, "rev-parse", "HEAD").trim()), new ShardId("s"), tests.stream()
                .map(s -> ExecutableTestIdentity.parse(target(provider) + "::" + s)).collect(Collectors.toSet()))));
    }
    private static Result invoke(Path root, String... args) throws Exception { return maven(root, args); }
    private static void write(Path root, String file, String text) throws Exception {
        Path path = root.resolve(file); Files.createDirectories(path.getParent()); Files.writeString(path, text);
    }
    private static String commit(Path root) throws Exception {
        if (!Files.isDirectory(root.resolve(".git"))) git(root, "init", "-q");
        git(root, "config", "commit.gpgsign", "false");
        git(root, "config", "tag.gpgsign", "false");
        git(root, "add", ".");
        git(root, "-c", "user.name=STP Test", "-c", "user.email=stp@example.invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "selection fixture");
        return git(root, "rev-parse", "HEAD").trim();
    }
    private static String git(Path root, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git")); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()); assertEquals(0, process.waitFor(), output); return output;
    }
    private static Set<String> reported(Path root, String provider) throws Exception {
        Set<String> result = new TreeSet<>(); Path directory = root.resolve("target/" + provider + "-reports");
        if (!Files.isDirectory(directory)) return result;
        try (var paths = Files.list(directory)) {
            for (Path xml : paths.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")).toList()) {
                var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var cases = factory.newDocumentBuilder().parse(xml.toFile()).getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    var row = (org.w3c.dom.Element) cases.item(i);
                    result.add(row.getAttribute("classname") + "#" + row.getAttribute("name").replaceFirst("\\(.*", "").replaceFirst("\\[.*", ""));
                }
            }
        }
        return result;
    }
    private static void save(String version, String provider, String scenario, String file, Result run) throws Exception {
        Path path = Path.of("build/maven-inventory-filters-04b", version, provider, scenario, file);
        Files.createDirectories(path.getParent()); Files.writeString(path, "exitCode=" + run.exitCode() + "\n" + run.output());
    }
}
