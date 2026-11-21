// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import static org.junit.jupiter.api.Assertions.*;
import static com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.*;

/** Checks the actual provider JVM, project report consumers and final mapping artifacts. */
class JacocoParityIntegrationTest {
    @TempDir Path temporary;
    static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:"
            + System.getProperty("smartTestPicker.version") + ":";

    @Test void asmPreservesItsFragmentAndProjectJacocoResults() throws Exception {
        Path root = fixture(temporary.resolve("coexist"));
        success(maven(root, "install", "-DskipTests", "-Djacoco.skip=true"));
        success(maven(root, "-pl", ".,app", "verify"));
        var expectedReport = counters(root.resolve("app/target/site/jacoco/jacoco.xml"));
        var expectedExec = execution(root.resolve("app/target/project.exec"), root.resolve("app/target/classes"));
        success(mapping(root, "ASM", true, "-Djacoco.skip=true"));
        byte[] fragment = Files.readAllBytes(fragment(root));
        Files.delete(root.resolve("app/target/project.exec"));
        var result = mapping(root, "ASM", true);
        success(result);
        assertArrayEquals(fragment, Files.readAllBytes(fragment(root)));
        assertEquals(expectedReport, counters(root.resolve("app/target/site/jacoco/jacoco.xml")));
        assertEquals(expectedExec, execution(root.resolve("app/target/project.exec"), root.resolve("app/target/classes")));
        assertFalse(result.output().contains("does not match"), result.output());
    }

    @ParameterizedTest @CsvSource({"ASM,test", "JACOCO,test", "JACOCO,conversion"})
    void failedMappingInvalidatesPreviouslyAcceptedAggregate(String collector, String failure) throws Exception {
        Path root = fixture(temporary.resolve("stale"));
        success(maven(root, "test-compile", "-Djacoco.skip=true"));
        success(mapping(root, collector, false));
        assertTrue(Files.isRegularFile(aggregate(root)));
        var failed = mapping(root, collector, false, "-Dfixture.failure=" + failure);
        assertNotEquals(0, failed.exitCode(), failed.output());
        assertTrue(failed.output().contains(failure.equals("test") ? "Tests run:" : "FAILED"), failed.output());
        assertFalse(Files.exists(aggregate(root)), failed.output());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void jacocoCoversActiveAndInstalledReactorProduction(boolean omitted) throws Exception {
        Path root = fixture(temporary.resolve("cross-module"));
        success(maven(root, "install", "-DskipTests", "-Djacoco.skip=true"));
        success(mapping(root, "JACOCO", omitted));
        var value = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(aggregate(root)));
        assertEquals(2, value.tests().size());
        for (var coverage : value.tests().values()) {
            assertTrue(coverage.coveredClasses().contains("example.library.Library"), coverage.toString());
            assertTrue(coverage.coveredMethods().stream().anyMatch(method -> method.toString().equals("example.library.Library#value()I")));
            assertFalse(coverage.coveredClasses().contains("example.Helper"));
        }
    }

