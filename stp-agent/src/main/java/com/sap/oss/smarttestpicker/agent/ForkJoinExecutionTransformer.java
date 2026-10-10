// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.function.Consumer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Runs after method-entry instrumentation so the original descriptor's hit is inside the scope. */
final class ForkJoinExecutionTransformer implements ClassFileTransformer {
	private static final String HOOK = "com/sap/oss/smarttestpicker/runtime/RuntimeHooks";
	private static final String MARKER = "$stp$forkJoinExecution";
	private final Consumer<String> errors;
	ForkJoinExecutionTransformer(Consumer<String> errors) { this.errors = errors; }

	@Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
			ProtectionDomain domain, byte[] bytes) {
		if (loader == null || name == null || bytes == null || ExecutorCallSiteTransformer.excluded(name)) return null;
		try {
			ClassReader reader = new ClassReader(bytes);
			ClassNode node = new ClassNode(Opcodes.ASM9);
			reader.accept(node, 0);
			var hierarchy = new ExecutorCallSiteTransformer.Hierarchy(loader, node);
			if (hierarchy.forkJoinTask(name) != ExecutorCallSiteTransformer.Resolution.YES) return null;
			if (node.fields.stream().anyMatch(field -> field.name.equals(MARKER))) return null;
			boolean recursive = hierarchy.subtype(name, "java/util/concurrent/RecursiveAction", new java.util.HashSet<>())
					== ExecutorCallSiteTransformer.Resolution.YES
					|| hierarchy.subtype(name, "java/util/concurrent/RecursiveTask", new java.util.HashSet<>())
					== ExecutorCallSiteTransformer.Resolution.YES;
			boolean changed = false;
			for (MethodNode method : node.methods) {
				if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
				if (!(method.name.equals("exec") && method.desc.equals("()Z"))
						&& !(recursive && method.name.equals("compute") && Type.getArgumentTypes(method.desc).length == 0)) continue;
				LabelNode start = new LabelNode();
				LabelNode end = new LabelNode();
				LabelNode handler = new LabelNode();
				InsnList entry = new InsnList();
				entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
				entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "enterForkJoin",
						"(Ljava/util/concurrent/ForkJoinTask;)V", false));
				entry.add(start);
				method.instructions.insert(entry);
				for (AbstractInsnNode instruction : method.instructions.toArray()) {
					if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN)
						method.instructions.insertBefore(instruction, exit());
				}
				method.instructions.add(end);
				method.instructions.add(handler);
				// No new locals or changes to existing frames. At the catch-all, only `this` is needed.
				method.instructions.add(new FrameNode(Opcodes.F_FULL, 1, new Object[]{name},
						1, new Object[]{"java/lang/Throwable"}));
				method.instructions.add(exit());
				method.instructions.add(new InsnNode(Opcodes.ATHROW));
				method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
				changed = true;
			}
			if (!changed) return null;
			node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
					| Opcodes.ACC_SYNTHETIC, MARKER, "Z", null, true));
			ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		} catch (Throwable failure) {
			errors.accept("forkjoin-execution-incomplete:" + name + ":" + failure.getClass().getName());
			return null;
		}
	}
	private static MethodInsnNode exit() {
		return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "exitForkJoin", "()V", false);
	}
}
