// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import example.remote.ApplicationInterfaceFixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RemoteMethodEntryTransformerTest {
	@Test void instrumentsAndLoadsDefaultAndStaticInterfaceMethods() throws Exception {
		Path output = Path.of("build", "interface-observations.json").toAbsolutePath();
		RemoteAgentConfiguration configuration = RemoteAgentConfiguration.parse(
				"output=" + output + ";includes=example.remote.");
		RemoteMethodEntryTransformer transformer = new RemoteMethodEntryTransformer(configuration);
		String internalName = ApplicationInterfaceFixture.class.getName().replace('.', '/');
		byte[] original;
		try (var in = ApplicationInterfaceFixture.class.getResourceAsStream("/" + internalName + ".class")) {
			original = in.readAllBytes();
		}
		byte[] transformed = transformer.transform(ApplicationInterfaceFixture.class.getClassLoader(), internalName,
				null, ApplicationInterfaceFixture.class.getProtectionDomain(), original);
		assertNotNull(transformed);

		DefiningLoader loader = new DefiningLoader(transformed);
		Class<?> instrumented = loader.define();
		var marker = instrumented.getDeclaredField("$stp$remote$instrumented$v1");
		assertTrue(Modifier.isPublic(marker.getModifiers()));
		assertTrue(Modifier.isStatic(marker.getModifiers()));
		assertTrue(Modifier.isFinal(marker.getModifiers()));
		assertTrue(marker.isSynthetic());

		RemoteRecorder.install(output);
		try (var scope = RemoteTestContext.enter(TestRequests.request("interface-test-id"))) {
			Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] { instrumented },
					(proxyInstance, method, args) -> method.isDefault()
							? InvocationHandler.invokeDefault(proxyInstance, method, args) : null);
			assertEquals("ok", instrumented.getMethod("defaultMethod").invoke(proxy));
			assertEquals("static-ok", instrumented.getMethod("staticMethod").invoke(null));
		}
		RemoteRecorder.writeOutput();
		String observations = java.nio.file.Files.readString(output);
		assertTrue(observations.contains("\"testId\":\"interface-test-id\""), observations);
		assertTrue(observations.contains("ApplicationInterfaceFixture#defaultMethod()Ljava/lang/String;"), observations);
		assertTrue(observations.contains("ApplicationInterfaceFixture#staticMethod()Ljava/lang/String;"), observations);
	}

	private static final class DefiningLoader extends ClassLoader {
		private final byte[] transformed;
		private DefiningLoader(byte[] transformed) {
			super(ApplicationInterfaceFixture.class.getClassLoader());
			this.transformed = transformed;
		}
		private Class<?> define() {
			return defineClass(ApplicationInterfaceFixture.class.getName(), transformed, 0, transformed.length);
		}
	}
	public static final class HeaderRequest {
		private final String id;
		public HeaderRequest(String id) { this.id = id; }
		public String getHeader(String header) { return id; }
	}
}
