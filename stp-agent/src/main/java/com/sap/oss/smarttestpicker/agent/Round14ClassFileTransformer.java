// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.function.Consumer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Narrow, property-gated BridgeMethodResolver control-flow tracing for ROUND 14. */
final class Round14ClassFileTransformer implements ClassFileTransformer {
	private static final String HOOK = "com/sap/oss/smarttestpicker/runtime/RuntimeHooks";
	private static final String BRIDGE = "org/springframework/core/BridgeMethodResolver";
	private static final String RESOLVABLE = "org/springframework/core/ResolvableType";
	private static final Set<String> METHODS = Set.of("findBridgedMethod", "resolveBridgeMethod", "searchCandidates",
			"isBridgedCandidateFor", "isBridgeMethodFor", "isResolvedTypeMatch", "findGenericDeclaration",
			"searchInterfaces", "searchForMatch");
	private final Consumer<String> errors;

	Round14ClassFileTransformer(Consumer<String> errors) { this.errors = errors; }

	@Override public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
			ProtectionDomain domain, byte[] bytes) {
		if (!BRIDGE.equals(className) && !RESOLVABLE.equals(className)) return null;
		try {
			ClassReader reader = new ClassReader(bytes);
			ClassNode node = new ClassNode(Opcodes.ASM9);
			reader.accept(node, 0);
			boolean changed = false;
			for (MethodNode method : node.methods) {
				if ((BRIDGE.equals(className) && METHODS.contains(method.name)) ||
						(RESOLVABLE.equals(className) && "getInterfaces".equals(method.name))) {
					instrument(node.name, method);
					changed = true;
				}
			}
			if (!changed) return null;
			ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		}
		catch (Throwable failure) {
			errors.accept("round14-transformation-error:" + className + ":" + failure);
			return null;
		}
	}

	private static void instrument(String owner, MethodNode method) {
		String id = owner.replace('/', '.') + "#" + method.name + method.desc;
		Type[] arguments = Type.getArgumentTypes(method.desc);
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		InsnList enter = new InsnList();
		enter.add(new LdcInsnNode(id));
		enter.add(isStatic ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));
		pushInt(enter, arguments.length);
		enter.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		int local = isStatic ? 0 : 1;
		for (int i = 0; i < arguments.length; i++) {
			enter.add(new InsnNode(Opcodes.DUP));
			pushInt(enter, i);
			loadAndBox(enter, arguments[i], local);
			enter.add(new InsnNode(Opcodes.AASTORE));
			local += arguments[i].getSize();
		}
		enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round14Enter",
				"(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)V", false));
		method.instructions.insertBefore(method.instructions.getFirst(), enter);

		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
			AbstractInsnNode next = insn.getNext();
			int opcode = insn.getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
				Type result = Type.getReturnType(method.desc);
				int resultLocal = method.maxLocals;
				method.maxLocals += result.getSize();
				InsnList exit = new InsnList();
				exit.add(new VarInsnNode(result.getOpcode(Opcodes.ISTORE), resultLocal));
				exit.add(new LdcInsnNode(id));
				loadAndBox(exit, result, resultLocal);
				exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round14Exit",
						"(Ljava/lang/String;Ljava/lang/Object;)V", false));
				exit.add(new VarInsnNode(result.getOpcode(Opcodes.ILOAD), resultLocal));
				method.instructions.insertBefore(insn, exit);
			}
			else if (opcode == Opcodes.RETURN) {
				InsnList exit = new InsnList();
				exit.add(new LdcInsnNode(id));
				exit.add(new InsnNode(Opcodes.ACONST_NULL));
				exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "round14Exit",
						"(Ljava/lang/String;Ljava/lang/Object;)V", false));
				method.instructions.insertBefore(insn, exit);
			}
			insn = next;
		}
	}

	private static void pushInt(InsnList list, int value) {
		if (value >= 0 && value <= 5) list.add(new InsnNode(Opcodes.ICONST_0 + value));
		else list.add(new LdcInsnNode(value));
	}

	private static void loadAndBox(InsnList list, Type type, int local) {
		list.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
		switch (type.getSort()) {
			case Type.BOOLEAN -> box(list, "Boolean", "(Z)Ljava/lang/Boolean;");
			case Type.BYTE -> box(list, "Byte", "(B)Ljava/lang/Byte;");
			case Type.CHAR -> box(list, "Character", "(C)Ljava/lang/Character;");
			case Type.SHORT -> box(list, "Short", "(S)Ljava/lang/Short;");
			case Type.INT -> box(list, "Integer", "(I)Ljava/lang/Integer;");
			case Type.FLOAT -> box(list, "Float", "(F)Ljava/lang/Float;");
			case Type.LONG -> box(list, "Long", "(J)Ljava/lang/Long;");
			case Type.DOUBLE -> box(list, "Double", "(D)Ljava/lang/Double;");
			default -> { }
		}
	}

	private static void box(InsnList list, String owner, String descriptor) {
		list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/" + owner, "valueOf", descriptor, false));
	}
}
