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
	@Test void leavesServletAndSpringBoundariesToOpenTelemetryInBothNamespaces() throws Exception {
		RemoteHttpBoundaryTransformer transformer = new RemoteHttpBoundaryTransformer();
		assertUntransformed(transformer, example.remote.javax.JavaxFilterChainFixture.class);
		assertUntransformed(transformer, example.remote.jakarta.JakartaFilterChainFixture.class);
		assertUntransformed(transformer, example.remote.javax.JavaxServletFixture.class);
		assertUntransformed(transformer, example.remote.jakarta.JakartaServletFixture.class);
		assertUntransformed(transformer, example.remote.javax.JavaxRequestListenerFixture.class);
		assertUntransformed(transformer, example.remote.jakarta.JakartaRequestListenerFixture.class);
		assertUntransformed(transformer, example.remote.javax.JavaxAsyncContextFixture.class);
		assertUntransformed(transformer, example.remote.jakarta.JakartaAsyncContextFixture.class);
		assertAsyncListenerCallbacksWrapped(transformer, example.remote.javax.JavaxAsyncListenerFixture.class);
		assertAsyncListenerCallbacksWrapped(transformer, example.remote.jakarta.JakartaAsyncListenerFixture.class);
		assertIoBoundariesWrapped(transformer, example.remote.javax.JavaxIoFixtures.Reader.class,
				example.remote.javax.JavaxIoFixtures.Writer.class, example.remote.javax.JavaxIoFixtures.Input.class,
				example.remote.javax.JavaxIoFixtures.Output.class);
		assertIoBoundariesWrapped(transformer, example.remote.jakarta.JakartaIoFixtures.Reader.class,
				example.remote.jakarta.JakartaIoFixtures.Writer.class, example.remote.jakarta.JakartaIoFixtures.Input.class,
				example.remote.jakarta.JakartaIoFixtures.Output.class);
	}

	@Test void nestedOtelIdentityScopesRestoreParentAndSuppressMissingIdentity() {
		RemoteRequestIdentity requestA = TestRequests.request("test-A");
		RemoteRequestIdentity requestB = TestRequests.request("test-B");
		try (var outer = RemoteTestContext.enter(requestA)) {
			assertEquals("test-A", RemoteTestContext.currentId());
			try (var nested = RemoteTestContext.enter(requestB)) { assertEquals("test-B", RemoteTestContext.currentId()); }
			assertEquals("test-A", RemoteTestContext.currentId());
		}
		assertNull(RemoteTestContext.currentIdentity());
	}

	@Test void invalidStructuredBaggageNeverCreatesPartialIdentity() {
		assertThrows(IllegalArgumentException.class, () -> new RemoteRequestIdentity("suite", null, "request"));
		assertThrows(IllegalArgumentException.class, () -> new RemoteRequestIdentity("suite", "test", "bad\nvalue"));
		assertNull(RemoteTestContext.currentIdentity());
	}

	@Test void requestIdIsPartOfCapturedAndRestoredAsyncIdentity() throws Exception {
		RemoteRequestIdentity expected;
		Runnable wrapped;
		RemoteRequestIdentity request = TestRequests.request("full-identity");
		try (var scope = RemoteTestContext.enter(request)) {
			expected = RemoteTestContext.currentIdentity();
			wrapped = RemoteTestContext.wrap(() -> assertEquals(expected, RemoteTestContext.currentIdentity()));
		}
		Thread worker = new Thread(wrapped);
		worker.start(); worker.join(5000);
		assertFalse(worker.isAlive());
		assertNull(RemoteTestContext.currentIdentity());
	}

	@Test void wrappedAsyncTaskRestoresWorkerContextEvenWhenItThrows() throws Exception {
		Runnable wrapped;
		try (var request = RemoteTestContext.enter(TestRequests.request("test-captured"))) {
			wrapped = RemoteTestContext.wrap((Runnable) () -> {
				assertEquals("test-captured", RemoteTestContext.currentId());
				throw new IllegalStateException("expected async failure");
			});
		}
		java.util.concurrent.atomic.AtomicReference<Throwable> taskFailure = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure = new java.util.concurrent.atomic.AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				try (var prior = RemoteTestContext.enter(TestRequests.request("worker-prior"))) {
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

	@Test void wrappedCallableCapturesIdentityAndRestoresWorkerContext() throws Exception {
		java.util.concurrent.Callable<String> wrapped;
		try (var request = RemoteTestContext.enter(TestRequests.request("callable-test-id"))) {
			wrapped = RemoteTestContext.wrap((java.util.concurrent.Callable<String>) RemoteTestContext::currentId);
		}
		java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure = new java.util.concurrent.atomic.AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				try (var prior = RemoteTestContext.enter(TestRequests.request("worker-prior"))) {
					try { assertEquals("callable-test-id", wrapped.call()); }
					catch (Exception failure) { throw new AssertionError(failure); }
					assertEquals("worker-prior", RemoteTestContext.currentId());
				}
				assertNull(RemoteTestContext.currentId());
			} catch (Throwable failure) { workerFailure.set(failure); }
		});
		worker.start();
		worker.join(5000);
		assertFalse(worker.isAlive(), "Callable worker did not finish");
		assertNull(workerFailure.get());
	}

	@Test void listenerAssociationsAreIsolatedAndCanBeClearedOnReuse() {
		Object listenerA = new Object();
		Object listenerB = new Object();
		try (var scope = RemoteTestContext.enter(TestRequests.request("test-A"))) {
			RemoteTestContext.associateListener(listenerA);
		}
		try (var scope = RemoteTestContext.enter(TestRequests.request("test-B"))) {
			RemoteTestContext.associateListener(listenerB);
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
		try (var scope = RemoteTestContext.enter(TestRequests.request("test-B-reused"))) {
			RemoteTestContext.associateListener(listenerA);
		}
		try (var callback = RemoteTestContext.enterListenerCallback(listenerA)) {
			assertEquals("test-B-reused", RemoteTestContext.currentId(), "listener re-registration replaces its old identity");
		}
		RemoteTestContext.clearListener(listenerA);
		try (var callback = RemoteTestContext.enterListenerCallback(listenerA)) {
			assertNull(RemoteTestContext.currentId(), "reusing an uncorrelated listener must clear its prior ID");
		}
		assertNull(RemoteTestContext.currentId());
	}

	private static void assertUntransformed(RemoteHttpBoundaryTransformer transformer, Class<?> fixture) throws Exception {
		String resource = "/" + fixture.getName().replace('.', '/') + ".class";
		try (var in = fixture.getResourceAsStream(resource)) {
			assertNull(transformer.transform(fixture.getClassLoader(), fixture.getName().replace('.', '/'),
					null, fixture.getProtectionDomain(), in.readAllBytes()), fixture.getName());
		}
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
					if (owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext") && called.equals("enterListenerCallback"))
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
