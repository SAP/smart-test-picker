// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.coverage.*;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.*;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableFragmentAssignmentValidator;
import com.sap.oss.smarttestpicker.runtime.model.*;
import com.sap.oss.smarttestpicker.selector.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectorIntegrityTest {
    private static final ExecutableTestIdentity BAD = ExecutableTestIdentity.parse("maven:.::example.Tests#affected");
    private static final ExecutableTestIdentity GOOD = ExecutableTestIdentity.parse("maven:.::example.Tests#sibling");
    private static final com.sap.oss.smarttestpicker.runtime.model.MethodIdentity SITE =
            new com.sap.oss.smarttestpicker.runtime.model.MethodIdentity("example.Service", "work", "()V");

    private RuntimeEventAggregator observation() {
        var runtime = new RuntimeEventAggregator("run", "jvm");
        for (var executable : List.of(BAD, GOOD)) {
            var test = new com.sap.oss.smarttestpicker.runtime.model.TestIdentity(executable.toString(), "display",
                    executable.test().className(), executable.test().methodName(), "", "junit-jupiter", "run", "jvm");
            runtime.beginTest(test);
            runtime.record(test, new MethodHitEvent(SITE, new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED)));
            runtime.endTest(test, TestResult.successful());
        }
        return runtime;
    }

    private FragmentProjectionResult project(CollectorIntegrity integrity) {
        return new AsmCoverageFragmentProjector().project(observation().snapshot(),
                FragmentProjectionConfig.v3("revision", "one", "maven:."), integrity);
    }

    private CollectorIntegrity integrity(CollectorError error) {
        return new CollectorIntegrity(true, 0, 0, List.of(error));
    }

    @Test void fatalErrorDiscardsBothPassingTests() {
        var result = project(integrity(CollectorError.fatal("anything at all")));
        assertFalse(result.fragment().collectionCompleted());
        assertTrue(result.fragment().tests().isEmpty());
        assertEquals(Set.of(BAD.test(), GOOD.test()), result.fragment().unmapped().stream()
                .map(UnmappedTest::test).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.fragment().unmapped().stream().allMatch(u -> u.reason() == UnmappedReason.COLLECTION_FAILED));
        assertEquals(List.of("anything at all"), result.diagnostics());
    }

    @Test void localErrorPreservesPassingSiblingAndCompletion() {
        var result = project(integrity(CollectorError.local("local failure", BAD)));
        assertTrue(result.fragment().collectionCompleted());
        assertEquals(List.of(new UnmappedTest(BAD.test(), UnmappedReason.COLLECTION_FAILED)), result.fragment().unmapped());
        assertEquals(Set.of(GOOD.test()), result.fragment().tests().keySet());
        assertEquals(Set.of("example.Service"), result.fragment().tests().get(GOOD.test()).coveredClasses());
        assertEquals(1, result.fragment().tests().get(GOOD.test()).coveredMethods().size());
        assertEquals(List.of("local failure"), result.diagnostics());
    }

    @Test void classificationNeverDependsOnMessagePrefix() {
        String text = "transformation-error:looks-fatal-but-is-explicitly-owned";
        assertTrue(project(integrity(CollectorError.local(text, BAD))).fragment().collectionCompleted());
        assertFalse(project(integrity(CollectorError.fatal(text))).fragment().collectionCompleted());
        assertTrue(project(integrity(CollectorError.local("unrecognized future message", BAD))).fragment().collectionCompleted());
    }

    @Test void siteWithoutProvenOwnersRemainsFatalEvenWhenItsMethodWasObserved() {
        var error = CollectorError.site("unresolved site", SITE);
        assertTrue(integrity(error).shardFatal());
        assertFalse(project(integrity(error)).fragment().collectionCompleted());
    }

    @Test void siteWithExplicitProvenOwnersIsLocal() {
        var error = new CollectorError(CollectorError.Classification.SITE_LOCAL, "resolved site", Set.of(BAD), SITE);
        var result = project(integrity(error));
        assertTrue(result.fragment().collectionCompleted());
        assertEquals(Set.of(GOOD.test()), result.fragment().tests().keySet());
        assertEquals(BAD.test(), result.fragment().unmapped().get(0).test());
    }

    @Test void wrongTargetOrUnobservedIdentityCannotSilentlyDisappear() {
        for (var identity : List.of(ExecutableTestIdentity.parse("maven:other::example.Tests#affected"),
                ExecutableTestIdentity.parse("maven:.::example.Tests#absent"))) {
            var result = project(integrity(CollectorError.local("owned elsewhere", identity)));
            assertFalse(result.fragment().collectionCompleted());
            assertTrue(result.fragment().tests().isEmpty());
            assertTrue(result.diagnostics().contains("owned elsewhere"));
            assertTrue(result.diagnostics().contains("unresolved-error-ownership:" + identity));
        }
    }

    @Test void localErrorCannotMaskFatalCountersOrMissingRuntime() {
        for (var integrity : List.of(new CollectorIntegrity(false, 0, 0, List.of()),
                new CollectorIntegrity(true, 1, 0, List.of(CollectorError.local("local", BAD))),
                new CollectorIntegrity(true, 0, 1, List.of(CollectorError.local("local", BAD))))) {
            assertFalse(project(integrity).fragment().collectionCompleted());
            assertTrue(project(integrity).fragment().tests().isEmpty());
        }
    }

    @Test void unsupportedIdentityRemainsFatalWithoutInventingOwnership() {
        var runtime = observation();
        var unknown = new com.sap.oss.smarttestpicker.runtime.model.TestIdentity("unsupported", "display",
                null, null, null, "vintage", "run", "jvm");
        runtime.beginTest(unknown); runtime.endTest(unknown, TestResult.successful());
        var result = new AsmCoverageFragmentProjector().project(runtime.snapshot(),
                FragmentProjectionConfig.v3("revision", "one", "maven:."), CollectorIntegrity.healthy());
        assertFalse(result.fragment().collectionCompleted());
        assertTrue(result.fragment().tests().isEmpty());
        assertEquals(2, result.fragment().unmapped().size());
        assertTrue(result.diagnostics().contains("unsupported-identity:unsupported"));
    }

    @Test void noExecutableOwnerIsGuessedForLogicalSchema() {
        var result = new AsmCoverageFragmentProjector().project(observation().snapshot(),
                FragmentProjectionConfig.v2("revision", "one"), integrity(CollectorError.local("local", BAD)));
        assertFalse(result.fragment().collectionCompleted());
    }

    @Test void localErrorRequiresAnExplicitIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new CollectorError(
                CollectorError.Classification.IDENTITY_LOCAL, "missing", Set.of(), null));
    }

    @Test void unmappedSurvivesCodecValidatorCompletenessJoinAndRealSelector(@TempDir Path repo) throws Exception {
        git(repo, "init"); git(repo, "config", "user.name", "STP test");
        git(repo, "config", "user.email", "stp@example.invalid");
        Files.writeString(repo.resolve("README.txt"), "base");
        git(repo, "add", "."); git(repo, "commit", "-m", "base");
        String revision = git(repo, "rev-parse", "HEAD");
        var projected = new AsmCoverageFragmentProjector().project(observation().snapshot(),
                FragmentProjectionConfig.v3(revision, "one", "maven:."), integrity(CollectorError.local("lost attribution", BAD)));
        var fragment = new ExecutableCoverageFragmentQualifier().qualify(projected.fragment(), BAD.target());
        var codec = new ExecutableCoverageFragmentCodec();
        fragment = codec.deserialize(codec.serialize(fragment));
        var assignment = new ExecutableShardAssignment(1, fragment.revision(), fragment.shardId(), Set.of(BAD, GOOD));
        ExecutableFragmentAssignmentValidator.validate(fragment, assignment, Set.of());
        var inventory = new ExecutableTestInventory(fragment.revision(), Set.of(BAD, GOOD));
        var plan = new ExecutableShardPlan(fragment.revision(), Map.of(fragment.shardId(), Set.of(BAD, GOOD)));
        var joined = new ExecutableCoverageFragmentJoiner().join(inventory, plan, List.of(fragment), Set.of(),
                Instant.EPOCH, new GeneratorProvenance("test", "asm", "none", "17"));
        assertTrue(joined.completeness().isComplete());
        assertEquals(Set.of(BAD, GOOD), joined.completeness().reportedTests());
        assertTrue(joined.completeness().missingTests().isEmpty());
        var published = new ExecutableCoverageMap(joined.schemaVersion(), joined.revision(), joined.generatedAt(),
                joined.generator(), joined.tests(), joined.unmapped(), joined.setupScopes(), joined.completeness(),
                joined.statistics(), CoverageMapLifecycleState.PUBLISHED, null);
        Path map = repo.resolve("map.json");
        Files.write(map, new ExecutableCoverageMapCodec().serialize(published));
        var selection = new ExplicitPrSelectorFlow().select(map.toFile(), repo.toFile(),
                HeadTestInventory.atRevision(revision, List.of(BAD.test(), GOOD.test())),
                revision, revision, revision, 10, List.of());
        var output = selection.output().orElseThrow(() -> new AssertionError(selection));
        assertEquals("SELECTED", output.getStatus());
        assertEquals(List.of(BAD.test().toString()), output.getSelectedTests());
        assertEquals("COLLECTION_FAILED", output.getUnmappedTests().get(BAD.test().toString()));
    }

    private String git(Path repo, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output.trim();
    }
}
