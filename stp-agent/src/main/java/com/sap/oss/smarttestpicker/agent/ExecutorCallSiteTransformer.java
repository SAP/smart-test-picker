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
	private static final String EXECUTOR = "Ljava/util/concurrent/Executor;";
	private static final String FUTURE = "Ljava/util/concurrent/Future;";
	private static final String FORK_JOIN_TASK = "Ljava/util/concurrent/ForkJoinTask;";
	private static final String COMPLETABLE_FUTURE = "Ljava/util/concurrent/CompletableFuture;";
	private static final String SUPPLIER = "Ljava/util/function/Supplier;";
	private static final String FUNCTION = "Ljava/util/function/Function;";
	private static final String SCHEDULED_EXECUTOR = "Ljava/util/concurrent/ScheduledExecutorService;";
	private static final String SCHEDULED_FUTURE = "Ljava/util/concurrent/ScheduledFuture;";
	private static final String TIME_UNIT = "Ljava/util/concurrent/TimeUnit;";
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
					InsnList threadWrapping = threadWrapping(call);
					if (threadWrapping != null) {
						method.instructions.insertBefore(call, threadWrapping);
						changed = true;
						continue;
					}
					MethodInsnNode scheduled = scheduledBridge(call, hierarchy);
					if (scheduled != null) {
						call.setOpcode(Opcodes.INVOKESTATIC);
						call.owner = scheduled.owner;
						call.name = scheduled.name;
						call.desc = scheduled.desc;
						call.itf = false;
						changed = true;
						continue;
					}
					InsnList completableFutureWrapping = completableFutureWrapping(call);
					if (completableFutureWrapping != null) {
						method.instructions.insertBefore(call, completableFutureWrapping);
						changed = true;
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

	private MethodInsnNode scheduledBridge(MethodInsnNode call, Hierarchy hierarchy) {
		String bridgeDescriptor;
		if (call.name.equals("schedule") && call.desc.equals("(" + RUNNABLE + "J" + TIME_UNIT + ")" + SCHEDULED_FUTURE)) {
			bridgeDescriptor = "(" + SCHEDULED_EXECUTOR + RUNNABLE + "J" + TIME_UNIT + ")" + SCHEDULED_FUTURE;
		} else if (call.name.equals("schedule") && call.desc.equals("(" + CALLABLE + "J" + TIME_UNIT + ")" + SCHEDULED_FUTURE)) {
			bridgeDescriptor = "(" + SCHEDULED_EXECUTOR + CALLABLE + "J" + TIME_UNIT + ")" + SCHEDULED_FUTURE;
		} else if ((call.name.equals("scheduleAtFixedRate") || call.name.equals("scheduleWithFixedDelay"))
				&& call.desc.equals("(" + RUNNABLE + "JJ" + TIME_UNIT + ")" + SCHEDULED_FUTURE)) {
			bridgeDescriptor = "(" + SCHEDULED_EXECUTOR + RUNNABLE + "JJ" + TIME_UNIT + ")" + SCHEDULED_FUTURE;
		} else return null;
		Resolution resolution = hierarchy.scheduledExecutor(call.owner);
		if (resolution == Resolution.UNKNOWN) errorSink.accept("scheduled-executor-attribution-incomplete:"
				+ call.owner.replace('/', '.') + "." + call.name + call.desc);
		if (resolution != Resolution.YES) return null;
		return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, call.name, bridgeDescriptor, false);
	}

	private static InsnList threadWrapping(MethodInsnNode call) {
		boolean direct = call.owner.equals("java/lang/Thread") && call.name.equals("<init>")
				&& (call.desc.equals("(" + RUNNABLE + ")V") || call.desc.equals("(Ljava/lang/ThreadGroup;" + RUNNABLE + ")V"));
		boolean beforeReference = call.owner.equals("java/lang/Thread") && call.name.equals("<init>")
				&& (call.desc.equals("(" + RUNNABLE + "Ljava/lang/String;)V")
				|| call.desc.equals("(Ljava/lang/ThreadGroup;" + RUNNABLE + "Ljava/lang/String;)V"));
		boolean virtual = call.name.equals("startVirtualThread") && call.owner.equals("java/lang/Thread")
				&& call.desc.equals("(" + RUNNABLE + ")Ljava/lang/Thread;");
		boolean virtualBuilder = call.name.equals("start") && call.owner.startsWith("java/lang/Thread$Builder")
				&& call.desc.equals("(" + RUNNABLE + ")Ljava/lang/Thread;");
		if (!direct && !beforeReference && !virtual && !virtualBuilder) return null;
		InsnList result = new InsnList();
		if (beforeReference) result.add(new InsnNode(Opcodes.SWAP));
		result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "wrap", "(" + RUNNABLE + ")" + RUNNABLE, false));
		if (beforeReference) result.add(new InsnNode(Opcodes.SWAP));
		return result;
	}

	private static boolean executorShape(MethodInsnNode call) {
		return (call.name.equals("execute") && call.desc.equals("(" + RUNNABLE + ")V"))
				|| (call.name.equals("submit") && (call.desc.equals("(" + RUNNABLE + ")" + FUTURE)
				|| call.desc.equals("(" + RUNNABLE + "Ljava/lang/Object;)" + FUTURE)
				|| call.desc.equals("(" + CALLABLE + ")" + FUTURE)
				|| call.desc.equals("(" + RUNNABLE + ")" + FORK_JOIN_TASK)
				|| call.desc.equals("(" + RUNNABLE + "Ljava/lang/Object;)" + FORK_JOIN_TASK)
				|| call.desc.equals("(" + CALLABLE + ")" + FORK_JOIN_TASK)));
	}

	private static InsnList wrapping(MethodInsnNode call) {
		InsnList result = new InsnList();
		if ((call.name.equals("execute") || call.name.equals("submit")) && call.desc.startsWith("(" + RUNNABLE)) {
			if (twoArgumentRunnable(call.desc)) result.add(new InsnNode(Opcodes.SWAP));
			result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "wrap",
					"(" + RUNNABLE + ")" + RUNNABLE, false));
			if (twoArgumentRunnable(call.desc)) result.add(new InsnNode(Opcodes.SWAP));
			return result;
		}
		if (call.name.equals("submit") && call.desc.startsWith("(" + CALLABLE)) {
			result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "wrap",
					"(" + CALLABLE + ")" + CALLABLE, false));
			return result;
		}
		return null;
	}

	private static boolean twoArgumentRunnable(String descriptor) {
		return descriptor.equals("(" + RUNNABLE + "Ljava/lang/Object;)" + FUTURE)
				|| descriptor.equals("(" + RUNNABLE + "Ljava/lang/Object;)" + FORK_JOIN_TASK);
	}

	private static InsnList completableFutureWrapping(MethodInsnNode call) {
		if (!call.owner.equals("java/util/concurrent/CompletableFuture")) return null;
		String argument;
		if (call.name.equals("runAsync") && (call.desc.equals("(" + RUNNABLE + ")" + COMPLETABLE_FUTURE)
				|| call.desc.equals("(" + RUNNABLE + EXECUTOR + ")" + COMPLETABLE_FUTURE))) {
			argument = RUNNABLE;
		} else if (call.name.equals("supplyAsync") && (call.desc.equals("(" + SUPPLIER + ")" + COMPLETABLE_FUTURE)
				|| call.desc.equals("(" + SUPPLIER + EXECUTOR + ")" + COMPLETABLE_FUTURE))) {
			argument = SUPPLIER;
		} else if (call.name.equals("thenRunAsync") && (call.desc.equals("(" + RUNNABLE + ")" + COMPLETABLE_FUTURE)
				|| call.desc.equals("(" + RUNNABLE + EXECUTOR + ")" + COMPLETABLE_FUTURE))) {
			argument = RUNNABLE;
		} else if (call.name.equals("thenApplyAsync") && (call.desc.equals("(" + FUNCTION + ")" + COMPLETABLE_FUTURE)
				|| call.desc.equals("(" + FUNCTION + EXECUTOR + ")" + COMPLETABLE_FUTURE))) {
			argument = FUNCTION;
		} else {
			return null;
		}

		InsnList result = new InsnList();
		boolean explicitExecutor = call.desc.contains(EXECUTOR);
		if (explicitExecutor) result.add(new InsnNode(Opcodes.SWAP));
		String hookMethod = argument.equals(SUPPLIER) ? "wrapSupplier"
				: argument.equals(FUNCTION) ? "wrapFunction" : "wrap";
		result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hookMethod,
				"(" + argument + ")" + argument, false));
		if (explicitExecutor) result.add(new InsnNode(Opcodes.SWAP));
		return result;
	}

	private static boolean excluded(String name) {
		return name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/")
				|| name.startsWith("org/gradle/")
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

		private Resolution scheduledExecutor(String owner) {
			return subtype(owner, "java/util/concurrent/ScheduledExecutorService", new HashSet<>());
		}

		private Resolution subtype(String owner, String root, Set<String> visited) {
			if (owner.equals(root)) return Resolution.YES;
			if (!visited.add(owner) || owner.equals("java/lang/Object")) return Resolution.NO;
			try {
				ClassReader reader;
				if (owner.equals(current.name)) return subtypeParents(current.superName, current.interfaces, root, visited);
				try (InputStream bytes = loader.getResourceAsStream(owner + ".class")) {
					if (bytes == null) return Resolution.UNKNOWN;
					reader = new ClassReader(bytes);
				}
				return subtypeParents(reader.getSuperName(), java.util.Arrays.asList(reader.getInterfaces()), root, visited);
			} catch (Throwable ignored) { return Resolution.UNKNOWN; }
		}

		private Resolution subtypeParents(String superclass, java.util.List<String> interfaces, String root,
				Set<String> visited) {
			boolean unknown = false;
			for (String parent : interfaces) {
				Resolution result = subtype(parent, root, visited);
				if (result == Resolution.YES) return result;
				unknown |= result == Resolution.UNKNOWN;
			}
			if (superclass != null) {
				Resolution result = subtype(superclass, root, visited);
				if (result == Resolution.YES) return result;
				unknown |= result == Resolution.UNKNOWN;
			}
			return unknown ? Resolution.UNKNOWN : Resolution.NO;
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
