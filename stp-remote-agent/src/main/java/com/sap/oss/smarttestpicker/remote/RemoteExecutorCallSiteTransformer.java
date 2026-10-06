// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Adds Remote STP context wrappers at configured application Executor call sites. */
final class RemoteExecutorCallSiteTransformer implements ClassFileTransformer {
	private static final String CONTEXT = "com/sap/oss/smarttestpicker/remote/RemoteTestContext";
	private static final String EXECUTOR = "java/util/concurrent/Executor";
	private static final String EXECUTOR_SERVICE = "java/util/concurrent/ExecutorService";
	private static final String RUNNABLE = "Ljava/lang/Runnable;";
	private static final String CALLABLE = "Ljava/util/concurrent/Callable;";
	private static final String FUTURE = "Ljava/util/concurrent/Future;";
	private static final Set<String> ROOTS = Set.of(EXECUTOR, EXECUTOR_SERVICE);
	private final RemoteAgentConfiguration configuration;
	private final Consumer<String> diagnosticSink;
	private final Set<String> reported = ConcurrentHashMap.newKeySet();

	RemoteExecutorCallSiteTransformer(RemoteAgentConfiguration configuration) {
		this(configuration, message -> System.err.println("[stp-remote-agent] " + message));
	}

	RemoteExecutorCallSiteTransformer(RemoteAgentConfiguration configuration, Consumer<String> diagnosticSink) {
		this.configuration = configuration;
		this.diagnosticSink = diagnosticSink;
	}

	@Override public byte[] transform(ClassLoader loader, String className, Class<?> redefining,
			ProtectionDomain domain, byte[] bytes) {
		if (loader == null || className == null || bytes == null || excluded(className)
				|| !configuration.instruments(className.replace('/', '.'))) return null;
		try {
			ClassReader reader = new ClassReader(bytes);
			ClassNode node = new ClassNode(Opcodes.ASM9);
			reader.accept(node, 0);
			Hierarchy hierarchy = new Hierarchy(loader, node);
			boolean changed = false;
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
						instruction = instruction.getNext()) {
					if (!(instruction instanceof MethodInsnNode call) || !supportedShape(call)) continue;
					Resolution resolution = hierarchy.executor(call.owner, new HashSet<>());
					if (resolution == Resolution.UNKNOWN) {
						report("executor-attribution-incomplete:" + className.replace('/', '.') + ":"
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
			report("executor-propagation-error:" + className.replace('/', '.') + ":" + failure.getClass().getName());
			return null;
		}
	}

	private void report(String message) {
		if (reported.add(message)) diagnosticSink.accept(message);
	}

	private static boolean excluded(String name) {
		return name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/")
				|| name.startsWith("com/sap/oss/smarttestpicker/remote/") || name.startsWith("org/objectweb/asm/");
	}

	private static boolean supportedShape(MethodInsnNode call) {
		return call.name.equals("execute") && call.desc.equals("(" + RUNNABLE + ")V")
				|| call.name.equals("submit") && (call.desc.equals("(" + RUNNABLE + ")" + FUTURE)
				|| call.desc.equals("(" + RUNNABLE + "Ljava/lang/Object;)" + FUTURE)
				|| call.desc.equals("(" + CALLABLE + ")" + FUTURE));
	}

	private static InsnList wrapping(MethodInsnNode call) {
		InsnList result = new InsnList();
		if (call.desc.startsWith("(" + RUNNABLE)) {
			boolean resultArgument = call.desc.equals("(" + RUNNABLE + "Ljava/lang/Object;)" + FUTURE);
			if (resultArgument) result.add(new InsnNode(Opcodes.SWAP));
			result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "wrap",
					"(" + RUNNABLE + ")" + RUNNABLE, false));
			if (resultArgument) result.add(new InsnNode(Opcodes.SWAP));
			return result;
		}
		if (call.desc.equals("(" + CALLABLE + ")" + FUTURE)) {
			result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "wrap",
					"(" + CALLABLE + ")" + CALLABLE, false));
			return result;
		}
		return null;
	}

	private enum Resolution { YES, NO, UNKNOWN }

	/** Resolves bytecode hierarchy without loading or initializing application classes. */
	private static final class Hierarchy {
		private final ClassLoader loader;
		private final ClassNode current;
		private Hierarchy(ClassLoader loader, ClassNode current) { this.loader = loader; this.current = current; }

		private Resolution executor(String owner, Set<String> visited) {
			if (ROOTS.contains(owner)) return Resolution.YES;
			if (!visited.add(owner) || owner.equals("java/lang/Object")) return Resolution.NO;
			try {
				if (owner.equals(current.name)) return parents(current.superName, current.interfaces, visited);
				try (InputStream bytes = loader.getResourceAsStream(owner + ".class")) {
					if (bytes == null) return Resolution.UNKNOWN;
					ClassReader reader = new ClassReader(bytes);
					return parents(reader.getSuperName(), Arrays.asList(reader.getInterfaces()), visited);
				}
			} catch (Throwable ignored) { return Resolution.UNKNOWN; }
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
