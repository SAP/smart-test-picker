// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.function.Consumer;

/** POC instrumentation of user-code calls through Executor and ExecutorService. */
final class ExecutorCallSiteTransformer implements ClassFileTransformer {
	private static final String HOOK = "com/sap/oss/smarttestpicker/runtime/RuntimeHooks";
	private static final Set<String> OWNERS = Set.of("java/util/concurrent/Executor",
			"java/util/concurrent/ExecutorService");
	private static final String RUNNABLE = "Ljava/lang/Runnable;";
	private static final String CALLABLE = "Ljava/util/concurrent/Callable;";
	private final Consumer<String> errorSink;

	ExecutorCallSiteTransformer(Consumer<String> errorSink) {
		this.errorSink = errorSink;
	}

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (loader == null || className == null || classfileBuffer == null || excluded(className)) return null;
		try {
			ClassReader reader = new ClassReader(classfileBuffer);
			ClassNode node = new ClassNode(Opcodes.ASM9);
			reader.accept(node, 0);
			boolean changed = false;
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
						instruction = instruction.getNext()) {
					if (!(instruction instanceof MethodInsnNode call) || !OWNERS.contains(call.owner)) continue;
					InsnList wrapping = wrapping(call);
					if (wrapping != null) {
						method.instructions.insertBefore(call, wrapping);
						changed = true;
					}
				}
			}
			if (!changed) return null;
			ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		} catch (Throwable failure) {
			errorSink.accept("executor-propagation-error:" + className.replace('/', '.') + ":"
					+ failure.getClass().getName());
			return null;
		}
	}

	private static InsnList wrapping(MethodInsnNode call) {
		InsnList result = new InsnList();
		if ((call.name.equals("execute") || call.name.equals("submit")) && call.desc.startsWith("(" + RUNNABLE)) {
			if (call.desc.startsWith("(" + RUNNABLE + "Ljava/lang/Object;")) result.add(new InsnNode(Opcodes.SWAP));
			result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "wrap",
					"(" + RUNNABLE + ")" + RUNNABLE, false));
			if (call.desc.startsWith("(" + RUNNABLE + "Ljava/lang/Object;")) result.add(new InsnNode(Opcodes.SWAP));
			return result;
		}
		if (call.name.equals("submit") && call.desc.startsWith("(" + CALLABLE)) {
			result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "wrap",
					"(" + CALLABLE + ")" + CALLABLE, false));
			return result;
		}
		return null;
	}

	private static boolean excluded(String name) {
		return name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/")
				|| name.startsWith("com/sap/oss/smarttestpicker/") || name.startsWith("org/objectweb/asm/")
				|| name.startsWith("com/sap/oss/smarttestpicker/internal/asm/");
	}
}
