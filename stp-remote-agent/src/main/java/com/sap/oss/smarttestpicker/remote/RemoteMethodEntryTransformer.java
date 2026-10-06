// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

final class RemoteMethodEntryTransformer implements ClassFileTransformer {
	private static final String MARKER = "$stp$remote$instrumented$v1";
	private final RemoteAgentConfiguration configuration;

	RemoteMethodEntryTransformer(RemoteAgentConfiguration configuration) { this.configuration = configuration; }

	@Override public byte[] transform(ClassLoader loader, String internalName, Class<?> redefining,
			ProtectionDomain domain, byte[] bytes) {
		try {
			if (internalName == null || bytes == null || internalName.equals("module-info")) return null;
			String className = internalName.replace('/', '.');
			if (!configuration.instruments(className) || className.startsWith("com.sap.oss.smarttestpicker.remote.")) return null;
			ClassNode node = new ClassNode(Opcodes.ASM9);
			new ClassReader(bytes).accept(node, 0);
			if ((node.access & Opcodes.ACC_ANNOTATION) != 0
					|| node.fields.stream().anyMatch(field -> MARKER.equals(field.name))) return null;
			int instrumented = 0;
			for (MethodNode method : node.methods) {
				if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
						|| method.instructions == null || method.instructions.size() == 0) continue;
				InsnList hook = new InsnList();
				hook.add(new LdcInsnNode(className));
				hook.add(new LdcInsnNode(method.name));
				hook.add(new LdcInsnNode(method.desc));
				hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
						"com/sap/oss/smarttestpicker/remote/RemoteHooks", "methodHit",
						"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false));
				method.instructions.insert(hook);
				instrumented++;
			}
			if (instrumented == 0) return null;
			node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
					| Opcodes.ACC_SYNTHETIC, MARKER, "Z", null, Boolean.TRUE));
			ClassWriter writer = new ClassWriter(new ClassReader(bytes), ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		} catch (Throwable ignored) {
			// Instrumentation is observational and must not break application class loading.
			return null;
		}
	}
}
