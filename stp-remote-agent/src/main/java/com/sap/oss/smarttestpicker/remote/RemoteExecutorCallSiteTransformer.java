// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

/** Adds OTel Context wrappers at measured Thread, scheduled-task and CF registration gaps. */
final class RemoteExecutorCallSiteTransformer implements ClassFileTransformer {
	private static final String CONTEXT = "com/sap/oss/smarttestpicker/remote/RemoteTestContext";
	private static final String RUNNABLE = "Ljava/lang/Runnable;";
	private static final String CF = "java/util/concurrent/CompletableFuture";
	private static final String CF_DESC = "Ljava/util/concurrent/CompletableFuture;";
	private static final String FUNCTION = "Ljava/util/function/Function;";
	private static final String SCHEDULED_EXECUTOR = "java/util/concurrent/ScheduledExecutorService";
	private static final String SCHEDULED_FUTURE = "Ljava/util/concurrent/ScheduledFuture;";
	private static final String TIME_UNIT = "Ljava/util/concurrent/TimeUnit;";
	private final RemoteAgentConfiguration configuration;

	RemoteExecutorCallSiteTransformer(RemoteAgentConfiguration configuration) { this.configuration = configuration; }
	RemoteExecutorCallSiteTransformer(RemoteAgentConfiguration configuration, java.util.function.Consumer<String> ignored) {
		this(configuration);
	}

