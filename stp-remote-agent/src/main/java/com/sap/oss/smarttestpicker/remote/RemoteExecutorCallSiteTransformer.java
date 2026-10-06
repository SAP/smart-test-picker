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
	private static final String SCHEDULED_EXECUTOR_SERVICE = "java/util/concurrent/ScheduledExecutorService";
	private static final String SCHEDULED_EXECUTOR_DESC = "Ljava/util/concurrent/ScheduledExecutorService;";
	private static final String RUNNABLE = "Ljava/lang/Runnable;";
	private static final String CALLABLE = "Ljava/util/concurrent/Callable;";
	private static final String FUTURE = "Ljava/util/concurrent/Future;";
	private static final String COMPLETABLE_FUTURE = "java/util/concurrent/CompletableFuture";
	private static final String COMPLETABLE_FUTURE_DESC = "Ljava/util/concurrent/CompletableFuture;";
	private static final String SUPPLIER = "Ljava/util/function/Supplier;";
	private static final String FUNCTION = "Ljava/util/function/Function;";
	private static final String SCHEDULED_FUTURE = "Ljava/util/concurrent/ScheduledFuture;";
	private static final String TIME_UNIT = "Ljava/util/concurrent/TimeUnit;";
	private static final Set<String> ROOTS = Set.of(EXECUTOR, EXECUTOR_SERVICE, SCHEDULED_EXECUTOR_SERVICE);
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
					if (!(instruction instanceof MethodInsnNode call)) continue;
					InsnList futureWrapping = completableFutureWrapping(call);
					if (futureWrapping != null) {
						method.instructions.insertBefore(call, futureWrapping);
						changed = true;
						continue;
					}
					MethodInsnNode scheduledBridge = scheduledBridge(call, hierarchy, className);
					if (scheduledBridge != null) {
						call.setOpcode(Opcodes.INVOKESTATIC);
						call.owner = scheduledBridge.owner;
						call.name = scheduledBridge.name;
						call.desc = scheduledBridge.desc;
						call.itf = false;
						changed = true;
						continue;
					}
					if (!supportedShape(call)) continue;
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

	private static InsnList completableFutureWrapping(MethodInsnNode call) {
		if (!call.owner.equals(COMPLETABLE_FUTURE)) return null;
		String argument;
		String hook;
		if ((call.name.equals("runAsync") || call.name.equals("thenRunAsync"))
				&& (call.desc.equals("(" + RUNNABLE + ")" + COMPLETABLE_FUTURE_DESC)
				|| call.desc.equals("(" + RUNNABLE + "Ljava/util/concurrent/Executor;)" + COMPLETABLE_FUTURE_DESC))) {
			argument = RUNNABLE;
			hook = "wrap";
		} else if (call.name.equals("supplyAsync")
				&& (call.desc.equals("(" + SUPPLIER + ")" + COMPLETABLE_FUTURE_DESC)
				|| call.desc.equals("(" + SUPPLIER + "Ljava/util/concurrent/Executor;)" + COMPLETABLE_FUTURE_DESC))) {
			argument = SUPPLIER;
			hook = "wrapSupplier";
		} else if ((call.name.equals("thenApplyAsync"))
				&& (call.desc.equals("(" + FUNCTION + ")" + COMPLETABLE_FUTURE_DESC)
				|| call.desc.equals("(" + FUNCTION + "Ljava/util/concurrent/Executor;)" + COMPLETABLE_FUTURE_DESC))) {
			argument = FUNCTION;
			hook = "wrapFunction";
		} else return null;
		boolean explicitExecutor = call.desc.contains("Ljava/util/concurrent/Executor;");
		InsnList result = new InsnList();
		if (explicitExecutor) result.add(new InsnNode(Opcodes.SWAP));
		result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, hook,
				"(" + argument + ")" + argument, false));
		if (explicitExecutor) result.add(new InsnNode(Opcodes.SWAP));
		return result;
	}

	private MethodInsnNode scheduledBridge(MethodInsnNode call, Hierarchy hierarchy, String caller) {
		String descriptor;
		if (call.name.equals("schedule") && call.desc.equals("(" + RUNNABLE + "J" + TIME_UNIT + ")" + SCHEDULED_FUTURE)) {
			descriptor = "(" + SCHEDULED_EXECUTOR_DESC + RUNNABLE + "J" + TIME_UNIT + ")" + SCHEDULED_FUTURE;
		} else if (call.name.equals("schedule") && call.desc.equals("(" + CALLABLE + "J" + TIME_UNIT + ")" + SCHEDULED_FUTURE)) {
			descriptor = "(" + SCHEDULED_EXECUTOR_DESC + CALLABLE + "J" + TIME_UNIT + ")" + SCHEDULED_FUTURE;
		} else if ((call.name.equals("scheduleAtFixedRate") || call.name.equals("scheduleWithFixedDelay"))
				&& call.desc.equals("(" + RUNNABLE + "JJ" + TIME_UNIT + ")" + SCHEDULED_FUTURE)) {
			descriptor = "(" + SCHEDULED_EXECUTOR_DESC + RUNNABLE + "JJ" + TIME_UNIT + ")" + SCHEDULED_FUTURE;
		} else return null;
		Resolution resolution = hierarchy.scheduledExecutor(call.owner, new HashSet<>());
		if (resolution == Resolution.UNKNOWN) {
			report("scheduled-executor-attribution-incomplete:" + caller.replace('/', '.') + ":"
					+ call.owner.replace('/', '.') + "." + call.name + call.desc);
			return null;
		}
		if (resolution != Resolution.YES) return null;
		return new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, call.name, descriptor, false);
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

		private Resolution scheduledExecutor(String owner, Set<String> visited) {
			if (owner.equals(SCHEDULED_EXECUTOR_SERVICE)) return Resolution.YES;
			if (!visited.add(owner) || owner.equals("java/lang/Object")) return Resolution.NO;
			try {
				if (owner.equals(current.name)) return scheduledParents(current.superName, current.interfaces, visited);
				try (InputStream bytes = loader.getResourceAsStream(owner + ".class")) {
					if (bytes == null) return Resolution.UNKNOWN;
					ClassReader reader = new ClassReader(bytes);
					return scheduledParents(reader.getSuperName(), Arrays.asList(reader.getInterfaces()), visited);
				}
			} catch (Throwable ignored) { return Resolution.UNKNOWN; }
		}

		private Resolution scheduledParents(String superclass, java.util.List<String> interfaces, Set<String> visited) {
			boolean unknown = false;
			for (String parent : interfaces) {
				Resolution result = scheduledExecutor(parent, visited);
				if (result == Resolution.YES) return result;
				unknown |= result == Resolution.UNKNOWN;
			}
			if (superclass != null) {
				Resolution result = scheduledExecutor(superclass, visited);
				if (result == Resolution.YES) return result;
				unknown |= result == Resolution.UNKNOWN;
			}
			return unknown ? Resolution.UNKNOWN : Resolution.NO;
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
