// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.maven;

import com.sap.oss.smarttestpicker.coverage.*;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.*;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableFragmentAssignmentValidator;
import com.sap.oss.smarttestpicker.runtime.*;
import com.sap.oss.smarttestpicker.runtime.model.*;
import com.sap.oss.smarttestpicker.selector.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.sap.oss.smarttestpicker.maven.AsmMappingFinalizationTest.set;

/** Real projection, Maven finalization/aggregation, codecs, publication alignment and selector. */
class AsmUnmappedEndToEndTest {
    @ParameterizedTest
    @ValueSource(strings = {"failed", "unfinished", "missing-result", "unsupported", "unowned-error",
            "runtime-uninitialized", "transformation-failure", "method-id-collision", "owned-error-unhealthy"})
    void onlyBrokenIdentitiesBecomeMandatory(String mode, @TempDir Path repo) throws Exception {
        git(repo, "init"); git(repo, "config", "user.name", "STP regression");
        git(repo, "config", "user.email", "stp@example.invalid");
        Files.writeString(repo.resolve("README.txt"), "base");
        git(repo, "add", "README.txt"); git(repo, "commit", "-m", "base");
        String revision = git(repo, "rev-parse", "HEAD");
        var f = AsmMappingFinalizationTest.fixture(repo);
        var target = ExecutionTarget.parse("maven:module");
        Set<ExecutableTestIdentity> assigned = new TreeSet<>(), broken = new TreeSet<>();
        var runtime = new RuntimeEventAggregator("run", "jvm");
        var method = new com.sap.oss.smarttestpicker.runtime.model.MethodIdentity("fixture.Service", "work", "()V");
        for (int i = 0; i < 3000; i++) {
            var id = new ExecutableTestIdentity(target,
                    new com.sap.oss.smarttestpicker.coverage.model.TestIdentity("fixture.Tests", "test%04d".formatted(i)));
            assigned.add(id);
            boolean bad = Set.of("failed", "unfinished", "missing-result", "unsupported", "owned-error-unhealthy")
                    .contains(mode) && i < (mode.equals("unsupported") ? 1 : 2);
            if (bad) broken.add(id);
            boolean unsupported = bad && mode.equals("unsupported");
            var physical = new com.sap.oss.smarttestpicker.runtime.model.TestIdentity("platform-" + i, "test",
                    unsupported ? null : id.test().className(), unsupported ? null : id.test().methodName(),
                    "", "fixture", "run", "jvm");
            runtime.beginTest(physical);
            runtime.record(physical, new MethodHitEvent(method, new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED)));
            if (!(bad && (mode.equals("unfinished") || mode.equals("missing-result")))) {
                runtime.endTest(physical, bad && mode.equals("failed")
                        ? new TestResult(TestExecutionStatus.FAILED, "AssertionError", "expected regression") : TestResult.successful());
            }
        }
        var observation = runtime.snapshot();
        if (mode.equals("missing-result")) {
            observation = new RuntimeObservation(observation.runId(), observation.jvmId(), observation.tests().stream()
                    .map(t -> new RuntimeObservation.PhysicalTest(t.identity(), t.result(), t.methods(), true, t.unattributedEvents()))
                    .toList(), observation.setup(), observation.unattributedEvents(), observation.setupDiagnostics());
        }
        var integrity = switch (mode) {
            case "unowned-error" -> new CollectorIntegrity(true, 0, 0, List.of(CollectorError.fatal("unowned diagnostic")));
            case "runtime-uninitialized" -> new CollectorIntegrity(false, 0, 0, List.of());
            case "transformation-failure" -> new CollectorIntegrity(true, 1, 0, List.of(CollectorError.fatal("transformation failure")));
            case "method-id-collision" -> new CollectorIntegrity(true, 0, 1, List.of(CollectorError.fatal("method id collision")));
            case "owned-error-unhealthy" -> new CollectorIntegrity(false, 1, 1,
                    broken.stream().map(id -> CollectorError.local("attribution failed", id)).toList());
            default -> CollectorIntegrity.healthy();
        };
        var projection = new AsmCoverageFragmentProjector().project(observation,
                FragmentProjectionConfig.v3(revision, "shard", target.toString()), integrity);
        assertTrue(projection.fragment().collectionCompleted());
        assertEquals(3000 - broken.size(), projection.fragment().tests().size());
        if (mode.equals("unsupported")) assertTrue(projection.diagnostics().contains("unsupported-identity:platform-0"));
        if (mode.equals("unowned-error")) assertEquals(List.of("unowned diagnostic"), projection.diagnostics());
        var codec = new ExecutableCoverageFragmentCodec();
        var fragment = new ExecutableCoverageFragmentQualifier().qualify(projection.fragment(), target);
        Files.write(f.fragment(), codec.serialize(fragment));
        var assignment = new ExecutableShardAssignment(1, new CoverageMapRevision(revision), new ShardId("shard"), assigned);
        Files.write(repo.resolve("assignment.json"), new ExecutableShardAssignmentCodec().serialize(assignment));
        var diagnosticFile = f.fragment().resolveSibling("asm-agent-diagnostic.json");
        var diagnostic = JsonParser.parseString(Files.readString(diagnosticFile)).getAsJsonObject();
        diagnostic.getAsJsonObject("configuration").addProperty("revision", revision);
        var errors = new com.google.gson.JsonArray(); projection.diagnostics().forEach(errors::add); diagnostic.add("agentErrors", errors);
        Files.writeString(diagnosticFile, diagnostic.toString());
        set(f.finalizer(), "revision", revision); set(f.aggregator(), "revision", revision);
        f.finalizer().execute();
        var finalized = codec.deserialize(Files.readAllBytes(f.fragment()));
        assertEquals(fragment.tests(), finalized.tests(), "reported sibling coverage must be unchanged");
        ExecutableFragmentAssignmentValidator.validate(finalized, assignment, Set.of());
        assertEquals(broken, finalized.unmapped().stream().map(ExecutableUnmappedTest::test)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(finalized.unmapped().stream().allMatch(u -> u.reason()
                == (mode.equals("failed") ? UnmappedReason.FAILED : UnmappedReason.COLLECTION_FAILED)));
        var evidence = MavenMappingEvidence.read(f.evidence());
        assertEquals(assigned, evidence.executed()); assertTrue(evidence.nonExecuted().isEmpty());
        if (mode.equals("unsupported")) {
            var audit = JsonParser.parseString(Files.readString(f.evidence())).getAsJsonObject().getAsJsonObject("assignmentAbsorption");
            assertEquals(1, audit.get("count").getAsInt());
            assertEquals(broken.iterator().next().toString(), audit.getAsJsonArray("tests").get(0).getAsString());
        }
        f.aggregator().execute();
        var aggregated = codec.deserialize(Files.readAllBytes(f.result()));
        assertEquals(finalized, aggregated);
        var inventory = new ExecutableTestInventory(assignment.revision(), assigned);
        var map = new ExecutableCoverageFragmentJoiner().join(inventory,
                new ExecutableShardPlan(assignment.revision(), Map.of(assignment.shardId(), assigned)),
                List.of(aggregated), Set.of(), Instant.EPOCH, new GeneratorProvenance("test", "ASM", "none", "17"));
        map = ExecutableCoverageMapPublication.alignExpectedInventory(map, inventory, Set.of());
        assertTrue(map.completeness().isComplete());
        var published = new ExecutableCoverageMap(map.schemaVersion(), map.revision(), map.generatedAt(), map.generator(),
                map.tests(), map.unmapped(), map.setupScopes(), map.completeness(), map.statistics(), CoverageMapLifecycleState.PUBLISHED, null);
        Path mapFile = repo.resolve("map.json");
        Files.write(mapFile, new ExecutableCoverageMapCodec().serialize(published));
        var selection = new ExplicitPrSelectorFlow().select(mapFile.toFile(), repo.toFile(),
                HeadTestInventory.atRevision(revision, assigned.stream().map(ExecutableTestIdentity::test).toList()),
                revision, revision, revision, 10, List.of());
        var output = selection.output().orElseThrow(() -> new AssertionError(selection));
        assertEquals(broken.isEmpty() ? "NONE" : "SELECTED", output.getStatus());
        assertEquals(broken.stream().map(id -> id.test().toString()).sorted().toList(), output.getSelectedTests());
        // Retain the actual artifacts, not a count-only summary, for every permanent case.
        Path retained = Path.of("build/stp-unmapped-01", mode); Files.createDirectories(retained);
        Files.copy(f.fragment(), retained.resolve("module-fragment.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(f.evidence(), retained.resolve("execution-evidence-v2.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(mapFile, retained.resolve("published-map.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(retained.resolve("selection.txt"), String.join("\n", output.getSelectedTests()) + "\n");
    }

    private static String git(Path repo, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git", "-C", repo.toString())); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output.trim();
    }
}
