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
import java.io.InputStream;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** POC instrumentation of user-code calls through Executor and ExecutorService. */
final class ExecutorCallSiteTransformer implements ClassFileTransformer {
	private static final String HOOK = "com/sap/oss/smarttestpicker/runtime/RuntimeHooks";
	private static final Set<String> EXECUTOR_ROOTS = Set.of("java/util/concurrent/Executor",
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
			Hierarchy hierarchy = new Hierarchy(loader, node);
			boolean changed = false;
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
						instruction = instruction.getNext()) {
					if (!(instruction instanceof MethodInsnNode call)) continue;
					if (unsupportedAsyncShape(call)) {
						errorSink.accept("executor-attribution-unsupported:" + className.replace('/', '.') + ":"
								+ call.owner.replace('/', '.') + "." + call.name + call.desc);
						continue;
					}
					if (!executorShape(call)) continue;
					Resolution resolution = hierarchy.executor(call.owner);
					if (resolution == Resolution.UNKNOWN) {
						errorSink.accept("executor-attribution-incomplete:" + className.replace('/', '.') + ":"
								+ call.owner.replace('/', '.') + "." + call.name + call.desc);
						continue;
					}
					if (resolution != Resolution.YES) continue;
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

	private static boolean unsupportedAsyncShape(MethodInsnNode call) {
		return call.owner.equals("java/util/concurrent/CompletableFuture")
				&& (call.name.equals("runAsync") || call.name.equals("supplyAsync"))
				&& call.desc.contains("Ljava/util/concurrent/Executor;");
	}

	private static boolean executorShape(MethodInsnNode call) {
		return ((call.name.equals("execute") || call.name.equals("submit"))
				&& call.desc.startsWith("(" + RUNNABLE))
				|| (call.name.equals("submit") && call.desc.startsWith("(" + CALLABLE));
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

	private enum Resolution { YES, NO, UNKNOWN }

	/** Resolves class-file hierarchy metadata without loading or initializing application classes. */
	private static final class Hierarchy {
		private final ClassLoader loader;
		private final ClassNode current;

		private Hierarchy(ClassLoader loader, ClassNode current) {
			this.loader = loader;
			this.current = current;
		}

		private Resolution executor(String owner) {
			return executor(owner, new HashSet<>());
		}

		private Resolution executor(String owner, Set<String> visited) {
			if (EXECUTOR_ROOTS.contains(owner)) return Resolution.YES;
			if (!visited.add(owner) || owner.equals("java/lang/Object")) return Resolution.NO;
			ClassReader reader;
			try {
				if (owner.equals(current.name)) return parents(current.superName, current.interfaces, visited);
				try (InputStream bytes = loader.getResourceAsStream(owner + ".class")) {
					if (bytes == null) return Resolution.UNKNOWN;
					reader = new ClassReader(bytes);
				}
				return parents(reader.getSuperName(), java.util.Arrays.asList(reader.getInterfaces()), visited);
			} catch (Throwable ignored) {
				return Resolution.UNKNOWN;
			}
		}

		private Resolution parents(String superclass, java.util.List<String> interfaces, Set<String> visited) {
			boolean unknown = false;
			for (String parent : interfaces) {
				Resolution result = executor(parent, visited);
				if (result == Resolution.YES) return result;
				unknown |= result == Resolution.UNKNOWN;
			}
			if (superclass != null) {
				Resolution result = executor(superclass, visited);
				if (result == Resolution.YES) return result;
				unknown |= result == Resolution.UNKNOWN;
			}
			return unknown ? Resolution.UNKNOWN : Resolution.NO;
		}
	}
}
