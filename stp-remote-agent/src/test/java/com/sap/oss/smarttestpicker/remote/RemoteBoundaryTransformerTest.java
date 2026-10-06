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
}
