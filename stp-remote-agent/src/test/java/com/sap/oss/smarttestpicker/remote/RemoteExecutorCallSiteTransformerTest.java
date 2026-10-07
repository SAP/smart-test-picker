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
	@Test void leavesExecutorAndExecutorServicePropagationToOpenTelemetry() throws Exception {
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
		assertEquals(6, runnableWraps.get(), "only direct Thread constructors and late thenRunAsync stages use STP fallback");
		assertEquals(0, callableWraps.get(), "Executor and Callable propagation belong to OpenTelemetry");
		assertDoesNotThrow(() -> new DefiningLoader(ExecutorCallSiteFixture.class.getClassLoader(),
				ExecutorCallSiteFixture.class.getName(), transformed).loadClass(ExecutorCallSiteFixture.class.getName()));
	}

	@Test void wrapsOnlyTheSupportedCompletableFutureAsyncStages() throws Exception {
		RemoteExecutorCallSiteTransformer transformer = transformer(message -> fail(message));
		byte[] transformed = transform(transformer, ExecutorCallSiteFixture.class);
		assertNotNull(transformed);
		java.util.Map<String, Integer> hooks = new java.util.HashMap<>();
		new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
						if (owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext")) hooks.merge(method + desc, 1, Integer::sum);
					}
				};
			}
		}, 0);
		assertEquals(6, hooks.get("wrap(Ljava/lang/Runnable;)Ljava/lang/Runnable;"));
		assertNull(hooks.get("wrapSupplier(Ljava/util/function/Supplier;)Ljava/util/function/Supplier;"),
				"OpenTelemetry handles runAsync and supplyAsync callbacks");
		assertEquals(2, hooks.get("wrapFunction(Ljava/util/function/Function;)Ljava/util/function/Function;"));
		assertDoesNotThrow(() -> new DefiningLoader(ExecutorCallSiteFixture.class.getClassLoader(),
				ExecutorCallSiteFixture.class.getName(), transformed).loadClass(ExecutorCallSiteFixture.class.getName()),
				"explicit-executor stack rewriting must leave verifier-valid bytecode");
	}

	@Test void wrapsScheduledExecutorCallSitesBecauseTheAgentDoesNotCaptureTheirSubmissionContext() throws Exception {
		RemoteExecutorCallSiteTransformer transformer = transformer(message -> fail(message));
		byte[] transformed = transform(transformer, ExecutorCallSiteFixture.class);
		assertNotNull(transformed);
		java.util.Map<String, Integer> bridges = new java.util.HashMap<>();
		new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
						if (owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext")
								&& (method.equals("schedule") || method.equals("scheduleAtFixedRate") || method.equals("scheduleWithFixedDelay")))
							bridges.merge(method + desc, 1, Integer::sum);
					}
				};
			}
		}, 0);
		assertEquals(2, bridges.get("schedule(Ljava/util/concurrent/ScheduledExecutorService;Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;"));
		assertEquals(1, bridges.get("schedule(Ljava/util/concurrent/ScheduledExecutorService;Ljava/util/concurrent/Callable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;"));
		assertEquals(1, bridges.get("scheduleAtFixedRate(Ljava/util/concurrent/ScheduledExecutorService;Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;"));
		assertEquals(1, bridges.get("scheduleWithFixedDelay(Ljava/util/concurrent/ScheduledExecutorService;Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;"));
		assertDoesNotThrow(() -> new DefiningLoader(ExecutorCallSiteFixture.class.getClassLoader(),
				ExecutorCallSiteFixture.class.getName(), transformed).loadClass(ExecutorCallSiteFixture.class.getName()));
	}

	@Test void wrapsRunnableArgumentsForAllSupportedThreadConstructors() throws Exception {
		RemoteExecutorCallSiteTransformer transformer = transformer(message -> fail(message));
		byte[] transformed = transform(transformer, ExecutorCallSiteFixture.class);
		assertNotNull(transformed);
		java.util.Map<String, Integer> wrapsByMethod = new java.util.HashMap<>();
		new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
			private String methodName;
			@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				methodName = name;
				return new MethodVisitor(Opcodes.ASM9) {
					@Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
						if (owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext") && method.equals("wrap")
								&& desc.equals("(Ljava/lang/Runnable;)Ljava/lang/Runnable;")) wrapsByMethod.merge(methodName, 1, Integer::sum);
					}
				};
			}
		}, 0);
		assertEquals(1, wrapsByMethod.get("thread"));
		assertEquals(1, wrapsByMethod.get("namedThread"));
		assertEquals(1, wrapsByMethod.get("groupedThread"));
		assertEquals(1, wrapsByMethod.get("namedGroupedThread"));
		assertDoesNotThrow(() -> new DefiningLoader(ExecutorCallSiteFixture.class.getClassLoader(),
				ExecutorCallSiteFixture.class.getName(), transformed).loadClass(ExecutorCallSiteFixture.class.getName()));
	}

	@Test void recognizesVirtualThreadAndBuilderCallSitesWithoutCompilingAgainstThoseApis() throws Exception {
		RemoteExecutorCallSiteTransformer transformer = transformer(message -> fail(message));
		byte[] generated = virtualThreadCaller();
		byte[] transformed = transformer.transform(ExecutorCallSiteFixture.class.getClassLoader(),
				"example/remote/VirtualThreadCallSiteFixture", null, null, generated);
		assertNotNull(transformed);
		AtomicInteger wraps = new AtomicInteger();
		new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
						if (owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext") && method.equals("wrap")) wraps.incrementAndGet();
					}
				};
			}
		}, 0);
		assertEquals(2, wraps.get());
		if (supportsVirtualThreads()) assertDoesNotThrow(() -> new DefiningLoader(ExecutorCallSiteFixture.class.getClassLoader(),
				"example.remote.VirtualThreadCallSiteFixture", transformed).loadClass("example.remote.VirtualThreadCallSiteFixture"));
	}

	@Test void functionWrapperCapturesAndRestoresContextEvenOnFailure() throws Exception {
		var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			RemoteTestContext.Scope scope = RemoteTestContext.enter(TestRequests.request("cf-A"));
			java.util.function.Function<String, String> function = RemoteTestContext.wrapFunction(value -> value + ":" + RemoteTestContext.currentId());
			scope.close();
			assertEquals("worker-prior", executor.submit(() -> {
				try (RemoteTestContext.Scope ignored = RemoteTestContext.enter(TestRequests.request("worker-prior"))) {
					String result = function.apply("input");
					assertEquals("worker-prior", RemoteTestContext.currentId());
					return RemoteTestContext.currentId();
				}
			}).get(), "worker context must be restored after function callback");
			try (RemoteTestContext.Scope ignored = RemoteTestContext.enter(TestRequests.request("worker-prior"))) {
				assertEquals("input:cf-A", function.apply("input"));
				assertEquals("worker-prior", RemoteTestContext.currentId());
			}
			try (RemoteTestContext.Scope ignored = RemoteTestContext.enter(TestRequests.request("cf-B"))) {
				var failure = RemoteTestContext.wrapFunction((String value) -> { throw new IllegalStateException("expected"); });
				String restored = executor.submit(() -> {
					try (RemoteTestContext.Scope worker = RemoteTestContext.enter(TestRequests.request("worker-prior"))) {
						try { failure.apply("value"); } catch (IllegalStateException expected) { }
						return RemoteTestContext.currentId();
					}
				}).get();
				assertEquals("worker-prior", restored, "worker context must be restored even when supplier fails");
			}
		} finally { executor.shutdownNow(); }
	}

	@Test void leavesUnknownExecutorHierarchyToOpenTelemetryWithoutGuessing() {
		List<String> diagnostics = new ArrayList<>();
		RemoteExecutorCallSiteTransformer transformer = transformer(diagnostics::add);
		byte[] generated = unknownExecutorCaller();
		byte[] transformed = transformer.transform(new ClassLoader(null) { }, "example/remote/UnknownCaller", null, null, generated);
		assertNull(transformed, "unknown executor calls must not be rewritten");
		assertTrue(diagnostics.isEmpty());
	}

	@Test void leavesUnknownScheduledExecutorHierarchyToOpenTelemetryWithoutGuessing() {
		List<String> diagnostics = new ArrayList<>();
		RemoteExecutorCallSiteTransformer transformer = transformer(diagnostics::add);
		String missing = "example/remote/missing/UnknownScheduledExecutor";
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/remote/UnknownScheduledCaller", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke",
				"(Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/util/concurrent/ScheduledFuture;", null, null);
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitTypeInsn(Opcodes.CHECKCAST, missing);
		method.visitVarInsn(Opcodes.ALOAD, 1);
		method.visitInsn(Opcodes.LCONST_0);
		method.visitFieldInsn(Opcodes.GETSTATIC, "java/util/concurrent/TimeUnit", "MILLISECONDS", "Ljava/util/concurrent/TimeUnit;");
		method.visitMethodInsn(Opcodes.INVOKEINTERFACE, missing, "schedule",
				"(Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;", true);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(5, 2);
		method.visitEnd();
		writer.visitEnd();
		byte[] generated = writer.toByteArray();
		assertNull(transformer.transform(new ClassLoader(null) { }, "example/remote/UnknownScheduledCaller", null, null, generated));
		assertTrue(diagnostics.isEmpty());
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
	private static byte[] virtualThreadCaller() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/remote/VirtualThreadCallSiteFixture", null, "java/lang/Object", null);
		MethodVisitor virtual = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "startVirtual",
				"(Ljava/lang/Runnable;)Ljava/lang/Thread;", null, null);
		virtual.visitCode();
		virtual.visitVarInsn(Opcodes.ALOAD, 0);
		virtual.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "startVirtualThread",
				"(Ljava/lang/Runnable;)Ljava/lang/Thread;", false);
		virtual.visitInsn(Opcodes.ARETURN);
		virtual.visitMaxs(1, 1); virtual.visitEnd();
		MethodVisitor builder = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "builderStart",
				"(Ljava/lang/Runnable;)Ljava/lang/Thread;", null, null);
		builder.visitCode();
		builder.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "ofVirtual",
				"()Ljava/lang/Thread$Builder$OfVirtual;", false);
		builder.visitVarInsn(Opcodes.ALOAD, 0);
		builder.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Thread$Builder", "start",
				"(Ljava/lang/Runnable;)Ljava/lang/Thread;", true);
		builder.visitInsn(Opcodes.ARETURN);
		builder.visitMaxs(2, 1); builder.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
	private static boolean supportsVirtualThreads() {
		try { Thread.class.getMethod("startVirtualThread", Runnable.class); Thread.class.getMethod("ofVirtual"); return true; }
		catch (NoSuchMethodException ignored) { return false; }
	}
	private record HeaderRequest(String header) {
		public String getHeader(String name) { return header; }
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
