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

/** Adds request context scopes to standard javax/jakarta Servlet lifecycle entry points. */
final class RemoteHttpBoundaryTransformer implements ClassFileTransformer {
	private static final int FILTER_CHAIN = 1;
	private static final int SERVLET = 2;
	private static final int REQUEST_LISTENER = 4;
	private static final Type SCOPE = Type.getObjectType("com/sap/oss/smarttestpicker/remote/RemoteTestContext$Scope");
	private static final String CONTEXT = "com/sap/oss/smarttestpicker/remote/RemoteTestContext";
	private static final String REQUEST = "ServletRequest";
	private static final String RESPONSE = "ServletResponse";
	private static final String EVENT = "ServletRequestEvent";
	private final String header;

	RemoteHttpBoundaryTransformer(String header) { this.header = header; }

	@Override public byte[] transform(ClassLoader loader, String internalName, Class<?> redefining,
			ProtectionDomain domain, byte[] bytes) {
		try {
			if (internalName == null || bytes == null || internalName.equals("module-info")) return null;
			ClassNode node = new ClassNode(Opcodes.ASM9);
			new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			BoundaryTypes types = boundaryTypes(loader, node, new HashSet<>());
			if (types.mask == 0) return null;
			String namespace = types.namespace;
			String request = "L" + namespace + "/" + REQUEST + ";";
			String response = "L" + namespace + "/" + RESPONSE + ";";
			String event = "L" + namespace + "/" + EVENT + ";";
			String requestResponse = "(" + request + response + ")V";
			String eventMethod = "(" + event + ")V";
			final int[] wrapped = {0};
			ClassReader reader = new ClassReader(bytes);
			ClassWriter writer = new LoaderClassWriter(reader,
					ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, writer) {
				@Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
						String signature, String[] exceptions) {
					org.objectweb.asm.MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
					if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return delegate;
					boolean match = ((types.mask & FILTER_CHAIN) != 0 && name.equals("doFilter") && desc.equals(requestResponse))
							|| ((types.mask & SERVLET) != 0 && name.equals("service") && desc.equals(requestResponse))
							|| ((types.mask & REQUEST_LISTENER) != 0 &&
								(name.equals("requestInitialized") || name.equals("requestDestroyed")) && desc.equals(eventMethod));
					if (!match) return delegate;
					wrapped[0]++;
					return new BoundaryAdvice(delegate, access, name, desc, header);
				}
			}, ClassReader.EXPAND_FRAMES);
			return wrapped[0] == 0 ? null : writer.toByteArray();
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static BoundaryTypes boundaryTypes(ClassLoader loader, ClassNode node, Set<String> visited) {
		if (node == null || !visited.add(node.name)) return BoundaryTypes.NONE;
		int mask = 0;
		String namespace = null;
		for (String iface : node.interfaces) {
			BoundaryTypes match = apiType(iface);
			if (match.mask == 0) match = boundaryTypes(loader, read(loader, iface), visited);
			mask |= match.mask;
			if (namespace == null && match.namespace != null) namespace = match.namespace;
		}
		if (node.superName != null) {
			BoundaryTypes match = apiType(node.superName);
			if (match.mask == 0) match = boundaryTypes(loader, read(loader, node.superName), visited);
			mask |= match.mask;
			if (namespace == null && match.namespace != null) namespace = match.namespace;
		}
		return new BoundaryTypes(mask, namespace);
	}

	private static BoundaryTypes apiType(String name) {
		for (String namespace : new String[] {"javax/servlet", "jakarta/servlet"}) {
			if (name.equals(namespace + "/FilterChain")) return new BoundaryTypes(FILTER_CHAIN, namespace);
			if (name.equals(namespace + "/Servlet")) return new BoundaryTypes(SERVLET, namespace);
			if (name.equals(namespace + "/ServletRequestListener")) return new BoundaryTypes(REQUEST_LISTENER, namespace);
		}
		return BoundaryTypes.NONE;
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

	private record BoundaryTypes(int mask, String namespace) {
		private static final BoundaryTypes NONE = new BoundaryTypes(0, null);
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
		private int scopeLocal;
		private BoundaryAdvice(org.objectweb.asm.MethodVisitor visitor, int access, String name, String desc, String header) {
			super(Opcodes.ASM9, visitor, access, name, desc);
			this.header = header;
		}
		@Override protected void onMethodEnter() {
			scopeLocal = newLocal(SCOPE);
			loadArg(0);
			push(header);
			invokeStatic(Type.getObjectType(CONTEXT), new Method("enter",
					"(Ljava/lang/Object;Ljava/lang/String;)Lcom/sap/oss/smarttestpicker/remote/RemoteTestContext$Scope;"));
			storeLocal(scopeLocal);
			start = new Label();
			mark(start);
		}
		@Override protected void onMethodExit(int opcode) {
			if (opcode != ATHROW) closeScope();
		}
		@Override public void visitMaxs(int maxStack, int maxLocals) {
			Label end = new Label();
			Label handler = new Label();
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
