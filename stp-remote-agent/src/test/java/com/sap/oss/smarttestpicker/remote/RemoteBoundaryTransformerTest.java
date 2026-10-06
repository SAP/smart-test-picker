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

	private static void assertValidTransformed(RemoteHttpBoundaryTransformer transformer, Class<?> fixture) throws Exception {
		String resource = "/" + fixture.getName().replace('.', '/') + ".class";
		byte[] bytes;
		try (var in = fixture.getResourceAsStream(resource)) { bytes = in.readAllBytes(); }
		byte[] transformed = transformer.transform(fixture.getClassLoader(), fixture.getName().replace('.', '/'),
				null, fixture.getProtectionDomain(), bytes);
		assertNotNull(transformed, fixture.getName());
		StringWriter diagnostics = new StringWriter();
		CheckClassAdapter.verify(new ClassReader(transformed), fixture.getClassLoader(), false,
				new PrintWriter(diagnostics));
		assertTrue(diagnostics.toString().isBlank(), diagnostics.toString());
	}
	@FunctionalInterface private interface HeaderRequest { String getHeader(String header); }
}
