// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentJoiner;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.*;
import com.sap.oss.smarttestpicker.selector.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JacocoSetupSelectionTest {
    @ParameterizedTest @CsvSource({"gradle,NONE", "maven,SELECTED"})
    void retainedCapabilityEvidenceDemonstratesSelectionConsequence(String tool, String status,
            @TempDir Path temp) throws Exception {
        Path fragment = Path.of(java.util.Objects.requireNonNull(JacocoSetupSelectionTest.class.getResource(
                "/setup-selection/" + tool + "/" + (tool.equals("gradle") ? "fragment.json" : "coverage-fragment-v3.json"))).toURI());
        var result = select(fragment, temp, Path.of("build/stp-gradle-setup-01/before-" + tool));
        assertEquals(status, result.getStatus());
        assertEquals(tool.equals("gradle") ? List.of() : List.of("example.CapabilitiesTest#probes"), result.getSelectedTests());
    }

    static SelectionOutput select(Path input, Path repo, Path evidence) throws Exception {
        Files.createDirectories(repo);
        Path source = repo.resolve("src/main/java/example/SetupOnly.java");
        Files.createDirectories(source.getParent());
        Files.copy(Path.of("../smart-test-picker-maven/src/test/fixtures/capability-probes/main/SetupOnly.java"), source);
        git(repo, "init", "-q"); git(repo, "config", "user.email", "fixture@example.invalid");
        git(repo, "config", "user.name", "STP Fixture"); git(repo, "add", "."); git(repo, "commit", "-qm", "base");
        String base = git(repo, "rev-parse", "HEAD").trim();
        var observed = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(input));
        // Only the revision is rebound to this disposable Git history. Coverage and ownership
        // are copied unchanged from the retained runtime fragment; the original remains intact.
        var fragment = new ExecutableCoverageFragment(observed.schemaVersion(), new CoverageMapRevision(base),
                observed.shardId(), observed.tests(), observed.unmapped(), observed.setupScopes(), observed.collectionCompleted());
        var identities = fragment.tests().keySet();
        var joined = new ExecutableCoverageFragmentJoiner().join(
                new ExecutableTestInventory(fragment.revision(), identities),
                new ExecutableShardPlan(fragment.revision(), Map.of(fragment.shardId(), identities)),
                List.of(fragment), Set.of(), Instant.EPOCH, new GeneratorProvenance("test", "test", "test", "21"));
        var published = new ExecutableCoverageMap(joined.schemaVersion(), joined.revision(), joined.generatedAt(),
                joined.generator(), joined.tests(), joined.unmapped(), joined.setupScopes(), joined.completeness(),
                joined.statistics(), CoverageMapLifecycleState.PUBLISHED, null);
        Files.createDirectories(evidence);
        Path map = evidence.resolve("map.json").toAbsolutePath();
        Files.write(map, new ExecutableCoverageMapCodec().serialize(published));
        Files.writeString(source, Files.readString(source).replace("return 7", "return 8"));
        git(repo, "add", "."); git(repo, "commit", "-qm", "change setup-only production class");
        String head = git(repo, "rev-parse", "HEAD").trim();
        var result = new ExplicitPrSelectorFlow().select(map.toFile(), repo.toFile(),
                HeadTestInventory.atRevision(head, identities.stream().map(ExecutableTestIdentity::test).toList()),
                base, base, head, 10, List.of());
        assertEquals(ExplicitPrSelectionStatus.SELECTION_RESULT, result.status(), result.toString());
        assertEquals(Set.of("example.SetupOnly"), result.context().orElseThrow().changedClasses());
        var output = result.output().orElseThrow();
        String summary = "input=" + input + "\nchanged=[example.SetupOnly]\nstatus=" + output.getStatus()
                + "\nselected=" + output.getSelectedTests() + "\n";
        Files.writeString(evidence.resolve("selection.txt"), summary);
        Files.writeString(evidence.resolve("diff.patch"), git(repo, "diff", base, head));
        System.out.println(summary);
        return output;
    }

    private static String git(Path repo, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git")); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
