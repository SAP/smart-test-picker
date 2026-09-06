// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.function.Consumer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Exact, property-gated call-site tracing for the ROUND 15 reflection pipeline. */
final class Round15ClassFileTransformer implements ClassFileTransformer {
	private static final String HOOK = "com/sap/oss/smarttestpicker/runtime/RuntimeHooks";
	private static final String REFLECTION = "org/springframework/util/ReflectionUtils";
	private static final String BRIDGE = "org/springframework/core/BridgeMethodResolver";
	private final Consumer<String> errors;
	Round15ClassFileTransformer(Consumer<String> errors) { this.errors = errors; }

	@Override public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
			ProtectionDomain domain, byte[] bytes) {
		if (!REFLECTION.equals(className) && !BRIDGE.equals(className)) return null;
		try {
			ClassReader reader = new ClassReader(bytes);
			ClassNode node = new ClassNode(Opcodes.ASM9);
			reader.accept(node, 0);
			boolean changed = REFLECTION.equals(className) ? reflection(node) : bridge(node);
			if (!changed) return null;
			ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		}
		catch (Throwable failure) { errors.accept("round15-transformation-error:" + className + ":" + failure); return null; }
	}

	private static boolean reflection(ClassNode node) {
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (method.name.equals("getDeclaredMethods") && method.desc.equals("(Ljava/lang/Class;Z)[Ljava/lang/reflect/Method;")) {
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof MethodInsnNode call && call.owner.equals("java/lang/Class") && call.name.equals("getDeclaredMethods")) {
						InsnList before = new InsnList(); before.add(new InsnNode(Opcodes.DUP));
						method.instructions.insertBefore(insn, before);
						InsnList after = new InsnList();
						after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round15RawDeclaredMethods",
								"(Ljava/lang/Class;[Ljava/lang/reflect/Method;)[Ljava/lang/reflect/Method;", false));
						method.instructions.insert(insn, after); changed = true;
					}
				}
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) if (insn.getOpcode() == Opcodes.ARETURN) {
					InsnList hook = new InsnList(); hook.add(new InsnNode(Opcodes.DUP)); hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
					hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round15ReflectionUtilsReturn",
							"([Ljava/lang/reflect/Method;Ljava/lang/Class;)V", false));
					method.instructions.insertBefore(insn, hook); changed = true;
				}
			}
			if (method.name.equals("doWithMethods") && method.desc.equals("(Ljava/lang/Class;Lorg/springframework/util/ReflectionUtils$MethodCallback;Lorg/springframework/util/ReflectionUtils$MethodFilter;)V")) {
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof MethodInsnNode call && call.name.equals("matches")) {
						InsnList after = new InsnList(); after.add(new VarInsnNode(Opcodes.ALOAD, 7)); after.add(new InsnNode(Opcodes.SWAP));
						after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round15Filter",
								"(Ljava/lang/reflect/Method;Z)Z", false)); method.instructions.insert(insn, after); changed = true;
					}
					if (insn instanceof MethodInsnNode call && call.name.equals("doWith")) {
						InsnList after = new InsnList(); after.add(new VarInsnNode(Opcodes.ALOAD, 7));
						after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round15Callback", "(Ljava/lang/reflect/Method;)V", false));
						method.instructions.insert(insn, after); changed = true;
					}
				}
			}
		}
		return changed;
	}

	private static boolean bridge(ClassNode node) {
		for (MethodNode method : node.methods) if (method.name.equals("searchCandidates")) {
			InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
			hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round15SearchCandidates", "(Ljava/util/List;)V", false));
			method.instructions.insertBefore(method.instructions.getFirst(), hook); return true;
		}
		return false;
	}
}
