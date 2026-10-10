// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import io.opentelemetry.context.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class RemoteReflectiveCallbacksTest {
    static final ContextKey<String> EXTRA = ContextKey.named("reflection-test");
    static final RemoteRequestIdentity A = new RemoteRequestIdentity("suite-A", "test-A", "request-A");
    static final RemoteRequestIdentity B = new RemoteRequestIdentity("suite-B", "test-B", "request-B");

    @Test void transformsOnlyIncludedReflectionArgumentsAndPreservesInvocationInstructions() throws Exception {
        String name = "example/indirect/IncludedCalls";
        byte[] original;
        ClassLoader loader = getClass().getClassLoader();
        try (var input = loader.getResourceAsStream(name + ".class")) { original = input.readAllBytes(); }
        var transformer = new RemoteExecutorCallSiteTransformer(RemoteAgentConfiguration.parse(
                "output=build/unused-reflection.json;includes=example.indirect.;serviceId=test;revision=test"));
        byte[] transformed = transformer.transform(loader, name, null, null, original);
        assertNotNull(transformed);
        ClassNode before = new ClassNode(), after = new ClassNode();
        new ClassReader(original).accept(before, 0); new ClassReader(transformed).accept(after, 0);
        java.util.List<String> originalInvocations = new java.util.ArrayList<>(), actualInvocations = new java.util.ArrayList<>();
        int helpers = 0;
        for (MethodNode method : before.methods) for (var insn : method.instructions)
            if (insn instanceof MethodInsnNode call && (call.owner.equals("java/lang/reflect/Method") || call.owner.equals("java/lang/invoke/MethodHandle")))
                originalInvocations.add(call.getOpcode() + call.owner + call.name + call.desc);
        for (MethodNode method : after.methods) for (var insn : method.instructions) if (insn instanceof MethodInsnNode call) {
            if (call.owner.equals("java/lang/reflect/Method") || call.owner.equals("java/lang/invoke/MethodHandle"))
                actualInvocations.add(call.getOpcode() + call.owner + call.name + call.desc);
            if (call.owner.endsWith("RemoteReflectiveCallbacks") && call.name.equals("arguments")) helpers++;
        }
        assertEquals(originalInvocations, actualInvocations, "original access checks, invokeExact types, bindTo and exception wrapping retained");
        assertEquals(1, helpers, "exactly one owner for the reflection callback boundary");
        assertNull(transformer.transform(loader, "example/indirectsupport/OutsideCalls", null, null, original));
        assertNull(transformer.transform(new ClassLoader(null) { }, name, null, null, original), "unavailable helper: skip safely");
    }

    @Test void capturesFullContextPerInvocationWithoutMutatingArgumentArrayAndRestoresPreviousContext() throws Exception {
        Method method = CompletableFuture.class.getMethod("thenApply", Function.class);
        CompletableFuture<String> source = new CompletableFuture<>();
        Function<String, String> callback = value -> {
            assertEquals(A, RemoteTestContext.currentIdentity()); assertEquals("extra-A", Context.current().get(EXTRA));
            if (value.equals("fail")) throw new IllegalStateException("original");
            return value + "-result";
        };
        Object[] original = {callback}; Object[] prepared;
        try (Scope ignored = A.toBaggage(Context.root()).with(EXTRA, "extra-A").makeCurrent()) {
            prepared = RemoteReflectiveCallbacks.arguments(method, source, original);
        }
        assertNotSame(original, prepared); assertSame(callback, original[0]);
        @SuppressWarnings("unchecked") Function<String, String> wrapped = (Function<String, String>) prepared[0];
        try (Scope ignored = B.toBaggage(Context.root()).with(EXTRA, "extra-B").makeCurrent()) {
            assertEquals("input-result", wrapped.apply("input"));
            assertEquals(B, RemoteTestContext.currentIdentity()); assertEquals("extra-B", Context.current().get(EXTRA));
            assertThrows(IllegalStateException.class, () -> wrapped.apply("fail"));
            assertEquals(B, RemoteTestContext.currentIdentity()); assertEquals("extra-B", Context.current().get(EXTRA));
        }
    }

    @Test void headerlessRegistrationSuppressesCompleterIdentityAndSameCallbackCanBeReused() throws Exception {
        Method method = CompletableFuture.class.getMethod("thenApply", Function.class);
        Function<Object, RemoteRequestIdentity> callback = ignored -> RemoteTestContext.currentIdentity();
        Object[] args = {callback}; Object[] anonymous, attributed;
        var source = new CompletableFuture<>();
        try (Scope ignored = Context.root().makeCurrent()) { anonymous = RemoteReflectiveCallbacks.arguments(method, source, args); }
        try (Scope ignored = A.toBaggage(Context.root()).makeCurrent()) { attributed = RemoteReflectiveCallbacks.arguments(method, source, args); }
        try (Scope ignored = B.toBaggage(Context.root()).makeCurrent()) {
            assertNull(((Function<?, ?>) anonymous[0]).apply(null));
            assertEquals(A, ((Function<?, ?>) attributed[0]).apply(null));
            assertEquals(B, RemoteTestContext.currentIdentity());
        }
        assertSame(callback, args[0]);
    }

    @Test void onlyVerifiedSignaturesAndReceiversAreAdapted() throws Exception {
        Function<Object, Object> callback = value -> value;
        Object[] args = {callback}; var future = new CompletableFuture<>();
        Method apply = CompletableFuture.class.getMethod("thenApply", Function.class);
        assertSame(args, RemoteReflectiveCallbacks.arguments(null, future, args));
        assertNull(RemoteReflectiveCallbacks.arguments(apply, future, null));
        assertSame(args, RemoteReflectiveCallbacks.arguments(apply, null, args));
        assertSame(args, RemoteReflectiveCallbacks.arguments(apply, new Object(), args));
        assertSame(args, RemoteReflectiveCallbacks.arguments(apply, new CustomFuture<>(), args));
        assertSame(args, RemoteReflectiveCallbacks.arguments(CompletableFuture.class.getMethod("thenCompose", Function.class), future, args));
        for (Object[] bad : new Object[][]{{}, {null}, {new Object()}, {callback, "extra"}})
            assertSame(bad, RemoteReflectiveCallbacks.arguments(apply, future, bad));
        Object[] invalidExecutor = {callback, new Object()};
        assertSame(invalidExecutor, RemoteReflectiveCallbacks.arguments(CompletableFuture.class.getMethod("thenApplyAsync", Function.class, Executor.class), future, invalidExecutor));
    }

    @Test void covariantConcreteCallbackArrayRemainsAValidReflectionArgument() throws Exception {
        ConcreteFunction callback = new ConcreteFunction();
        ConcreteFunction[] arguments = {callback};
        Method method = CompletableFuture.class.getMethod("thenApply", Function.class);
        CompletableFuture<String> source = CompletableFuture.completedFuture("input");
        Object[] prepared = RemoteReflectiveCallbacks.arguments(method, source, arguments);
        assertSame(callback, arguments[0]);
        assertEquals(Object[].class, prepared.getClass());
        assertEquals("input-result", ((CompletableFuture<?>) method.invoke(source, prepared)).join());
    }

    @Test void periodicArgumentsAndPrimitiveWideningRetainReflectionSemantics() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            Method method = ScheduledExecutorService.class.getMethod("scheduleAtFixedRate", Runnable.class, long.class, long.class, TimeUnit.class);
            Runnable task = () -> { };
            Object[] original = {task, 0, 1, TimeUnit.SECONDS};
            Object[] prepared = RemoteReflectiveCallbacks.arguments(method, executor, original);
            assertNotSame(prepared[0], task); assertSame(task, original[0]);
            for (int i = 1; i < original.length; i++) assertSame(original[i], prepared[i]);
            Future<?> future = (Future<?>) method.invoke(executor, prepared); future.cancel(false);
            Object[] bad = {task, 0.0, 1, TimeUnit.SECONDS};
            assertSame(bad, RemoteReflectiveCallbacks.arguments(method, executor, bad));
            assertThrows(IllegalArgumentException.class, () -> method.invoke(executor, bad));
            Object[] zeroPeriod = {task, 0, 0, TimeUnit.SECONDS};
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(executor, RemoteReflectiveCallbacks.arguments(method, executor, zeroPeriod)));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        } finally { executor.shutdownNow(); }
    }
    static final class CustomFuture<T> extends CompletableFuture<T> { }
    static final class ConcreteFunction implements Function<String, String> {
        @Override public String apply(String input) { return input + "-result"; }
    }
}
