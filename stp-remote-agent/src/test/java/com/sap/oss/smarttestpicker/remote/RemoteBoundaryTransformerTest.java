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
		RemoteHttpBoundaryTransformer transformer = new RemoteHttpBoundaryTransformer();
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
		Object requestA = TestRequests.request("test-A");
		Object noHeader = TestRequests.noIdentity();
		try (var outer = RemoteTestContext.enter(requestA)) {
			assertEquals("test-A", RemoteTestContext.currentId());
			try (var nested = RemoteTestContext.enter(noHeader)) {
				assertNull(RemoteTestContext.currentId());
			}
			assertEquals("test-A", RemoteTestContext.currentId());
		}
		assertNull(RemoteTestContext.currentId());
	}

	@Test void acceptedRequestAttributeWinsOverConflictingRedispatchHeader() {
		TestRequests.Request request = TestRequests.request("test-original");
		try (var initial = RemoteTestContext.enter(request)) {
			assertEquals("test-original", RemoteTestContext.currentId());
		}
		assertEquals(new RemoteRequestIdentity("fixture-suite", "test-original", request.getHeader(RemoteRequestIdentity.REQUEST_ID_HEADER)), request.getAttribute("com.sap.oss.smarttestpicker.remote.requestIdentity"));
		request.setHeader(RemoteRequestIdentity.TEST_ID_HEADER, "test-conflict");
		try (var redispatch = RemoteTestContext.enter(request)) {
			assertEquals("test-original", RemoteTestContext.currentId());
		}
		assertEquals("test-original", ((RemoteRequestIdentity) request.getAttribute("com.sap.oss.smarttestpicker.remote.requestIdentity")).testId());
		assertNull(RemoteTestContext.currentId());
	}

	@Test void partialOrInvalidIdentityHeadersNeverCreatePartialAttribution() {
		TestRequests.Request partial = TestRequests.request("partial-test");
		partial.setHeader(RemoteRequestIdentity.REQUEST_ID_HEADER, null);
		try (var scope = RemoteTestContext.enter(partial)) { assertNull(RemoteTestContext.currentIdentity()); }
		TestRequests.Request invalid = TestRequests.request("invalid-test");
		invalid.setHeader(RemoteRequestIdentity.REQUEST_ID_HEADER, "bad\nvalue");
		try (var scope = RemoteTestContext.enter(invalid)) { assertNull(RemoteTestContext.currentIdentity()); }
		assertNull(RemoteTestContext.currentIdentity());
	}

	@Test void requestIdIsPartOfCapturedAndRestoredAsyncIdentity() throws Exception {
		RemoteRequestIdentity expected;
		Runnable wrapped;
		TestRequests.Request request = TestRequests.request("full-identity");
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

	@Test void asyncListenerScopeUsesRequestAttributeAndRestoresCallbackThreadContext() {
		TestRequests.Request request = TestRequests.request("header-must-not-be-used");
		request.setAttribute("com.sap.oss.smarttestpicker.remote.requestIdentity", new RemoteRequestIdentity("suite", "accepted-test-id", java.util.UUID.randomUUID().toString()));
		FakeAsyncEvent event = new FakeAsyncEvent(new FakeAsyncContext(request));
		try (var worker = RemoteTestContext.enter(TestRequests.request("worker-prior"))) {
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
		try (var scope = RemoteTestContext.enter(TestRequests.request("test-A"))) {
			RemoteTestContext.associateListener(listenerA, RemoteTestContext.capture());
		}
		try (var scope = RemoteTestContext.enter(TestRequests.request("test-B"))) {
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
		try (var scope = RemoteTestContext.enter(TestRequests.request("test-B-reused"))) {
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
