// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class RemoteBoundaryTransformerTest {
	@Test void recognizesAndWrapsBothServletNamespaces() throws Exception {
		RemoteHttpBoundaryTransformer transformer = new RemoteHttpBoundaryTransformer("X-STP-Test-Execution-Id");
		assertValidTransformed(transformer, example.remote.javax.JavaxFilterChainFixture.class);
		assertValidTransformed(transformer, example.remote.jakarta.JakartaFilterChainFixture.class);
		assertValidTransformed(transformer, example.remote.javax.JavaxServletFixture.class);
		assertValidTransformed(transformer, example.remote.jakarta.JakartaServletFixture.class);
		assertValidTransformed(transformer, example.remote.javax.JavaxRequestListenerFixture.class);
		assertValidTransformed(transformer, example.remote.jakarta.JakartaRequestListenerFixture.class);
		assertAsyncTaskWrapped(transformer, example.remote.javax.JavaxAsyncContextFixture.class);
		assertAsyncTaskWrapped(transformer, example.remote.jakarta.JakartaAsyncContextFixture.class);
		assertAsyncListenerCallbacksWrapped(transformer, example.remote.javax.JavaxAsyncListenerFixture.class);
		assertAsyncListenerCallbacksWrapped(transformer, example.remote.jakarta.JakartaAsyncListenerFixture.class);
		assertIoBoundariesWrapped(transformer, example.remote.javax.JavaxIoFixtures.Reader.class,
				example.remote.javax.JavaxIoFixtures.Writer.class, example.remote.javax.JavaxIoFixtures.Input.class,
				example.remote.javax.JavaxIoFixtures.Output.class);
		assertIoBoundariesWrapped(transformer, example.remote.jakarta.JakartaIoFixtures.Reader.class,
				example.remote.jakarta.JakartaIoFixtures.Writer.class, example.remote.jakarta.JakartaIoFixtures.Input.class,
				example.remote.jakarta.JakartaIoFixtures.Output.class);
	}

	@Test void requestScopesRestoreParentAndSuppressMissingIds() {
		Object requestA = (HeaderRequest) name -> "test-A";
		Object noHeader = (HeaderRequest) name -> null;
		try (var outer = RemoteTestContext.enter(requestA, "X-STP-Test-Execution-Id")) {
			assertEquals("test-A", RemoteTestContext.currentId());
			try (var nested = RemoteTestContext.enter(noHeader, "X-STP-Test-Execution-Id")) {
				assertNull(RemoteTestContext.currentId());
			}
			assertEquals("test-A", RemoteTestContext.currentId());
		}
		assertNull(RemoteTestContext.currentId());
	}

	@Test void acceptedRequestAttributeWinsOverConflictingRedispatchHeader() {
		MutableRequest request = new MutableRequest("test-original");
		try (var initial = RemoteTestContext.enter(request, "X-STP-Test-Execution-Id")) {
			assertEquals("test-original", RemoteTestContext.currentId());
		}
		assertEquals("test-original", request.attributes.get("com.sap.oss.smarttestpicker.remote.testExecutionId"));
		request.header = "test-conflict";
		try (var redispatch = RemoteTestContext.enter(request, "X-STP-Test-Execution-Id")) {
			assertEquals("test-original", RemoteTestContext.currentId());
		}
		assertEquals("test-original", request.attributes.get("com.sap.oss.smarttestpicker.remote.testExecutionId"));
		assertNull(RemoteTestContext.currentId());
	}

	@Test void wrappedAsyncTaskRestoresWorkerContextEvenWhenItThrows() throws Exception {
		Runnable wrapped;
		try (var request = RemoteTestContext.enter((HeaderRequest) ignored -> "test-captured", "X-STP-Test-Execution-Id")) {
			wrapped = RemoteTestContext.wrap(() -> {
				assertEquals("test-captured", RemoteTestContext.currentId());
				throw new IllegalStateException("expected async failure");
			});
		}
		java.util.concurrent.atomic.AtomicReference<Throwable> taskFailure = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure = new java.util.concurrent.atomic.AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				try (var prior = RemoteTestContext.enter((HeaderRequest) ignored -> "worker-prior", "X-STP-Test-Execution-Id")) {
					try { wrapped.run(); } catch (Throwable thrown) { taskFailure.set(thrown); }
					assertEquals("worker-prior", RemoteTestContext.currentId());
				}
				assertNull(RemoteTestContext.currentId());
			} catch (Throwable thrown) { workerFailure.set(thrown); }
		});
		worker.start();
		worker.join(5000);
		assertFalse(worker.isAlive(), "async wrapper worker did not finish");
		assertInstanceOf(IllegalStateException.class, taskFailure.get());
		assertNull(workerFailure.get());
		assertNull(RemoteTestContext.currentId());
	}

	@Test void asyncListenerScopeUsesRequestAttributeAndRestoresCallbackThreadContext() {
		MutableRequest request = new MutableRequest("header-must-not-be-used");
		request.attributes.put("com.sap.oss.smarttestpicker.remote.testExecutionId", "accepted-test-id");
		FakeAsyncEvent event = new FakeAsyncEvent(new FakeAsyncContext(request));
		try (var worker = RemoteTestContext.enter((HeaderRequest) ignored -> "worker-prior", "X-STP-Test-Execution-Id")) {
			try (var callback = RemoteTestContext.enterAsyncListener(event)) {
				assertEquals("accepted-test-id", RemoteTestContext.currentId());
			}
			assertEquals("worker-prior", RemoteTestContext.currentId());
		}
		assertNull(RemoteTestContext.currentId());
	}

	@Test void listenerAssociationsAreIsolatedAndCanBeClearedOnReuse() {
		Object listenerA = new Object();
		Object listenerB = new Object();
		try (var scope = RemoteTestContext.enter((HeaderRequest) ignored -> "test-A", "X-STP-Test-Execution-Id")) {
			RemoteTestContext.associateListener(listenerA, RemoteTestContext.capture());
		}
		try (var scope = RemoteTestContext.enter((HeaderRequest) ignored -> "test-B", "X-STP-Test-Execution-Id")) {
			RemoteTestContext.associateListener(listenerB, RemoteTestContext.capture());
		}
		java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure = new java.util.concurrent.atomic.AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				try (var callback = RemoteTestContext.enterListenerCallback(listenerA)) {
					assertEquals("test-A", RemoteTestContext.currentId());
				}
				assertNull(RemoteTestContext.currentId(), "worker must be restored after callback");
				try (var callback = RemoteTestContext.enterListenerCallback(listenerB)) {
					assertEquals("test-B", RemoteTestContext.currentId());
				}
				assertNull(RemoteTestContext.currentId(), "worker must be restored between callbacks");
			} catch (Throwable failure) { workerFailure.set(failure); }
		});
		worker.start();
		try { worker.join(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail(interrupted); }
		assertFalse(worker.isAlive(), "listener callback worker did not finish");
		assertNull(workerFailure.get());
		try (var scope = RemoteTestContext.enter((HeaderRequest) ignored -> "test-B-reused", "X-STP-Test-Execution-Id")) {
			RemoteTestContext.associateListener(listenerA, RemoteTestContext.capture());
		}
		try (var callback = RemoteTestContext.enterListenerCallback(listenerA)) {
			assertEquals("test-B-reused", RemoteTestContext.currentId(), "listener re-registration replaces its old identity");
		}
		RemoteTestContext.associateListener(listenerA, null);
		try (var callback = RemoteTestContext.enterListenerCallback(listenerA)) {
			assertNull(RemoteTestContext.currentId(), "reusing an uncorrelated listener must clear its prior ID");
		}
		assertNull(RemoteTestContext.currentId());
	}

	private static void assertValidTransformed(RemoteHttpBoundaryTransformer transformer, Class<?> fixture) throws Exception {
		String resource = "/" + fixture.getName().replace('.', '/') + ".class";
		byte[] bytes;
		try (var in = fixture.getResourceAsStream(resource)) { bytes = in.readAllBytes(); }
		byte[] transformed = transformer.transform(fixture.getClassLoader(), fixture.getName().replace('.', '/'),
				null, fixture.getProtectionDomain(), bytes);
		assertNotNull(transformed, fixture.getName());
		assertDoesNotThrow(() -> new DefiningLoader(fixture.getClassLoader(), fixture.getName(), transformed).loadClass(fixture.getName()));
		StringWriter diagnostics = new StringWriter();
		CheckClassAdapter.verify(new ClassReader(transformed), fixture.getClassLoader(), false,
				new PrintWriter(diagnostics));
		assertTrue(diagnostics.toString().isBlank(), diagnostics.toString());
	}
	private static void assertAsyncTaskWrapped(RemoteHttpBoundaryTransformer transformer, Class<?> fixture) throws Exception {
		assertValidTransformed(transformer, fixture);
		String resource = "/" + fixture.getName().replace('.', '/') + ".class";
		byte[] bytes;
		try (var in = fixture.getResourceAsStream(resource)) { bytes = in.readAllBytes(); }
		byte[] transformed = transformer.transform(fixture.getClassLoader(), fixture.getName().replace('.', '/'),
				null, fixture.getProtectionDomain(), bytes);
		assertNotNull(transformed, fixture.getName());
		java.util.concurrent.atomic.AtomicBoolean wraps = new java.util.concurrent.atomic.AtomicBoolean();
		new ClassReader(transformed).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
			@Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
					String signature, String[] exceptions) {
				if (!name.equals("start") || !desc.equals("(Ljava/lang/Runnable;)V")) return null;
				return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
					@Override public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean isInterface) {
						if (owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext") && called.equals("wrap")) wraps.set(true);
					}
				};
			}
		}, 0);
		assertTrue(wraps.get(), fixture.getName() + " AsyncContext.start must call RemoteTestContext.wrap");
	}
	private static void assertAsyncListenerCallbacksWrapped(RemoteHttpBoundaryTransformer transformer, Class<?> fixture) throws Exception {
		assertValidTransformed(transformer, fixture);
		String resource = "/" + fixture.getName().replace('.', '/') + ".class";
		byte[] bytes;
		try (var in = fixture.getResourceAsStream(resource)) { bytes = in.readAllBytes(); }
		byte[] transformed = transformer.transform(fixture.getClassLoader(), fixture.getName().replace('.', '/'),
				null, fixture.getProtectionDomain(), bytes);
		java.util.Set<String> wrappedCallbacks = new java.util.HashSet<>();
		new ClassReader(transformed).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
			@Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
					String signature, String[] exceptions) {
			if (!java.util.Set.of("onStartAsync", "onComplete", "onTimeout", "onError").contains(name)) return null;
			return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
				@Override public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean isInterface) {
					if (owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext") && called.equals("enterAsyncListener"))
						wrappedCallbacks.add(name);
				}
			};
			}
		}, 0);
		assertEquals(java.util.Set.of("onStartAsync", "onComplete", "onTimeout", "onError"), wrappedCallbacks,
				fixture.getName() + " must wrap all AsyncListener callbacks");
	}
	private static void assertIoBoundariesWrapped(RemoteHttpBoundaryTransformer transformer, Class<?> readListener,
			Class<?> writeListener, Class<?> inputStream, Class<?> outputStream) throws Exception {
		for (Class<?> fixture : new Class<?>[] {readListener, writeListener, inputStream, outputStream}) assertValidTransformed(transformer, fixture);
		assertContainsCall(transformer, inputStream, "setReadListener", "associateListener");
		assertContainsCall(transformer, outputStream, "setWriteListener", "associateListener");
		for (String callback : new String[] {"onDataAvailable", "onAllDataRead", "onError"})
			assertContainsCall(transformer, readListener, callback, "enterListenerCallback");
		for (String callback : new String[] {"onWritePossible", "onError"})
			assertContainsCall(transformer, writeListener, callback, "enterListenerCallback");
	}
	private static void assertContainsCall(RemoteHttpBoundaryTransformer transformer, Class<?> fixture,
			String methodName, String called) throws Exception {
		String resource = "/" + fixture.getName().replace('.', '/') + ".class";
		byte[] bytes;
		try (var in = fixture.getResourceAsStream(resource)) { bytes = in.readAllBytes(); }
		byte[] transformed = transformer.transform(fixture.getClassLoader(), fixture.getName().replace('.', '/'),
				null, fixture.getProtectionDomain(), bytes);
		assertNotNull(transformed, fixture.getName());
		java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
		new ClassReader(transformed).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
			@Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
					String signature, String[] exceptions) {
				if (!name.equals(methodName)) return null;
				return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
					@Override public void visitMethodInsn(int opcode, String owner, String calledName, String descriptor, boolean isInterface) {
						if (owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext") && calledName.equals(called)) found.set(true);
					}
				};
			}
		}, 0);
		assertTrue(found.get(), fixture.getName() + "#" + methodName + " must call " + called);
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
	@FunctionalInterface private interface HeaderRequest { String getHeader(String header); }
	private static final class MutableRequest {
		private String header;
		private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();
		private MutableRequest(String header) { this.header = header; }
		public String getHeader(String ignored) { return header; }
		public Object getAttribute(String name) { return attributes.get(name); }
		public void setAttribute(String name, Object value) { attributes.put(name, value); }
	}
	private static final class FakeAsyncContext {
		private final Object request;
		private FakeAsyncContext(Object request) { this.request = request; }
		public Object getRequest() { return request; }
	}
	private static final class FakeAsyncEvent {
		private final FakeAsyncContext context;
		private FakeAsyncEvent(FakeAsyncContext context) { this.context = context; }
		public Object getSuppliedRequest() { return null; }
		public FakeAsyncContext getAsyncContext() { return context; }
	}
}
