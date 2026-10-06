// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import example.remote.ExecutorCallSiteFixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemoteExecutorCallSiteTransformerTest {
	@Test void wrapsExecutorAndExecutorServiceCallSitesIncludingCustomImplementation() throws Exception {
		RemoteExecutorCallSiteTransformer transformer = transformer(message -> fail(message));
		byte[] transformed = transform(transformer, ExecutorCallSiteFixture.class);
		assertNotNull(transformed);
		AtomicInteger runnableWraps = new AtomicInteger();
		AtomicInteger callableWraps = new AtomicInteger();
		new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
						if (!owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext") || !method.equals("wrap")) return;
						if (desc.equals("(Ljava/lang/Runnable;)Ljava/lang/Runnable;")) runnableWraps.incrementAndGet();
						if (desc.equals("(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Callable;")) callableWraps.incrementAndGet();
					}
				};
			}
		}, 0);
		assertEquals(4, runnableWraps.get(), "execute, submit(Runnable), submit(Runnable,result), and custom Executor");
		assertEquals(1, callableWraps.get());
		assertDoesNotThrow(() -> new DefiningLoader(ExecutorCallSiteFixture.class.getClassLoader(),
				ExecutorCallSiteFixture.class.getName(), transformed).loadClass(ExecutorCallSiteFixture.class.getName()));
	}

	@Test void reportsUnknownExecutorHierarchyWithoutGuessing() {
		List<String> diagnostics = new ArrayList<>();
		RemoteExecutorCallSiteTransformer transformer = transformer(diagnostics::add);
		byte[] generated = unknownExecutorCaller();
		byte[] transformed = transformer.transform(new ClassLoader(null) { }, "example/remote/UnknownCaller", null, null, generated);
		assertNull(transformed, "unknown executor calls must not be rewritten");
		assertEquals(1, diagnostics.size());
		assertTrue(diagnostics.get(0).contains("executor-attribution-incomplete:example.remote.UnknownCaller"), diagnostics.toString());
	}

	@Test void doesNotTransformJdkClassesEvenWhenIncluded() {
		RemoteAgentConfiguration configuration = RemoteAgentConfiguration.parse(
				"output=/tmp/remote-agent-test.json;includes=java.util.concurrent.");
		RemoteExecutorCallSiteTransformer transformer = new RemoteExecutorCallSiteTransformer(configuration, ignored -> { });
		assertNull(transformer.transform(ClassLoader.getSystemClassLoader(), "java/util/concurrent/Executors", null, null,
				new byte[] {0, 0, 0, 0}));
	}

	private static RemoteExecutorCallSiteTransformer transformer(java.util.function.Consumer<String> diagnostics) {
		RemoteAgentConfiguration configuration = RemoteAgentConfiguration.parse(
				"output=/tmp/remote-agent-test.json;includes=example.remote.");
		return new RemoteExecutorCallSiteTransformer(configuration, diagnostics);
	}
	private static byte[] transform(RemoteExecutorCallSiteTransformer transformer, Class<?> fixture) throws Exception {
		String resource = "/" + fixture.getName().replace('.', '/') + ".class";
		try (InputStream input = fixture.getResourceAsStream(resource)) {
			return transformer.transform(fixture.getClassLoader(), fixture.getName().replace('.', '/'), null,
					fixture.getProtectionDomain(), input.readAllBytes());
		}
	}
	private static byte[] unknownExecutorCaller() {
		String missing = "example/remote/missing/UnknownExecutor";
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/remote/UnknownCaller", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke",
				"(Ljava/lang/Object;Ljava/lang/Runnable;)V", null, null);
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitTypeInsn(Opcodes.CHECKCAST, missing);
		method.visitVarInsn(Opcodes.ALOAD, 1);
		method.visitMethodInsn(Opcodes.INVOKEINTERFACE, missing, "execute", "(Ljava/lang/Runnable;)V", true);
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(2, 2);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
	private static final class DefiningLoader extends ClassLoader {
		private final String target;
		private final byte[] bytes;
		private DefiningLoader(ClassLoader parent, String target, byte[] bytes) { super(parent); this.target = target; this.bytes = bytes; }
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.equals(target)) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) loaded = defineClass(name, bytes, 0, bytes.length);
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}
	}
}