    @ParameterizedTest @CsvSource({"false,true", "true,true", "true,false"})
    void mappingNeverCreatesOrChangesProjectExecutionData(boolean present, boolean append) throws Exception {
        Path root = fixture(temporary.resolve("isolation"));
        Path pom = root.resolve("app/pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("</project>", """
            <build><plugins><plugin>
              <groupId>org.apache.maven.plugins</groupId><artifactId>maven-antrun-plugin</artifactId><version>3.1.0</version>
              <executions><execution><id>observe-private-agent-output</id><phase>verify</phase>
                <goals><goal>run</goal></goals><configuration><target>
                  <resourcecount property="private.agent.outputs">
                    <fileset dir="${project.build.directory}/stp" includes="jacoco-agent-*/agent.exec">
                      <size value="0" when="more"/>
                    </fileset>
                  </resourcecount>
                  <echo file="${project.build.directory}/private-agent-output-count.txt">${private.agent.outputs}</echo>
                </target></configuration>
              </execution></executions>
            </plugin></plugins></build>
            </project>
            """));
        success(maven(root, "test-compile", "-Djacoco.skip=true"));
        Path projectData = root.resolve("app/target/project.exec");
        byte[] original = "project-owned execution data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (present) Files.write(projectData, original);
        var result = mapping(root, "JACOCO", false, "-Dfixture.append=" + append);
        success(result);
        if (present) assertArrayEquals(original, Files.readAllBytes(projectData));
        else assertFalse(Files.exists(projectData));
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(fragment(root)));
        assertEquals(2, fragment.tests().size());
        assertTrue(fragment.tests().values().stream().allMatch(test -> test.coveredClasses().contains("example.Service")));
        assertTrue(Integer.parseInt(Files.readString(root.resolve("app/target/private-agent-output-count.txt")).trim()) > 0,
                "Non-empty STP-owned agent output must exist after tests, before the Maven session ends");
        try (var files = Files.list(root.resolve("app/target/stp"))) {
            assertEquals(List.of(), files.filter(path -> path.getFileName().toString().startsWith("jacoco-agent-"))
                    .map(path -> path.getFileName().toString()).sorted().toList(),
                    "Invocation-owned agent entries must be removed when the Maven session ends");
        }
    }

    @Test void projectConsumersAreSkippedWithoutDisablingAgentAttachment() throws Exception {
        Path root = fixture(temporary.resolve("consumers"));
        success(maven(root, "test-compile", "-Djacoco.skip=true"));
        var result = mapping(root, "JACOCO", false, "-Pproject-consumers");
        success(result);
        for (String goal : List.of("report", "report-integration", "report-aggregate", "check", "merge", "dump")) {
            assertTrue(result.output().lines().anyMatch(line -> line.contains("project-" + goal)
                    && line.toLowerCase(Locale.ROOT).contains("skip") && line.contains("STP")), result.output());
        }
        assertFalse(Files.exists(root.resolve("app/target/site/jacoco/jacoco.xml")));
        assertTrue(Files.exists(fragment(root)));
        success(maven(root, "verify"));
        assertTrue(Files.isRegularFile(root.resolve("app/target/site/jacoco/jacoco.xml")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void privateAgentDirectoriesDoNotAccumulateAfterMapping(boolean failing) throws Exception {
        Path root = fixture(temporary.resolve("private-agent-cleanup"));
        success(maven(root, "test-compile", "-Djacoco.skip=true"));
        Path projectData = root.resolve("app/target/project.exec");
        byte[] sentinel = "project-owned execution data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(projectData, sentinel);
        for (int invocation = 0; invocation < 2; invocation++) {
            var result = mapping(root, "JACOCO", false, "-Dfixture.failure=" + (failing ? "test" : ""));
            if (failing) assertNotEquals(0, result.exitCode(), result.output());
            else success(result);
            assertArrayEquals(sentinel, Files.readAllBytes(projectData));
            for (String module : List.of("library", "app")) {
                Path directory = root.resolve(module + "/target/stp");
                if (!Files.isDirectory(directory)) continue;
                try (var files = Files.list(directory)) {
                    assertEquals(List.of(), files.filter(path -> path.getFileName().toString().startsWith("jacoco-agent-"))
                            .map(path -> path.getFileName().toString()).sorted().toList(),
                            "Private agent directories remain after invocation " + invocation + " in " + module);
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"no-agent", "offline"})
    void unsafeAgentConfigurationsFailClearly(String kind) throws Exception {
        Path root = fixture(temporary.resolve(kind));
        success(maven(root, "test-compile", "-Djacoco.skip=true"));
        var failed = mapping(root, "JACOCO", false, kind.equals("offline") ? "-Poffline" : "-Djacoco.skip=true");
        assertNotEquals(0, failed.exitCode(), failed.output());
        String message = kind.equals("offline") ? "instrument" : "Active JaCoCo runtime is unavailable";
        assertTrue(failed.output().contains(message), failed.output());
        if (kind.equals("offline")) assertTrue(failed.output().toLowerCase(Locale.ROOT).contains("unsupported"), failed.output());
        assertFalse(Files.exists(aggregate(root)));
    }

    static Path fixture(Path root) throws Exception {
        String version = System.getProperty("smartTestPicker.version");
        write(root, "pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>example.parity</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
              <modules><module>library</module><module>app</module></modules>
              <properties><maven.compiler.release>17</maven.compiler.release><maven.compiler.source>17</maven.compiler.source><maven.compiler.target>17</maven.compiler.target>
                <fixture.append>false</fixture.append><fixture.failure></fixture.failure>
              </properties>
              <dependencies>
                <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.9.3</version><scope>test</scope></dependency>
                <dependency><groupId>com.sap.oss.smart-test-picker</groupId><artifactId>smart-test-picker-core</artifactId><version>%s</version><scope>test</scope></dependency>
              </dependencies>
              <build><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>
                  <configuration><systemPropertyVariables><stp.exec.dir>${project.build.directory}/jacoco</stp.exec.dir><fixture.failure>${fixture.failure}</fixture.failure></systemPropertyVariables>
                    <properties><configurationParameters>junit.jupiter.extensions.autodetection.enabled = true</configurationParameters></properties>
                  </configuration>
                </plugin>
                <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.13</version>
                  <configuration><destFile>${project.build.directory}/project.exec</destFile><dataFile>${project.build.directory}/project.exec</dataFile>
                    <append>${fixture.append}</append>
                  </configuration>
                  <executions><execution><id>project-agent</id><goals><goal>prepare-agent</goal></goals><configuration><includes><include>example.*</include></includes></configuration></execution>
                    <execution><id>project-report</id><phase>verify</phase><goals><goal>report</goal></goals></execution>
                  </executions>
                </plugin>
              </plugins></build>
              <profiles>
                <profile><id>mapping</id><build><plugins><plugin><groupId>com.sap.oss.smart-test-picker</groupId><artifactId>smart-test-picker-maven</artifactId><version>%s</version>
                  <configuration><moduleFragmentOutput>${smartTestPicker.moduleFragmentOutput}</moduleFragmentOutput><moduleEvidenceOutput>${smartTestPicker.moduleEvidenceOutput}</moduleEvidenceOutput></configuration>
                  <executions><execution><id>stp-finalize</id><phase>verify</phase><goals><goal>generate-reports</goal><goal>generate-coverage-fragment</goal></goals></execution></executions>
                </plugin></plugins></build></profile>
                <profile><id>project-consumers</id><build><plugins><plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId>
                  <executions>%s</executions>
                </plugin></plugins></build></profile>
                <profile><id>offline</id><build><plugins><plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId>
                  <executions><execution><id>project-instrument</id><goals><goal>instrument</goal></goals></execution></executions>
                </plugin></plugins></build></profile>
              </profiles>
            </project>
            """.formatted(version, version, consumers()));
        for (String module : List.of("library", "app")) write(root, module + "/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
              <parent><groupId>example.parity</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>%s</artifactId>
              %s
            </project>
            """.formatted(module, module.equals("app") ? "<dependencies><dependency><groupId>example.parity</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies>" : ""));
        write(root, "library/src/main/java/example/library/Library.java",
                "package example.library; public class Library { public static int value() { return 7; } }");
        write(root, "app/src/main/java/example/Service.java", """
            package example;
            public class Service {
              public static int value() { return example.library.Library.value(); }
              public static int unused() { return 9; }
            }
            """);
        write(root, "app/src/test/java/example/Helper.java", "package example; class Helper { static int value() { return Service.value(); } }");
        write(root, "app/src/test/java/example/ParityTest.java", """
            package example;
            class ParityTest {
              @org.junit.jupiter.api.Test void first() {
                org.junit.jupiter.api.Assertions.assertEquals(7, Helper.value());
                org.junit.jupiter.api.Assertions.assertNotEquals("test", System.getProperty("fixture.failure"));
              }
              @org.junit.jupiter.api.Test void second() { org.junit.jupiter.api.Assertions.assertEquals(7, Helper.value()); }
              @org.junit.jupiter.api.AfterAll static void after() {
                if ("conversion".equals(System.getProperty("fixture.failure"))) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                  try (var files = java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("stp.exec.dir")))) {
                    for (var path : files.filter(p -> p.toString().endsWith(".exec")).toList()) java.nio.file.Files.write(path, new byte[] {1, 2, 3});
                  } catch (Exception error) { throw new RuntimeException(error); }
                }));
              }
            }
            """);
        write(root, ".gitignore", "target/\nassignment.json\nfunctional-test-settings.xml\n");
        command(root, "git", "init", "-q"); command(root, "git", "config", "commit.gpgsign", "false");
        command(root, "git", "config", "tag.gpgsign", "false"); command(root, "git", "add", ".");
        command(root, "git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture");
        String revision = command(root, "git", "rev-parse", "HEAD").trim();
        write(root, "assignment.json", "{\"version\":1,\"revision\":\"" + revision + "\",\"shardId\":\"local\",\"tests\":["
                + "\"maven:app::example.ParityTest#first\",\"maven:app::example.ParityTest#second\"]}");
        return root;
    }

    private static String consumers() {
        StringBuilder xml = new StringBuilder();
        for (String goal : List.of("report-integration", "report-aggregate", "check", "merge", "dump")) {
            String configuration = switch (goal) {
                case "check" -> "<rules><rule><element>BUNDLE</element><limits><limit><counter>INSTRUCTION</counter><value>COVEREDRATIO</value><minimum>0.1</minimum></limit></limits></rule></rules>";
                case "merge" -> "<fileSets><fileSet><directory>${project.build.directory}</directory><includes><include>project.exec</include></includes></fileSet></fileSets>";
                case "dump" -> "<port>9</port><retryCount>0</retryCount>";
                default -> "";
            };
            xml.append("<execution><id>project-").append(goal).append("</id><phase>verify</phase><goals><goal>")
                    .append(goal).append("</goal></goals><configuration>").append(configuration).append("</configuration></execution>");
        }
        return xml.toString();
    }
    static Result mapping(Path root, String collector, boolean omitted, String... extra) throws Exception {
        var arguments = new ArrayList<String>();
        if (omitted) arguments.addAll(List.of("-pl", ".,app"));
        arguments.addAll(List.of("-Pmapping", PLUGIN + "prepare-reactor-executable-mapping", "verify", PLUGIN + "aggregate-reactor-coverage-fragment",
                "-DsmartTestPicker.schemaVersion=3", "-DsmartTestPicker.coverageCollector=" + collector,
                "-DsmartTestPicker.testsFile=" + root.resolve("assignment.json"),
                "-DsmartTestPicker.revision=" + command(root, "git", "rev-parse", "HEAD").trim(), "-DsmartTestPicker.shardId=local",
                "-DsmartTestPicker.fragmentOutput=" + aggregate(root), "-DsmartTestPicker.evidenceOutput=" + root.resolve("target/aggregate-evidence.json")));
        arguments.addAll(List.of(extra));
        Result result = maven(root, arguments.toArray(String[]::new));
        System.out.println("PARITY " + root.getFileName() + " " + collector + " omitted=" + omitted + " " + Arrays.toString(extra) + " exit=" + result.exitCode());
        return result;
    }
    private static Path fragment(Path root) { return root.resolve("app/target/stp/coverage-fragment-v3.json"); }
    private static Path aggregate(Path root) { return root.resolve("target/aggregate.json"); }
    static void success(Result result) { assertEquals(0, result.exitCode(), result.output()); }
    static String command(Path root, String... arguments) throws Exception {
        var process = new ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()); assertEquals(0, process.waitFor(), output); return output;
    }
    private static void write(Path root, String relative, String text) throws Exception {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, text);
    }
    private static Map<String,String> counters(Path report) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var document = factory.newDocumentBuilder().parse(report.toFile());
        var result = new TreeMap<String,String>(); var classes = document.getElementsByTagName("class");
        for (int i = 0; i < classes.getLength(); i++) {
            var type = (org.w3c.dom.Element) classes.item(i); var values = type.getElementsByTagName("counter");
            for (int j = 0; j < values.getLength(); j++) {
                var counter = (org.w3c.dom.Element) values.item(j); var parent = (org.w3c.dom.Element) counter.getParentNode();
                result.put(type.getAttribute("name") + "/" + parent.getTagName() + "/" + parent.getAttribute("name") + parent.getAttribute("desc") + "/" + counter.getAttribute("type"),
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
