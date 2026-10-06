// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Instruments only Spring AOP's verified CompletableFuture-returning @Async submission boundary. */
final class RemoteSpringAsyncTransformer implements ClassFileTransformer {
	static final String TARGET_CLASS = "org/springframework/aop/interceptor/AsyncExecutionAspectSupport";
	static final String TARGET_METHOD = "doSubmit";
	static final String TARGET_DESCRIPTOR = "(Ljava/util/concurrent/Callable;Lorg/springframework/core/task/AsyncTaskExecutor;Ljava/lang/Class;)Ljava/lang/Object;";
	private static final String EXECUTOR = "org/springframework/core/task/AsyncTaskExecutor";
	private static final String SUBMIT_DESCRIPTOR = "(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/CompletableFuture;";
	private static final String CONTEXT = "com/sap/oss/smarttestpicker/remote/RemoteTestContext";
	private static final String CALLABLE = "Ljava/util/concurrent/Callable;";
	private final Consumer<String> diagnosticSink;
	private final AtomicBoolean diagnosticReported = new AtomicBoolean();

	RemoteSpringAsyncTransformer() { this(message -> System.err.println("[stp-remote-agent] " + message)); }
	RemoteSpringAsyncTransformer(Consumer<String> diagnosticSink) { this.diagnosticSink = diagnosticSink; }

	@Override public byte[] transform(ClassLoader loader, String className, Class<?> redefining,
			ProtectionDomain domain, byte[] bytes) {
		if (!TARGET_CLASS.equals(className) || bytes == null) return null;
		try {
			ClassReader reader = new ClassReader(bytes);
			ClassNode node = new ClassNode(Opcodes.ASM9);
			reader.accept(node, 0);
			if (!TARGET_CLASS.equals(node.name)) {
				diagnostic("spring-async-boundary-unverified:class-name-mismatch");
				return null;
			}
			MethodNode target = node.methods.stream()
					.filter(method -> TARGET_METHOD.equals(method.name) && TARGET_DESCRIPTOR.equals(method.desc))
					.findFirst().orElse(null);
			if (target == null) {
				diagnostic("spring-async-boundary-unverified:expected-method-missing:" + TARGET_METHOD + TARGET_DESCRIPTOR);
				return null;
			}
			int matches = 0;
			for (AbstractInsnNode instruction = target.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
				if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEINTERFACE
						|| !EXECUTOR.equals(call.owner) || !"submitCompletable".equals(call.name)
						|| !SUBMIT_DESCRIPTOR.equals(call.desc)) continue;
				InsnList wrapping = new InsnList();
				wrapping.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "wrap", "(" + CALLABLE + ")" + CALLABLE, false));
				target.instructions.insertBefore(call, wrapping);
				matches++;
			}
			if (matches != 1) {
				diagnostic("spring-async-boundary-unverified:expected-one-submitCompletable-call:found=" + matches);
				return null;
			}
			ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		} catch (Throwable failure) {
			diagnostic("spring-async-instrumentation-error:" + failure.getClass().getName());
			return null;
		}
	}

	private void diagnostic(String message) {
		if (diagnosticReported.compareAndSet(false, true)) diagnosticSink.accept(message);
	}
}