	@Override public byte[] transform(ClassLoader loader, String className, Class<?> redefining,
			ProtectionDomain domain, byte[] bytes) {
		if (loader == null || className == null || bytes == null || excluded(className)
				|| !configuration.instruments(className.replace('/', '.'))) return null;
		try {
			ClassReader reader = new ClassReader(bytes);
			ClassNode node = new ClassNode(Opcodes.ASM9);
			reader.accept(node, 0);
			boolean changed = false;
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
						instruction = instruction.getNext()) {
					if (!(instruction instanceof MethodInsnNode call)) continue;
					if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals("java/lang/reflect/Method")
							&& call.name.equals("invoke") && call.desc.equals("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")) {
						if (!reflectiveHelperVisible(loader)) {
							System.err.println("[stp-remote-agent] reflective-boundary-unavailable: " + className);
							continue;
						}
						method.instructions.insertBefore(call, reflectiveArguments(method));
						changed = true;
						continue;
					}
					MethodInsnNode scheduled = scheduledBridge(call, loader, node);
					if (scheduled != null) {
						call.setOpcode(Opcodes.INVOKESTATIC);
						call.owner = scheduled.owner;
						call.name = scheduled.name;
						call.desc = scheduled.desc;
						call.itf = false;
						changed = true;
						continue;
					}
					if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.name.equals("start") && call.desc.equals("()V")
							&& RemoteThreadBoundaryTransformer.isThread(call.owner, loader, node)) {
						if (RemoteThreadBoundaryTransformer.visible(loader)) {
							call.setOpcode(Opcodes.INVOKESTATIC);
							call.owner = RemoteThreadBoundaryTransformer.HELPER;
							call.desc = "(Ljava/lang/Thread;)V";
							call.itf = false;
							changed = true;
						} else System.err.println("[stp-remote-agent] thread-boundary-unavailable: " + className);
						continue;
					}
					InsnList wrapping = threadWrapping(call, method, loader);
					if (wrapping == null) wrapping = completableFutureWrapping(call);
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
		} catch (Throwable ignored) { return null; }
	}

	private static boolean excluded(String name) {
		return name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/")
				|| name.startsWith("com/sap/oss/smarttestpicker/remote/") || name.startsWith("org/objectweb/asm/");
	}

	private static InsnList reflectiveArguments(MethodNode method) {
		int arguments = method.maxLocals++, receiver = method.maxLocals++, reflected = method.maxLocals++;
		InsnList code = new InsnList();
		code.add(new VarInsnNode(Opcodes.ASTORE, arguments));
		code.add(new VarInsnNode(Opcodes.ASTORE, receiver));
		code.add(new VarInsnNode(Opcodes.ASTORE, reflected));
		code.add(new VarInsnNode(Opcodes.ALOAD, reflected));
		code.add(new VarInsnNode(Opcodes.ALOAD, receiver));
		code.add(new VarInsnNode(Opcodes.ALOAD, reflected));
		code.add(new VarInsnNode(Opcodes.ALOAD, receiver));
		code.add(new VarInsnNode(Opcodes.ALOAD, arguments));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"com/sap/oss/smarttestpicker/remote/RemoteReflectiveCallbacks", "arguments",
				"(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)[Ljava/lang/Object;", false));
		// Original invokevirtual Method.invoke remains at the original caller: reflection access checks
		// and InvocationTargetException wrapping retain their JVM semantics.
		return code;
	}

	private static boolean reflectiveHelperVisible(ClassLoader loader) {
		try { return Class.forName(RemoteReflectiveCallbacks.class.getName(), false, loader) == RemoteReflectiveCallbacks.class; }
		catch (ClassNotFoundException | LinkageError unavailable) { return false; }
	}

	private static MethodInsnNode scheduledBridge(MethodInsnNode call, ClassLoader loader, ClassNode current) {
		String args;
		if (call.name.equals("schedule") && call.desc.equals("(Ljava/lang/Runnable;J" + TIME_UNIT + ")" + SCHEDULED_FUTURE))
			args = "Ljava/lang/Runnable;J" + TIME_UNIT;
		else if (call.name.equals("schedule") && call.desc.equals("(Ljava/util/concurrent/Callable;J" + TIME_UNIT + ")" + SCHEDULED_FUTURE))
			args = "Ljava/util/concurrent/Callable;J" + TIME_UNIT;
		else if ((call.name.equals("scheduleAtFixedRate") || call.name.equals("scheduleWithFixedDelay"))
				&& call.desc.equals("(Ljava/lang/Runnable;JJ" + TIME_UNIT + ")" + SCHEDULED_FUTURE))
			args = "Ljava/lang/Runnable;JJ" + TIME_UNIT;
		else return null;
		if (!implementsScheduled(call.owner, loader, current, new HashSet<>())) return null;
		return new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, call.name,
				"(L" + SCHEDULED_EXECUTOR + ";" + args + ")" + SCHEDULED_FUTURE, false);
	}

	private static boolean implementsScheduled(String name, ClassLoader loader, ClassNode current, Set<String> visited) {
		if (name.equals(SCHEDULED_EXECUTOR)) return true;
		if (!visited.add(name) || name.equals("java/lang/Object")) return false;
		try {
			String superName;
			String[] interfaces;
			if (name.equals(current.name)) { superName = current.superName; interfaces = current.interfaces.toArray(String[]::new); }
			else try (InputStream input = loader.getResourceAsStream(name + ".class")) {
				if (input == null) return false;
				org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(input);
				superName = reader.getSuperName();
				interfaces = reader.getInterfaces();
			}
			for (String parent : interfaces) if (implementsScheduled(parent, loader, current, visited)) return true;
			return superName != null && implementsScheduled(superName, loader, current, visited);
		} catch (Throwable ignored) { return false; }
	}

	private static InsnList completableFutureWrapping(MethodInsnNode call) {
		if (!call.owner.equals(CF)) return null;
		if (call.getOpcode() != Opcodes.INVOKEVIRTUAL) return null;
		boolean async = call.name.endsWith("Async");
		String name = async ? call.name.substring(0, call.name.length() - 5) : call.name;
		String argument;
		String hook;
		switch (name) {
			case "thenRun", "runAfterBoth", "runAfterEither" -> { argument = RUNNABLE; hook = "wrap"; }
			case "thenApply", "thenCompose", "exceptionally", "applyToEither" -> { argument = FUNCTION; hook = "wrapFunction"; }
			case "thenAccept", "acceptEither" -> { argument = "Ljava/util/function/Consumer;"; hook = "wrapConsumer"; }
			case "handle", "thenCombine" -> { argument = "Ljava/util/function/BiFunction;"; hook = "wrapBiFunction"; }
			case "whenComplete", "thenAcceptBoth" -> { argument = "Ljava/util/function/BiConsumer;"; hook = "wrapBiConsumer"; }
			default -> { return null; }
		}
		boolean binary = Set.of("thenCombine", "thenAcceptBoth", "runAfterBoth", "applyToEither",
				"acceptEither", "runAfterEither").contains(name);
		String parameters = (binary ? "Ljava/util/concurrent/CompletionStage;" : "") + argument;
		boolean explicitExecutor = async && call.desc.equals("(" + parameters + "Ljava/util/concurrent/Executor;)" + CF_DESC);
		if (!explicitExecutor && !call.desc.equals("(" + parameters + ")" + CF_DESC)) return null;
		// Callback is the last argument, or immediately below the category-1 Executor reference.
		// Capture even when no STP identity exists: a completing request must not supply its identity.
		InsnList result = new InsnList();
		if (explicitExecutor) result.add(new InsnNode(Opcodes.SWAP));
		result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, hook, "(" + argument + ")" + argument, false));
		if (explicitExecutor) result.add(new InsnNode(Opcodes.SWAP));
		return result;
	}

	/** Deferred constructors/builders never capture Context; the actual start boundary does. */
	private static InsnList threadWrapping(MethodInsnNode call, MethodNode method, ClassLoader loader) {
		boolean constructor = call.owner.equals("java/lang/Thread") && call.name.equals("<init>")
				&& Set.of("(" + RUNNABLE + ")V", "(" + RUNNABLE + "Ljava/lang/String;)V",
						"(Ljava/lang/ThreadGroup;" + RUNNABLE + ")V",
						"(Ljava/lang/ThreadGroup;" + RUNNABLE + "Ljava/lang/String;)V",
						"(Ljava/lang/ThreadGroup;" + RUNNABLE + "Ljava/lang/String;J)V",
						"(Ljava/lang/ThreadGroup;" + RUNNABLE + "Ljava/lang/String;JZ)V").contains(call.desc);
		boolean builder = Set.of("java/lang/Thread$Builder", "java/lang/Thread$Builder$OfPlatform",
				"java/lang/Thread$Builder$OfVirtual").contains(call.owner);
		boolean direct = call.owner.equals("java/lang/Thread") && call.name.equals("startVirtualThread")
				|| builder && call.name.equals("start");
		boolean deferred = builder && call.name.equals("unstarted")
				|| call.owner.equals("java/util/concurrent/ThreadFactory") && call.name.equals("newThread");
		if (!constructor && !(direct || deferred) || !constructor && !call.desc.equals("(" + RUNNABLE + ")Ljava/lang/Thread;")) return null;
		if (!RemoteThreadBoundaryTransformer.visible(loader)) {
			System.err.println("[stp-remote-agent] thread-boundary-unavailable: helper not visible for " + call.owner);
			return null;
		}
		InsnList result = new InsnList();
		Type[] args = Type.getArgumentTypes(call.desc);
		int runnable = 0;
		while (!args[runnable].getDescriptor().equals(RUNNABLE)) runnable++;
		int[] locals = new int[args.length];
		// Spill trailing name/stack-size/inherit arguments; handles category-2 longs without SWAP tricks.
		for (int i = args.length - 1; i > runnable; i--) {
			locals[i] = method.maxLocals;
			method.maxLocals += args[i].getSize();
			result.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), locals[i]));
		}
		result.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				direct ? CONTEXT : RemoteThreadBoundaryTransformer.HELPER,
				direct ? "wrap" : "prepare", "(" + RUNNABLE + ")" + RUNNABLE, false));
		for (int i = runnable + 1; i < args.length; i++) result.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), locals[i]));
		return result;
	}
}
