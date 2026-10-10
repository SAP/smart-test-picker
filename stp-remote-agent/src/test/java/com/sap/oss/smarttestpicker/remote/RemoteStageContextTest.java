// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import example.cf.ObservedStages;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class RemoteStageContextTest {
    @Test void allFunctionalWrappersCaptureFullContextAndRestorePriorContextIncludingErrors() {
        List<Function<Runnable, Runnable>> wrappers = List.of(
                RemoteTestContext::wrap,
                action -> { var wrapped = RemoteTestContext.wrapFunction(value -> { action.run(); return value; }); return () -> assertEquals("value", wrapped.apply("value")); },
                action -> { var wrapped = RemoteTestContext.wrapConsumer(value -> action.run()); return () -> wrapped.accept("value"); },
                action -> { var wrapped = RemoteTestContext.wrapBiFunction((a, b) -> { action.run(); return a + ":" + b; }); return () -> assertEquals("a:b", wrapped.apply("a", "b")); },
                action -> { var wrapped = RemoteTestContext.wrapBiConsumer((a, b) -> action.run()); return () -> wrapped.accept("a", "b"); });
        RemoteRequestIdentity identityA = TestRequests.request("A");
        ContextKey<String> extra = ContextKey.named("wrapper-extra");
        for (var factory : wrappers) for (boolean anonymous : List.of(false, true)) for (int failure = 0; failure < 3; failure++) {
            Context captured = Context.root().with(extra, "A");
            if (!anonymous) captured = identityA.toBaggage(captured);
            int outcome = failure;
            Context owner = captured;
            Runnable wrapped;
            try (var ignored = owner.makeCurrent()) {
                wrapped = factory.apply(() -> {
                    assertSame(owner, Context.current());
                    assertEquals(anonymous ? null : identityA, RemoteTestContext.currentIdentity());
                    if (outcome == 1) throw new IllegalStateException("original");
                    if (outcome == 2) throw new AssertionError("original");
                });
            }
            Context previous = TestRequests.request("B").toBaggage(Context.root().with(extra, "B"));
            try (var ignored = previous.makeCurrent()) {
                if (failure == 0) wrapped.run();
                else if (failure == 1) assertEquals("original", assertThrows(IllegalStateException.class, wrapped::run).getMessage());
                else assertEquals("original", assertThrows(AssertionError.class, wrapped::run).getMessage());
                assertSame(previous, Context.current());
            }
        }
        assertNull(RemoteTestContext.wrapFunction(null));
        assertNull(RemoteTestContext.wrapConsumer(null));
        assertNull(RemoteTestContext.wrapBiFunction(null));
        assertNull(RemoteTestContext.wrapBiConsumer(null));
    }

    @Test void everySupportedCallHasExactlyOneWrapperAndPreservesInvocationDescriptor() throws Exception {
        var config = RemoteAgentConfiguration.parse("output=build/cf-transform.json;includes=example.cf.;serviceId=test;revision=test");
        var transformer = new RemoteExecutorCallSiteTransformer(config);
        byte[] bytes;
        try (var stream = ObservedStages.class.getResourceAsStream("ObservedStages.class")) { bytes = stream.readAllBytes(); }
        var before = new ClassNode();
        new ClassReader(bytes).accept(before, 0);
        var after = new ClassNode();
        new ClassReader(transformer.transform(ObservedStages.class.getClassLoader(), "example/cf/ObservedStages", null, null, bytes)).accept(after, 0);
        List<String> original = new ArrayList<>(), transformed = new ArrayList<>();
        int wraps = 0;
        for (var method : before.methods) for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/concurrent/CompletableFuture")) original.add(call.name + call.desc + call.getOpcode());
        for (var method : after.methods) for (var insn : method.instructions) if (insn instanceof MethodInsnNode call) {
            if (call.owner.equals("java/util/concurrent/CompletableFuture")) transformed.add(call.name + call.desc + call.getOpcode());
            if (call.owner.endsWith("/RemoteTestContext")) wraps++;
        }
        assertEquals(40, wraps, "39 overloads + deferred-chain continuation, no duplicate old adapters");
        assertEquals(original, transformed);
        assertNull(transformer.transform(null, "java/util/concurrent/CompletableFuture", null, null, bytes));
        assertNull(transformer.transform(getClass().getClassLoader(), "outside/Includes", null, null, bytes));
    }
}
