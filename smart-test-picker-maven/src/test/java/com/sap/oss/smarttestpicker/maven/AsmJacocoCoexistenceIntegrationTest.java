// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import static org.junit.jupiter.api.Assertions.*;
import static com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.copyFixture;
import static com.sap.oss.smarttestpicker.maven.ReactorHeadTestInventoryMojoIntegrationTest.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.xml.XMLFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.google.gson.GsonBuilder;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;

/** Project-owned JaCoCo reporting, independent of STP's coverage model. */
class AsmJacocoCoexistenceIntegrationTest {
    private static final String VERSION = System.getProperty("smartTestPicker.version");
    private static final String PLUGIN = "com.sap.oss.smart-test-picker:smart-test-picker-maven:" + VERSION + ":";
    private static final Set<String> METHODS = Set.of("enumShape", "recordShape", "bridgeShape", "implicitExceptionShape");

    @ParameterizedTest
    @ValueSource(strings = {"literal", "prepare-agent"})
    void preparedAsmPreservesEveryProjectJacocoCounterAndAgentOrder(String shape, @TempDir Path temp) throws Exception {
        Path baseline = copyFixture("asm-jacoco-coexistence", temp.resolve("baseline"));
        Path coexist = copyFixture("asm-jacoco-coexistence", temp.resolve("coexist"));
        Path observer = buildObserver(temp);
        run(baseline, shape, false, observer, false, shape + "/baseline");
        run(coexist, shape, true, observer, false, shape + "/coexist");
        Map<String,Map<String,Counter>> expected = counters(baseline.resolve("target/site/jacoco/jacoco.xml"));
        Map<String,Map<String,Counter>> actual = counters(coexist.resolve("target/site/jacoco/jacoco.xml"));
        writeJson(saved(shape).resolve("counter-comparison.json"), Map.of(
                "baseline", expected, "coexist", actual, "differences", differences(expected, actual)));
        assertEquals(expected, actual, "P2 STOP: project JaCoCo counters changed; do not widen tolerance");
        assertFilterSensitiveShapes(baseline, expected);
        try (var files = Files.walk(baseline.resolve("target/classes"))) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(coexist.resolve(baseline.relativize(file))),
                        "Project report must analyze identical compiled bytes");
        }
        assertNoProbeMutation(baseline); assertNoProbeMutation(coexist);
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(
                Files.readAllBytes(coexist.resolve("target/stp/coverage-fragment-v3.json")));
        assertEquals(identities(), fragment.tests().keySet()); assertTrue(fragment.unmapped().isEmpty());
        assertTrue(fragment.collectionCompleted());
        assertTrue(fragment.tests().get(ExecutableTestIdentity.parse("maven:.::fixture.ShapesTest#enumShape"))
                .coveredMethods().stream().anyMatch(m -> m.toString().contains("EmptyEnum#<init>")));
        var evidence = MavenMappingEvidence.read(coexist.resolve("target/stp/execution-evidence-v2.json"));
        assertEquals(identities(), evidence.executed()); assertTrue(evidence.nonExecuted().isEmpty());
        if (shape.equals("prepare-agent")) {
            Path withoutObserver = copyFixture("asm-jacoco-coexistence", temp.resolve("without-observer"));
            run(withoutObserver, shape, false, null, false, "observer-absent");
            assertEquals(expected, counters(withoutObserver.resolve("target/site/jacoco/jacoco.xml")),
                    "The observer itself must not change project coverage");
            measureUnsupportedInverseOrder(temp, observer, expected);
        }
    }

    @Test
    void observerDetectsActualResetDumpAndSnapshotCalls(@TempDir Path temp) throws Exception {
        Path fixture = copyFixture("asm-jacoco-coexistence", temp.resolve("control"));
        run(fixture, "prepare-agent", false, buildObserver(temp), true, "observer-control");
        String trace = Files.readString(fixture.resolve("target/probe-calls.txt"));
        for (String method : List.of("#reset()V", "#dump(Z)V", "#getExecutionData(Z)[B"))
            assertTrue(trace.lines().anyMatch(l -> l.startsWith("CALL ") && l.contains(method)), trace);
        assertTrue(trace.contains("fixture.ShapesTest.observerPositiveControl"));
        assertTrue(trace.contains("reset=true"));
    }

    private static Set<ExecutableTestIdentity> identities() {
        return METHODS.stream().map(m -> ExecutableTestIdentity.parse("maven:.::fixture.ShapesTest#" + m))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void run(Path fixture, String shape, boolean mapping, Path observer, boolean control, String label) throws Exception {
        var args = new ArrayList<>(List.of("-X", "-Dexpect.stp=" + mapping, "-Dprobe.control=" + control));
        if (observer != null) args.add("-DobserverArgLine=-javaagent:" + observer + "=" + fixture.resolve("target/probe-calls.txt"));
        var profiles = new ArrayList<String>();
        if (shape.equals("literal")) profiles.add("literal");
        if (mapping) {
            profiles.add("stp");
            Path assignment = fixture.resolve("assignment.json");
            Files.write(assignment, new ExecutableShardAssignmentCodec().serialize(new ExecutableShardAssignment(1,
                    new CoverageMapRevision("coexistence-revision"), new ShardId("coexistence-shard"), identities())));
            args.addAll(List.of(PLUGIN + "prepare-reactor-executable-mapping", "-DsmartTestPicker.schemaVersion=3",
                    "-DsmartTestPicker.coverageCollector=ASM", "-DsmartTestPicker.asm.includes=fixture.",
                    "-DsmartTestPicker.revision=coexistence-revision", "-DsmartTestPicker.shardId=coexistence-shard",
                    "-DsmartTestPicker.testsFile=" + assignment));
        }
        if (!profiles.isEmpty()) args.add("-P" + String.join(",", profiles));
        args.add("verify");
        var result = maven(fixture, args.toArray(String[]::new));
        // Maven -X prints process environment values; never retain those secrets as evidence.
        String safeLog = result.output().replaceAll("(?m)^\\[DEBUG\\] env\\.[^\\r\\n]*", "[DEBUG] environment entry redacted");
        Files.writeString(saved(label).resolve("maven.log"), safeLog);
        Files.writeString(saved(label).resolve("command.txt"), "mvn -U --batch-mode --no-transfer-progress --settings functional-test-settings.xml "
                + String.join(" ", args) + "\n");
        assertEquals(0, result.exitCode(), safeLog);
        String fork = safeLog.lines().filter(l -> l.contains("Forking command line:")).findFirst().orElseThrow();
        Files.writeString(saved(label).resolve("fork-command.txt"), fork + "\n");
        int jacoco = fork.indexOf(shape.equals("literal") ? "project-agent/jacoco.jar" : "org.jacoco.agent");
        int stp = fork.indexOf("stp-agent");
        assertTrue(jacoco >= 0, fork);
        if (mapping) assertTrue(stp > jacoco, "P6: JaCoCo must precede STP in the actual Surefire fork: " + fork);
        else if (label.equals("unsupported-inverse")) assertTrue(stp >= 0 && stp < jacoco, fork);
        else assertEquals(-1, stp, "P1 baseline must have no STP agent");
        var document = xml(fixture.resolve("target/surefire-reports/TEST-fixture.ShapesTest.xml"));
        assertEquals("4", document.getAttribute("tests")); assertEquals("0", document.getAttribute("failures"));
        assertEquals("0", document.getAttribute("errors")); assertEquals("0", document.getAttribute("skipped"));
        Set<String> executed = new TreeSet<>();
        for (Element test : children(document, "testcase")) executed.add(test.getAttribute("name"));
        assertEquals(METHODS, executed);
        for (String file : List.of("target/site/jacoco/jacoco.xml", "target/project.exec", "target/probe-calls.txt",
                "target/fork-arguments.txt", "target/surefire-reports/TEST-fixture.ShapesTest.xml",
                "target/stp/coverage-fragment-v3.json", "target/stp/execution-evidence-v2.json", "target/stp/asm-agent-diagnostic.json")) {
            Path source = fixture.resolve(file);
            if (Files.isRegularFile(source)) Files.copy(source, saved(label).resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void measureUnsupportedInverseOrder(Path temp, Path observer,
            Map<String,Map<String,Counter>> baseline) throws Exception {
        Path fixture = copyFixture("asm-jacoco-coexistence", temp.resolve("unsupported-inverse"));
        Files.createDirectories(fixture.resolve("target/stp"));
        Path pom = fixture.resolve("pom.xml");
        // Deliberate raw attachment in a disposable fixture, never a prepared-path configuration option.
        String agent = "-javaagent:" + System.getProperty("stp.test.agent.jar")
                + "=output=" + fixture.resolve("target/stp/asm-agent-diagnostic.json")
                + ";fragmentOutput=" + fixture.resolve("target/stp/coverage-fragment-v3.json")
                + ";schemaVersion=3;revision=coexistence-revision;shardId=coexistence-shard"
                + ";executionTarget=maven:.;runId=unsupported-inverse;includes=fixture.";
        Files.writeString(pom, Files.readString(pom).replace("${observerArgLine} @{argLine}",
                "${observerArgLine} " + agent + " -DsmartTestPicker.collectionEnabled=true -DsmartTestPicker.coverageCollector=ASM @{argLine}"));
        run(fixture, "prepare-agent", false, observer, false, "unsupported-inverse");
        Map<String,Map<String,Counter>> projectReport = counters(fixture.resolve("target/site/jacoco/jacoco.xml"));
        assertFalse(differences(baseline, projectReport).isEmpty(), "The unsupported order must remain visibly unsafe in this fixture");
        // Diagnostic only: analyze the actual bytes JaCoCo received to separate ID mismatch from filter changes.
        var exec = new ExecFileLoader(); exec.load(fixture.resolve("target/project.exec").toFile());
        var coverage = new CoverageBuilder(); var analyzer = new Analyzer(exec.getExecutionDataStore(), coverage);
        try (var files = Files.walk(fixture.resolve("target/classes"))) {
            for (Path compiled : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = new ClassReader(Files.readAllBytes(compiled)).getClassName();
                Path parent = fixture.resolve("target/jacoco-input").resolve(name).getParent();
                String leaf = name.substring(name.lastIndexOf('/') + 1) + ".";
                try (var dumps = Files.list(parent)) {
                    Path dump = dumps.filter(p -> p.getFileName().toString().startsWith(leaf)).findFirst().orElseThrow();
                    analyzer.analyzeAll(dump.toFile());
                    Path retained = Files.createDirectories(saved("unsupported-inverse").resolve("classes"));
                    Files.copy(compiled, retained.resolve(compiled.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(dump, retained.resolve(dump.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        assertTrue(coverage.getNoMatchClasses().isEmpty());
        Path matched = saved("unsupported-inverse").resolve("jacoco-on-jacoco-input.xml");
        try (var output = Files.newOutputStream(matched)) {
            var visitor = new XMLFormatter().createVisitor(output);
            visitor.visitInfo(exec.getSessionInfoStore().getInfos(), exec.getExecutionDataStore().getContents());
            visitor.visitBundle(coverage.getBundle("diagnostic-matched-input"), new DirectorySourceFileLocator(
                    fixture.resolve("src/main/java").toFile(), "UTF-8", 4));
            visitor.visitEnd();
        }
        Map<String,Map<String,Counter>> matchedReport = counters(matched);
        writeJson(saved("unsupported-inverse").resolve("counter-comparison.json"), Map.of(
                "baseline", baseline, "projectReport", projectReport, "matchedInputReport", matchedReport,
                "projectReportDifferences", differences(baseline, projectReport),
                "matchedInputDifferences", differences(baseline, matchedReport)));
        assertEquals(new Counter(0, 7), matchedReport.get("fixture/EmptyEnum#<init>(Ljava/lang/String;I)V").get("INSTRUCTION"));
        assertNoProbeMutation(fixture);
    }

    private static void assertNoProbeMutation(Path fixture) throws IOException {
        String trace = Files.readString(fixture.resolve("target/probe-calls.txt"));
        assertTrue(trace.startsWith("OBSERVER_READY")); assertFalse(trace.contains("OBSERVER_ERROR"), trace);
        for (String watched : List.of("#reset()V", "#dump(Z)V", "#getExecutionData(Z)[B", "#collect("))
            assertTrue(trace.lines().anyMatch(l -> l.startsWith("WATCH ") && l.contains(watched)), trace);
        List<String> calls = trace.lines().filter(l -> l.startsWith("CALL ")).toList();
        assertFalse(calls.isEmpty(), "Observer must see JaCoCo's normal shutdown collection");
        assertTrue(calls.stream().allMatch(l -> l.contains("#collect(") && l.endsWith("reset=false")), trace);
        assertFalse(trace.contains("com.sap.oss.smarttestpicker"), trace);
        assertTrue(trace.contains(".shutdown("), trace);
    }

    private static void assertFilterSensitiveShapes(Path fixture, Map<String,Map<String,Counter>> counters) throws IOException {
        var enumClass = node(fixture, "EmptyEnum");
        var constructor = enumClass.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        List<Integer> opcodes = new ArrayList<>();
        constructor.instructions.forEach(i -> { if (i.getOpcode() >= 0) opcodes.add(i.getOpcode()); });
        assertEquals(List.of(Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.INVOKESPECIAL, Opcodes.RETURN), opcodes);
        assertFalse(counters.containsKey("fixture/EmptyEnum#<init>(Ljava/lang/String;I)V"));
        assertTrue((node(fixture, "ValueRecord").access & Opcodes.ACC_RECORD) != 0);
        assertFalse(counters.containsKey("fixture/ValueRecord#text()Ljava/lang/String;"));
        assertFalse(counters.containsKey("fixture/ValueRecord#number()I"));
        assertTrue(node(fixture, "BridgeValue").methods.stream().anyMatch(m ->
                m.name.equals("get") && (m.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) == (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)));
        assertFalse(counters.containsKey("fixture/BridgeValue#get()Ljava/lang/Object;"));
        assertEquals(new Counter(0, 2), counters.get("fixture/BridgeValue#get()Ljava/lang/String;").get("INSTRUCTION"));
        assertEquals(new Counter(0, 9), counters.get("fixture/ValueRecord#<init>(Ljava/lang/String;I)V").get("INSTRUCTION"));
        assertEquals(new Counter(3, 0), counters.get("fixture/ImplicitException#length(Ljava/lang/String;)I").get("INSTRUCTION"));
    }

    private static ClassNode node(Path fixture, String name) throws IOException {
        var node = new ClassNode(); new ClassReader(Files.readAllBytes(fixture.resolve("target/classes/fixture/" + name + ".class"))).accept(node, 0); return node;
    }

    private static Path buildObserver(Path temp) throws Exception {
        Path classes = Files.createDirectories(temp.resolve("observer-classes"));
        Path asm = Path.of(ClassReader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", asm.toString(), "-d", classes.toString(),
                "src/test/fixtures/asm-jacoco-coexistence/observer/ProbeObserver.java"));
        var manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", "observation.ProbeObserver");
        Path output = temp.resolve("probe-observer.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(output), manifest); var files = Files.walk(classes); var dependency = new JarFile(asm.toFile())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/'))); Files.copy(file, jar); jar.closeEntry();
            }
            for (JarEntry entry : Collections.list(dependency.entries())) if (!entry.isDirectory() && entry.getName().startsWith("org/objectweb/asm/")) {
                jar.putNextEntry(new JarEntry(entry.getName())); try (var input = dependency.getInputStream(entry)) { input.transferTo(jar); } jar.closeEntry();
            }
        }
        return output;
    }

    private record Counter(int missed, int covered) { }
    private static Map<String,Map<String,Counter>> counters(Path file) throws Exception {
        Map<String,Map<String,Counter>> result = new TreeMap<>();
        var classes = xml(file).getElementsByTagName("class");
        for (int i = 0; i < classes.getLength(); i++) {
            Element type = (Element) classes.item(i); String name = type.getAttribute("name");
            result.put(name, counterValues(type));
            for (Element method : children(type, "method")) result.put(name + "#" + method.getAttribute("name") + method.getAttribute("desc"), counterValues(method));
        }
        return result;
    }
    private static Map<String,Counter> counterValues(Element parent) {
        Map<String,Counter> result = new TreeMap<>();
        for (Element counter : children(parent, "counter")) result.put(counter.getAttribute("type"),
                new Counter(Integer.parseInt(counter.getAttribute("missed")), Integer.parseInt(counter.getAttribute("covered"))));
        return result;
    }
    private static List<Element> children(Element parent, String name) {
        var result = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element element && name.equals(element.getTagName())) result.add(element);
        return result;
    }
    private static Element xml(Path file) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }
    private static Map<String,Object> differences(Map<String,Map<String,Counter>> baseline, Map<String,Map<String,Counter>> other) {
        Map<String,Object> result = new TreeMap<>(); Set<String> keys = new TreeSet<>(baseline.keySet()); keys.addAll(other.keySet());
        for (String key : keys) if (!Objects.equals(baseline.get(key), other.get(key))) {
            var difference = new LinkedHashMap<String,Object>(); difference.put("baseline", baseline.get(key)); difference.put("other", other.get(key)); result.put(key, difference);
        }
        return result;
    }
    private static Path saved(String label) throws IOException { return Files.createDirectories(Path.of("build/maven-asm-07", label)); }
    private static void writeJson(Path file, Object data) throws IOException { Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(data) + "\n"); }
}
