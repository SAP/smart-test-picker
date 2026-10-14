// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import example.remote.ApplicationInterfaceFixture;
import example.remote.TrivialMembersFixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RemoteMethodEntryTransformerTest {
	@Test void instrumentsAndLoadsDefaultAndStaticInterfaceMethods() throws Exception {
		Path output = java.nio.file.Files.createTempDirectory("remote-interface-output").resolve("interface-observations.json");
		RemoteAgentConfiguration configuration = RemoteAgentConfiguration.parse(
				"output=" + output + ";includes=example.remote.;serviceId=fixture-service;revision=test-revision");
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

	@Test void skipTrivialLeavesAccessorsAndTrivialCtorUninstrumented() throws Exception {
		String observations = runTrivialFixture(true);
		assertTrue(observations.contains("TrivialMembersFixture#compute(I)I"), observations);
		assertFalse(observations.contains("#getValue"), observations);
		assertFalse(observations.contains("#setValue"), observations);
		assertFalse(observations.contains("#isReady"), observations);
		assertFalse(observations.contains("#<init>"), observations);
	}

	@Test void skipTrivialFalseInstrumentsAccessorsAndTrivialCtor() throws Exception {
		String observations = runTrivialFixture(false);
		assertTrue(observations.contains("TrivialMembersFixture#compute(I)I"), observations);
		assertTrue(observations.contains("TrivialMembersFixture#getValue()I"), observations);
		assertTrue(observations.contains("TrivialMembersFixture#setValue(I)V"), observations);
		assertTrue(observations.contains("TrivialMembersFixture#isReady()Z"), observations);
		assertTrue(observations.contains("TrivialMembersFixture#<init>()V"), observations);
	}

	private static String runTrivialFixture(boolean skipTrivial) throws Exception {
		Path output = java.nio.file.Files.createTempDirectory("remote-trivial-output").resolve("trivial-observations.json");
		RemoteAgentConfiguration configuration = RemoteAgentConfiguration.parse(
				"output=" + output + ";includes=example.remote.;serviceId=fixture-service;revision=test-revision;skipTrivial=" + skipTrivial);
		RemoteMethodEntryTransformer transformer = new RemoteMethodEntryTransformer(configuration);
		String internalName = TrivialMembersFixture.class.getName().replace('.', '/');
		byte[] original;
		try (var in = TrivialMembersFixture.class.getResourceAsStream("/" + internalName + ".class")) {
			original = in.readAllBytes();
		}
		byte[] transformed = transformer.transform(TrivialMembersFixture.class.getClassLoader(), internalName,
				null, TrivialMembersFixture.class.getProtectionDomain(), original);
		assertNotNull(transformed);
		TrivialDefiningLoader loader = new TrivialDefiningLoader(transformed);
		Class<?> instrumented = loader.define();

		RemoteRecorder.install(output);
		try (var scope = RemoteTestContext.enter(TestRequests.request("trivial-test-id"))) {
			Object instance = instrumented.getDeclaredConstructor().newInstance();
			instrumented.getMethod("setValue", int.class).invoke(instance, 3);
			instrumented.getMethod("getValue").invoke(instance);
			instrumented.getMethod("isReady").invoke(instance);
			instrumented.getMethod("compute", int.class).invoke(instance, 4);
		}
		RemoteRecorder.writeOutput();
		return java.nio.file.Files.readString(output);
	}

	private static final class TrivialDefiningLoader extends ClassLoader {
		private final byte[] transformed;
		private TrivialDefiningLoader(byte[] transformed) {
			super(TrivialMembersFixture.class.getClassLoader());
			this.transformed = transformed;
		}
		private Class<?> define() {
			return defineClass(TrivialMembersFixture.class.getName(), transformed, 0, transformed.length);
		}
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
