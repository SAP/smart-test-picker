// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

/** Wraps concrete javax/jakarta Servlet FilterChain.doFilter implementations. */
final class RemoteHttpBoundaryTransformer implements ClassFileTransformer {
	private static final String JAVAX_CHAIN = "javax/servlet/FilterChain";
	private static final String JAKARTA_CHAIN = "jakarta/servlet/FilterChain";
	private static final Type SCOPE = Type.getObjectType("com/sap/oss/smarttestpicker/remote/RemoteTestContext$Scope");
	private final String header;

	RemoteHttpBoundaryTransformer(String header) { this.header = header; }

	@Override public byte[] transform(ClassLoader loader, String internalName, Class<?> redefining,
			ProtectionDomain domain, byte[] bytes) {
		try {
			if (internalName == null || bytes == null || internalName.equals("module-info")) return null;
			ClassNode node = new ClassNode(Opcodes.ASM9);
			new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			String namespace = servletNamespace(loader, node, new HashSet<>());
			if (namespace == null) return null;
			String request = "L" + namespace + "/ServletRequest;";
			String response = "L" + namespace + "/ServletResponse;";
			String descriptor = "(" + request + response + ")V";
			final int[] wrapped = {0};
			ClassReader reader = new ClassReader(bytes);
			ClassWriter writer = new LoaderClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, writer) {
				@Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
						String signature, String[] exceptions) {
					org.objectweb.asm.MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
					if (!name.equals("doFilter") || !desc.equals(descriptor)
							|| (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return delegate;
					wrapped[0]++;
					return new BoundaryAdvice(delegate, access, name, desc, header);
				}
			}, ClassReader.EXPAND_FRAMES);
			return wrapped[0] == 0 ? null : writer.toByteArray();
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String servletNamespace(ClassLoader loader, ClassNode node, Set<String> visited) {
		if (node == null || !visited.add(node.name)) return null;
		for (String iface : node.interfaces) {
			if (iface.equals(JAVAX_CHAIN)) return "javax/servlet";
			if (iface.equals(JAKARTA_CHAIN)) return "jakarta/servlet";
			ClassNode parent = read(loader, iface);
			String match = servletNamespace(loader, parent, visited);
			if (match != null) return match;
		}
		if (node.superName != null) {
			if (node.superName.equals(JAVAX_CHAIN)) return "javax/servlet";
			if (node.superName.equals(JAKARTA_CHAIN)) return "jakarta/servlet";
			return servletNamespace(loader, read(loader, node.superName), visited);
		}
		return null;
	}

	private static ClassNode read(ClassLoader loader, String name) {
		String resource = name + ".class";
		try (InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(resource)
				: loader.getResourceAsStream(resource)) {
			if (in == null) return null;
			ClassNode node = new ClassNode(Opcodes.ASM9);
			new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		} catch (Exception ignored) { return null; }
	}

	private static final class LoaderClassWriter extends ClassWriter {
		private final ClassLoader loader;
		private LoaderClassWriter(ClassReader reader, int flags, ClassLoader loader) {
			super(reader, flags);
			this.loader = loader;
		}
		@Override protected String getCommonSuperClass(String leftName, String rightName) {
			try {
				Class<?> left = Class.forName(leftName.replace('/', '.'), false, loader);
				Class<?> right = Class.forName(rightName.replace('/', '.'), false, loader);
				if (left.isAssignableFrom(right)) return leftName;
				if (right.isAssignableFrom(left)) return rightName;
				if (left.isInterface() || right.isInterface()) return "java/lang/Object";
				do { left = left.getSuperclass(); } while (left != null && !left.isAssignableFrom(right));
				return left == null ? "java/lang/Object" : Type.getInternalName(left);
			} catch (LinkageError | ClassNotFoundException ignored) {
				return "java/lang/Object";
			}
		}
	}

	private static final class BoundaryAdvice extends AdviceAdapter {
		private final String header;
		private Label start;
		private Label end;
		private Label handler;
		private int scopeLocal;
		private BoundaryAdvice(org.objectweb.asm.MethodVisitor visitor, int access, String name, String desc, String header) {
			super(Opcodes.ASM9, visitor, access, name, desc);
			this.header = header;
		}
		@Override protected void onMethodEnter() {
			scopeLocal = newLocal(SCOPE);
			loadArg(0);
			push(header);
			invokeStatic(Type.getObjectType("com/sap/oss/smarttestpicker/remote/RemoteTestContext"),
					new Method("enter", "(Ljava/lang/Object;Ljava/lang/String;)Lcom/sap/oss/smarttestpicker/remote/RemoteTestContext$Scope;"));
			storeLocal(scopeLocal);
			start = new Label();
			mark(start);
		}
		@Override protected void onMethodExit(int opcode) {
			if (opcode != ATHROW) closeScope();
		}
		@Override public void visitMaxs(int maxStack, int maxLocals) {
			end = new Label();
			handler = new Label();
			mark(end);
			visitTryCatchBlock(start, end, handler, null);
			mark(handler);
			int throwable = newLocal(Type.getType(Throwable.class));
			storeLocal(throwable);
			closeScope();
			loadLocal(throwable);
			throwException();
			super.visitMaxs(maxStack, maxLocals);
		}
		private void closeScope() {
			loadLocal(scopeLocal);
			invokeVirtual(SCOPE, new Method("close", "()V"));
		}
	}
}
