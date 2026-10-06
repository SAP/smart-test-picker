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
import org.springframework.aop.interceptor.AsyncExecutionAspectSupport;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSpringAsyncTransformerTest {
	private static final String METHOD_DESCRIPTOR = RemoteSpringAsyncTransformer.TARGET_DESCRIPTOR;

	@Test void wrapsExactlyTheVerifiedSpringCompletableFutureSubmission() throws Exception {
		List<String> diagnostics = new ArrayList<>();
		RemoteSpringAsyncTransformer transformer = new RemoteSpringAsyncTransformer(diagnostics::add);
		byte[] original;
		try (InputStream stream = AsyncExecutionAspectSupport.class.getResourceAsStream("AsyncExecutionAspectSupport.class")) {
			assertNotNull(stream);
			original = stream.readAllBytes();
		}
		byte[] transformed = transformer.transform(AsyncExecutionAspectSupport.class.getClassLoader(),
				RemoteSpringAsyncTransformer.TARGET_CLASS, null, null, original);
		assertNotNull(transformed, "Spring 6.1.14 fixture must contain the verified boundary");
		assertTrue(diagnostics.isEmpty(), diagnostics.toString());
		ClassNode node = new ClassNode(Opcodes.ASM9);
		new ClassReader(transformed).accept(node, 0);
		MethodNode method = node.methods.stream().filter(candidate -> candidate.name.equals("doSubmit")
				&& candidate.desc.equals(METHOD_DESCRIPTOR)).findFirst().orElseThrow();
		int wraps = 0;
		int submissions = 0;
		for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			if (call.owner.equals("com/sap/oss/smarttestpicker/remote/RemoteTestContext") && call.name.equals("wrap")
					&& call.desc.equals("(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Callable;")) wraps++;
			if (call.owner.equals("org/springframework/core/task/AsyncTaskExecutor")
					&& call.name.equals("submitCompletable")
					&& call.desc.equals("(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/CompletableFuture;")) submissions++;
		}
		assertEquals(1, wraps);
		assertEquals(1, submissions);
	}

	@Test void leavesClassUnchangedAndReportsWhenBoundaryShapeIsMissing() {
		List<String> diagnostics = new ArrayList<>();
		RemoteSpringAsyncTransformer transformer = new RemoteSpringAsyncTransformer(diagnostics::add);
		assertNull(transformer.transform(getClass().getClassLoader(), RemoteSpringAsyncTransformer.TARGET_CLASS,
				null, null, new byte[]{0, 1}));
		assertEquals(1, diagnostics.size());
		assertTrue(diagnostics.get(0).startsWith("spring-async-instrumentation-error:"), diagnostics.toString());
	}

	@Test void leavesMethodUnchangedAndReportsWhenSubmitCompletableShapeIsAbsent() {
		List<String> diagnostics = new ArrayList<>();
		RemoteSpringAsyncTransformer transformer = new RemoteSpringAsyncTransformer(diagnostics::add);
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RemoteSpringAsyncTransformer.TARGET_CLASS, null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "doSubmit", METHOD_DESCRIPTOR, null, null);
		method.visitCode();
		method.visitInsn(Opcodes.ACONST_NULL);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(1, 4);
		method.visitEnd();
		writer.visitEnd();
		assertNull(transformer.transform(getClass().getClassLoader(), RemoteSpringAsyncTransformer.TARGET_CLASS,
				null, null, writer.toByteArray()));
		assertEquals(1, diagnostics.size());
		assertTrue(diagnostics.get(0).endsWith("expected-one-submitCompletable-call:found=0"), diagnostics.toString());
	}

	@Test void genericApplicationCallSiteTransformerDoesNotOwnSpringAsyncBoundary() throws Exception {
		byte[] original;
		try (InputStream stream = AsyncExecutionAspectSupport.class.getResourceAsStream("AsyncExecutionAspectSupport.class")) {
			assertNotNull(stream);
			original = stream.readAllBytes();
		}
		RemoteAgentConfiguration broadIncludes = RemoteAgentConfiguration.parse("output=/tmp/remote.json;includes=org.springframework.");
		RemoteExecutorCallSiteTransformer generic = new RemoteExecutorCallSiteTransformer(broadIncludes, message -> fail(message));
		assertNull(generic.transform(AsyncExecutionAspectSupport.class.getClassLoader(), RemoteSpringAsyncTransformer.TARGET_CLASS,
				null, null, original), "targeted transformer exclusively owns this Spring boundary");
	}
}
