// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.function.Consumer;

/** Narrow, property-gated instrumentation for the three ROUND 13 targets and their map path. */
final class Round13ClassFileTransformer implements ClassFileTransformer {
	private static final String HOOK = "com/sap/oss/smarttestpicker/runtime/RuntimeHooks";
	private static final Set<String> CLASSES = Set.of(
			"org/springframework/util/ConcurrentReferenceHashMap",
			"org/springframework/util/ConcurrentReferenceHashMap$Segment",
			"org/springframework/util/ConcurrentReferenceHashMap$SoftEntryReference",
			"org/springframework/core/ResolvableType");
	private static final Set<String> MAP_METHODS = Set.of("get", "getReference", "put", "putIfAbsent", "doTask",
			"restructureIfNecessary", "restructure", "createReference", "getHash", "getNext", "getInterfaces");
	private final Consumer<String> errors;

	Round13ClassFileTransformer(Consumer<String> errors) { this.errors = errors; }

	@Override public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
			ProtectionDomain domain, byte[] bytes) {
		if (!CLASSES.contains(className)) return null;
		try {
			ClassNode node = new ClassNode(Opcodes.ASM9);
			ClassReader reader = new ClassReader(bytes);
			reader.accept(node, 0);
			boolean changed = false;
			for (MethodNode method : node.methods) {
				if (!MAP_METHODS.contains(method.name) || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0) continue;
				String id = node.name.replace('/', '.') + "#" + method.name + method.desc;
				Type[] args = Type.getArgumentTypes(method.desc);
				InsnList enter = new InsnList();
				enter.add(new LdcInsnNode(id)); enter.add(new VarInsnNode(Opcodes.ALOAD, 0));
				if (args.length == 0) enter.add(new InsnNode(Opcodes.ACONST_NULL));
				else loadAndBox(enter, args[0], 1);
				enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round13Enter", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", false));
				method.instructions.insertBefore(method.instructions.getFirst(), enter);
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
					AbstractInsnNode next = insn.getNext(); int opcode = insn.getOpcode();
					if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
						Type result = Type.getReturnType(method.desc); int local = method.maxLocals; method.maxLocals += result.getSize();
						InsnList exit = new InsnList(); exit.add(new VarInsnNode(result.getOpcode(Opcodes.ISTORE), local));
						exit.add(new LdcInsnNode(id)); exit.add(new VarInsnNode(Opcodes.ALOAD, 0)); loadAndBox(exit, result, local);
						exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round13Exit", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", false));
						exit.add(new VarInsnNode(result.getOpcode(Opcodes.ILOAD), local)); method.instructions.insertBefore(insn, exit);
					} else if (opcode == Opcodes.RETURN) {
						InsnList exit = new InsnList(); exit.add(new LdcInsnNode(id)); exit.add(new VarInsnNode(Opcodes.ALOAD, 0)); exit.add(new InsnNode(Opcodes.ACONST_NULL));
						exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round13Exit", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", false)); method.instructions.insertBefore(insn, exit);
					}
					insn = next;
				}
				changed = true;
			}
			if (!changed) return null;
			ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
		} catch (Throwable failure) { errors.accept("round13-transformation-error:" + className + ":" + failure); return null; }
	}

	private static void loadAndBox(InsnList list, Type type, int local) {
		list.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
		switch (type.getSort()) {
			case Type.BOOLEAN -> box(list, "Boolean", "(Z)Ljava/lang/Boolean;"); case Type.BYTE -> box(list, "Byte", "(B)Ljava/lang/Byte;");
			case Type.CHAR -> box(list, "Character", "(C)Ljava/lang/Character;"); case Type.SHORT -> box(list, "Short", "(S)Ljava/lang/Short;");
			case Type.INT -> box(list, "Integer", "(I)Ljava/lang/Integer;"); case Type.FLOAT -> box(list, "Float", "(F)Ljava/lang/Float;");
			case Type.LONG -> box(list, "Long", "(J)Ljava/lang/Long;"); case Type.DOUBLE -> box(list, "Double", "(D)Ljava/lang/Double;"); default -> { }
		}
	}
	private static void box(InsnList list, String owner, String desc) { list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/" + owner, "valueOf", desc, false)); }
}
