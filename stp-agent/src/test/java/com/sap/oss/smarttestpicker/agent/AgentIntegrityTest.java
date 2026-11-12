// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.runtime.*;
import com.sap.oss.smarttestpicker.runtime.model.*;
import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentIntegrityTest {
    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void spockPlaceholderNamesSurviveFragmentPublication(int schemaVersion, @TempDir Path dir) throws Exception {
        String className = "de.hybris.platform.extensibilityservices.client.HttpResponseStatusUnitTest";
        String methodName = "is5xxError is #expected for valueOf(#code, #desc)";
        var logical = new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(className, methodName);
        var sibling = new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(className, "ordinaryTest");
        var failed = new com.sap.oss.smarttestpicker.coverage.model.TestIdentity(className, "failed feature(#value)");
        var config = AgentConfiguration.parse("output=" + dir.resolve("diagnostic.json")
                + ";fragmentOutput=" + dir.resolve("fragment.json")
                + ";schemaVersion=" + schemaVersion + ";revision=r;shardId=7"
                + (schemaVersion == 3 ? ";executionTarget=ant:cxmc" : ""));
        var runtime = AgentRuntime.install(config, new AgentMetrics(1));
        try {
            for (var test : List.of(logical, sibling, failed)) {
                var identity = new TestIdentity(test.toString(), test.toString(), test.className(),
                        test.methodName(), "", "ant-yunit", config.runId(), "pid-" + ProcessHandle.current().pid());
                runtime.runtimeContext().beginTest(identity);
                runtime.runtimeContext().record(new MethodHitEvent(new MethodIdentity("example.Service", "work", "()V"),
                        new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED)));
                runtime.runtimeContext().endTest(identity, test.equals(failed)
                        ? new TestResult(TestExecutionStatus.FAILED, "AssertionError", "expected failure") : TestResult.successful());
            }
            runtime.writeOutput();
            assertTrue(Files.isRegularFile(dir.resolve("fragment.json")), Files.readString(dir.resolve("diagnostic.json")));
            var bytes = Files.readAllBytes(dir.resolve("fragment.json"));
            var covered = new com.sap.oss.smarttestpicker.coverage.model.MethodIdentity("example.Service", "work", "()V");
            if (schemaVersion == 3) {
                var fragment = new ExecutableCoverageFragmentCodec().deserialize(bytes);
                var target = com.sap.oss.smarttestpicker.coverage.model.ExecutionTarget.parse("ant:cxmc");
                assertTrue(fragment.collectionCompleted());
                assertEquals(Set.of(new ExecutableTestIdentity(target, logical), new ExecutableTestIdentity(target, sibling)),
                        fragment.tests().keySet());
                assertEquals(Set.of(covered), fragment.tests().get(new ExecutableTestIdentity(target, logical)).coveredMethods());
                assertEquals(List.of(new com.sap.oss.smarttestpicker.coverage.model.ExecutableUnmappedTest(
                        new ExecutableTestIdentity(target, failed), com.sap.oss.smarttestpicker.coverage.model.UnmappedReason.FAILED)),
                        fragment.unmapped());
                assertArrayEquals(bytes, new ExecutableCoverageFragmentCodec().serialize(fragment));
            } else {
                var codec = new com.sap.oss.smarttestpicker.coverage.serialization.CoverageFragmentCodec();
                var fragment = codec.deserialize(bytes);
                assertTrue(fragment.collectionCompleted());
                assertEquals(Set.of(logical, sibling), fragment.tests().keySet());
                assertEquals(Set.of(covered), fragment.tests().get(logical).coveredMethods());
                assertEquals(List.of(new com.sap.oss.smarttestpicker.coverage.model.UnmappedTest(
                        failed, com.sap.oss.smarttestpicker.coverage.model.UnmappedReason.FAILED)), fragment.unmapped());
                assertArrayEquals(bytes, codec.serialize(fragment));
            }
        } finally { runtime.abortInitialization(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void structuredLocalErrorSurvivesAgentShutdownAndKeepsSibling(boolean unhealthy, @TempDir Path dir) throws Exception {
        var bad = ExecutableTestIdentity.parse("maven:.::example.Tests#affected");
        var good = ExecutableTestIdentity.parse("maven:.::example.Tests#sibling");
        var config = AgentConfiguration.parse("output=" + dir.resolve("diagnostic.json")
                + ";fragmentOutput=" + dir.resolve("fragment.json")
                + ";schemaVersion=3;revision=r;shardId=one;executionTarget=maven:.");
        var metrics = new AgentMetrics(1);
        if (unhealthy) { metrics.error(); metrics.methodIdCollision(); }
        var runtime = AgentRuntime.install(config, metrics);
        try {
            for (var executable : List.of(bad, good)) {
                var identity = new TestIdentity(executable.toString(), "test", executable.test().className(),
                        executable.test().methodName(), "", "junit-jupiter", config.runId(), "pid-" + ProcessHandle.current().pid());
                runtime.runtimeContext().beginTest(identity);
                runtime.runtimeContext().record(new MethodHitEvent(new MethodIdentity("example.Service", "work", "()V"),
                        new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED)));
                runtime.runtimeContext().endTest(identity, TestResult.successful());
            }
            runtime.error(CollectorError.local("arbitrary diagnostic text", bad));
            runtime.writeOutput();
            var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(dir.resolve("fragment.json")));
            assertTrue(fragment.collectionCompleted());
            assertEquals(Set.of(good), fragment.tests().keySet());
            assertEquals(bad, fragment.unmapped().get(0).test());
            assertTrue(Files.readString(dir.resolve("diagnostic.json")).contains("arbitrary diagnostic text"));
            String diagnostic = Files.readString(dir.resolve("diagnostic.json"));
            for (String counter : List.of("transformationErrors", "methodIdCollisions")) {
                var value = java.util.regex.Pattern.compile("\"" + counter + "\"\\s*:\\s*(\\d+)").matcher(diagnostic);
                assertTrue(value.find(), diagnostic);
                assertEquals(unhealthy ? 1 : 0, Integer.parseInt(value.group(1)));
            }
        } finally { runtime.abortInitialization(); }
    }

    @Test void unresolvedPropagationSitesRetainLegacySeverityWithoutGuessingTests() {
        String[][] calls = {
                {"execute", "(Ljava/lang/Runnable;)V", "executor-attribution-incomplete:"},
                {"fork", "()Ljava/util/concurrent/ForkJoinTask;", "forkjoin-attribution-incomplete:"},
                {"schedule", "(Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;",
                        "scheduled-executor-attribution-incomplete:"}
        };
        for (String[] call : calls) {
            var writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/CallSite", null, "java/lang/Object", null);
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "()V", null, null);
            method.visitCode();
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "missing/ExecutorType", call[0], call[1], false);
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(8, 1); method.visitEnd(); writer.visitEnd();
            var errors = new ArrayList<CollectorError>();
            new ExecutorCallSiteTransformer(errors::add).transform(getClass().getClassLoader(),
                    "example/CallSite", null, null, writer.toByteArray());
            assertEquals(1, errors.size());
            var error = errors.get(0);
            assertEquals(CollectorError.Classification.SITE_LOCAL, error.classification());
            assertEquals(new MethodIdentity("example.CallSite", "invoke", "()V"), error.site());
            assertTrue(error.affectedIdentities().isEmpty());
            assertTrue(error.shardFatal());
            assertTrue(error.message().startsWith(call[2]));
        }
    }
}
