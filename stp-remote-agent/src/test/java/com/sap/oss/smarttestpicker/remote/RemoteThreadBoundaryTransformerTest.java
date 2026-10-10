// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RemoteThreadBoundaryTransformerTest {
    private static final String NAME = "example/threadapp/GeneratedThread";
    @Test void includedSubclassInDelegatingLoaderResolvesTheSharedRuntimeHelper() throws Exception {
        var transformer = transformer();
        var loader = new DefiningLoader(getClass().getClassLoader());
        assertTrue(RemoteThreadBoundaryTransformer.visible(loader));
        byte[] transformed = transformer.transform(loader, NAME, null, null, bytes());
        assertNotNull(transformed);
        Class<?> type = loader.define(transformed);
        AtomicReference<RemoteRequestIdentity> actual = new AtomicReference<>();
        Thread thread = (Thread) type.getConstructor(Runnable.class).newInstance((Runnable) () -> actual.set(RemoteTestContext.currentIdentity()));
        var expected = TestRequests.request("loader-start");
        try (var ignored = RemoteTestContext.enter(expected)) { RemoteThreadContext.start(thread); }
        thread.join(10000); assertFalse(thread.isAlive()); assertEquals(expected, actual.get());
        assertEquals(0, RemoteThreadContext.pendingCount());
    }
    @Test void isolatedLoaderAndExcludedClassesAreNotRewritten() throws Exception {
        byte[] bytes = bytes();
        var isolated = new DefiningLoader(null);
        assertFalse(RemoteThreadBoundaryTransformer.visible(isolated));
        assertNull(transformer().transform(isolated, NAME, null, null, bytes));
        var config = RemoteAgentConfiguration.parse("output=build/thread-isolated.json;includes=example.threadapp.;serviceId=test;revision=test");
        assertNull(new RemoteMethodEntryTransformer(config).transform(isolated, NAME, null, null, bytes),
                "the earlier method recorder must also avoid injecting an inaccessible helper");
        assertNull(new RemoteExecutorCallSiteTransformer(config).transform(isolated, NAME, null, null, bytes));
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        Thread original = (Thread) isolated.define(bytes).getConstructor(Runnable.class).newInstance((Runnable) () -> ran.set(true));
        original.start(); original.join(10000); assertFalse(original.isAlive()); assertTrue(ran.get());
        assertNull(transformer().transform(getClass().getClassLoader(), "outside/GeneratedThread", null, null, bytes));
        assertNull(transformer().transform(null, "java/lang/Thread", null, null, bytes));
    }
    private static RemoteThreadBoundaryTransformer transformer() {
        return new RemoteThreadBoundaryTransformer(RemoteAgentConfiguration.parse(
                "output=build/thread-transform.json;includes=example.threadapp.;serviceId=test;revision=test"));
    }
    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) { super(parent); }
        Class<?> define(byte[] bytes) { return defineClass(NAME.replace('/', '.'), bytes, 0, bytes.length); }
    }
    private static byte[] bytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, NAME, null, "java/lang/Thread", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "body", "Ljava/lang/Runnable;", null, null).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Runnable;)V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, NAME, "body", "Ljava/lang/Runnable;");
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(2, 2); ctor.visitEnd();
        MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        run.visitCode(); run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitFieldInsn(Opcodes.GETFIELD, NAME, "body", "Ljava/lang/Runnable;");
        run.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        run.visitInsn(Opcodes.RETURN); run.visitMaxs(1, 1); run.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
}
