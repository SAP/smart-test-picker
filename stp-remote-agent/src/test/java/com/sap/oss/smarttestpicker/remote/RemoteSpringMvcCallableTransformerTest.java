// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.springframework.web.context.request.async.WebAsyncManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSpringMvcCallableTransformerTest {
	@Test void wrapsOnlyTheVerifiedWebAsyncManagerCallableSubmitBoundary() throws Exception {
		RemoteSpringMvcCallableTransformer transformer = new RemoteSpringMvcCallableTransformer(message -> fail(message));
		byte[] original;
		try (InputStream stream = WebAsyncManager.class.getResourceAsStream("WebAsyncManager.class")) {
			assertNotNull(stream);
			original = stream.readAllBytes();
		}
		byte[] transformed = transformer.transform(WebAsyncManager.class.getClassLoader(),
				RemoteSpringMvcCallableTransformer.TARGET_CLASS, null, null, original);
		assertNotNull(transformed, "Spring 6.1.14 fixture must contain the expected call site");
		ClassNode node = new ClassNode(Opcodes.ASM9);
		new ClassReader(transformed).accept(node, 0);
		MethodNode method = node.methods.stream().filter(candidate -> candidate.name.equals("startCallableProcessing")
				&& candidate.desc.equals("(Lorg/springframework/web/context/request/async/WebAsyncTask;[Ljava/lang/Object;)V"))
				.findFirst().orElseThrow();
		int wraps = 0;
		int submitCalls = 0;
		for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			if (call.owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext") && call.name.equals("wrap")
					&& call.desc.equals("(Ljava/lang/Runnable;)Ljava/lang/Runnable;")) wraps++;
			if (call.owner.equals("org/springframework/core/task/AsyncTaskExecutor") && call.name.equals("submit")
					&& call.desc.equals("(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;")) submitCalls++;
		}
		assertEquals(1, wraps);
		assertEquals(1, submitCalls);
	}

	@Test void reportsWhenTheKnownSpringClassDoesNotContainTheExpectedMethodShape() {
		List<String> diagnostics = new ArrayList<>();
		RemoteSpringMvcCallableTransformer transformer = new RemoteSpringMvcCallableTransformer(diagnostics::add);
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RemoteSpringMvcCallableTransformer.TARGET_CLASS,
				null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "otherMethod", "()V", null, null);
		method.visitCode();
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(0, 1);
		method.visitEnd();
		writer.visitEnd();
		assertNull(transformer.transform(getClass().getClassLoader(), RemoteSpringMvcCallableTransformer.TARGET_CLASS,
				null, null, writer.toByteArray()));
		assertEquals(1, diagnostics.size());
		assertTrue(diagnostics.get(0).contains("spring-mvc-callable-boundary-unverified:expected-method-missing"), diagnostics.toString());
	}

	@Test void reportsWhenTheExpectedMethodHasNoMatchingSubmitDescriptor() {
		List<String> diagnostics = new ArrayList<>();
		RemoteSpringMvcCallableTransformer transformer = new RemoteSpringMvcCallableTransformer(diagnostics::add);
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RemoteSpringMvcCallableTransformer.TARGET_CLASS,
				null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "startCallableProcessing",
				"(Lorg/springframework/web/context/request/async/WebAsyncTask;[Ljava/lang/Object;)V", null, null);
		method.visitCode();
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(0, 3);
		method.visitEnd();
		writer.visitEnd();
		assertNull(transformer.transform(getClass().getClassLoader(), RemoteSpringMvcCallableTransformer.TARGET_CLASS,
				null, null, writer.toByteArray()));
		assertEquals(1, diagnostics.size());
		assertTrue(diagnostics.get(0).contains("expected-one-submit-runnable-call:found=0"), diagnostics.toString());
	}

	@Test void ignoresClassesOutsideSpringWebAsyncManager() {
		List<String> diagnostics = new ArrayList<>();
		RemoteSpringMvcCallableTransformer transformer = new RemoteSpringMvcCallableTransformer(diagnostics::add);
		assertNull(transformer.transform(getClass().getClassLoader(), "org/springframework/other/Manager", null, null, new byte[] {0}));
		assertTrue(diagnostics.isEmpty());
	}
}
