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

/** Adds remote context scopes to javax/jakarta Servlet boundaries and async callbacks. */
final class RemoteHttpBoundaryTransformer implements ClassFileTransformer {
	private static final int FILTER_CHAIN = 1;
	private static final int SERVLET = 2;
	private static final int REQUEST_LISTENER = 4;
	private static final int ASYNC_CONTEXT = 8;
	private static final int ASYNC_LISTENER = 16;
	private static final int SERVLET_INPUT_STREAM = 32;
	private static final int SERVLET_OUTPUT_STREAM = 64;
	private static final int READ_LISTENER = 128;
	private static final int WRITE_LISTENER = 256;
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
			String asyncEvent = "L" + namespace + "/AsyncEvent;";
			String readListener = "L" + namespace + "/ReadListener;";
			String writeListener = "L" + namespace + "/WriteListener;";
			String requestResponse = "(" + request + response + ")V";
			String eventMethod = "(" + event + ")V";
			String asyncListenerMethod = "(" + asyncEvent + ")V";
			String setReadListenerMethod = "(" + readListener + ")V";
			String setWriteListenerMethod = "(" + writeListener + ")V";
			String asyncStartMethod = "(Ljava/lang/Runnable;)V";
			final int[] wrapped = {0};
			ClassReader reader = new ClassReader(bytes);
			ClassWriter writer = new LoaderClassWriter(reader,
					ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, writer) {
				@Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
						String signature, String[] exceptions) {
					org.objectweb.asm.MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
					if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return delegate;
					boolean readRegistration = (types.mask & SERVLET_INPUT_STREAM) != 0
							&& name.equals("setReadListener") && desc.equals(setReadListenerMethod);
					boolean writeRegistration = (types.mask & SERVLET_OUTPUT_STREAM) != 0
							&& name.equals("setWriteListener") && desc.equals(setWriteListenerMethod);
					boolean match = ((types.mask & FILTER_CHAIN) != 0 && name.equals("doFilter") && desc.equals(requestResponse))
							|| ((types.mask & SERVLET) != 0 && name.equals("service") && desc.equals(requestResponse))
							|| ((types.mask & REQUEST_LISTENER) != 0 &&
								(name.equals("requestInitialized") || name.equals("requestDestroyed")) && desc.equals(eventMethod))
							|| ((types.mask & ASYNC_CONTEXT) != 0 && name.equals("start") && desc.equals(asyncStartMethod));
					boolean listenerCallback = ((types.mask & READ_LISTENER) != 0 && isReadCallback(name, desc))
							|| ((types.mask & WRITE_LISTENER) != 0 && isWriteCallback(name, desc));
					boolean remoteCallback = ((types.mask & ASYNC_LISTENER) != 0
							&& isAsyncListenerCallback(name) && desc.equals(asyncListenerMethod)) || listenerCallback;
					match |= remoteCallback || readRegistration || writeRegistration;
					if (!match) return delegate;
					wrapped[0]++;
					if ((types.mask & ASYNC_CONTEXT) != 0 && name.equals("start") && desc.equals(asyncStartMethod))
						return new AsyncStartAdvice(delegate, access, name, desc);
					if (readRegistration || writeRegistration)
						return new ListenerRegistrationAdvice(delegate, access, name, desc);
					return new BoundaryAdvice(delegate, access, name, desc, header,
							(types.mask & ASYNC_LISTENER) != 0 && isAsyncListenerCallback(name) && desc.equals(asyncListenerMethod), listenerCallback);
				}
			}, ClassReader.EXPAND_FRAMES);
			return wrapped[0] == 0 ? null : writer.toByteArray();
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static boolean isAsyncListenerCallback(String name) {
		return name.equals("onStartAsync") || name.equals("onComplete") || name.equals("onTimeout") || name.equals("onError");
	}

	private static boolean isReadCallback(String name, String desc) {
		return (name.equals("onDataAvailable") || name.equals("onAllDataRead")) && desc.equals("()V")
				|| name.equals("onError") && desc.equals("(Ljava/lang/Throwable;)V");
	}

	private static boolean isWriteCallback(String name, String desc) {
		return name.equals("onWritePossible") && desc.equals("()V")
				|| name.equals("onError") && desc.equals("(Ljava/lang/Throwable;)V");
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
			if (name.equals(namespace + "/AsyncContext")) return new BoundaryTypes(ASYNC_CONTEXT, namespace);
			if (name.equals(namespace + "/AsyncListener")) return new BoundaryTypes(ASYNC_LISTENER, namespace);
			if (name.equals(namespace + "/ServletInputStream")) return new BoundaryTypes(SERVLET_INPUT_STREAM, namespace);
			if (name.equals(namespace + "/ServletOutputStream")) return new BoundaryTypes(SERVLET_OUTPUT_STREAM, namespace);
			if (name.equals(namespace + "/ReadListener")) return new BoundaryTypes(READ_LISTENER, namespace);
			if (name.equals(namespace + "/WriteListener")) return new BoundaryTypes(WRITE_LISTENER, namespace);
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
		private final boolean asyncListenerCallback;
		private final boolean ioListenerCallback;
		private Label start;
		private int scopeLocal;
		private BoundaryAdvice(org.objectweb.asm.MethodVisitor visitor, int access, String name, String desc,
				String header, boolean asyncListenerCallback, boolean ioListenerCallback) {
			super(Opcodes.ASM9, visitor, access, name, desc);
			this.header = header;
			this.asyncListenerCallback = asyncListenerCallback;
			this.ioListenerCallback = ioListenerCallback;
		}
		@Override protected void onMethodEnter() {
			scopeLocal = newLocal(SCOPE);
			if (asyncListenerCallback || ioListenerCallback) {
				if (asyncListenerCallback) loadArg(0);
				else loadThis();
				invokeStatic(Type.getObjectType(CONTEXT), new Method(asyncListenerCallback ? "enterAsyncListener" : "enterListenerCallback",
						"(Ljava/lang/Object;)Lcom/sap/oss/smarttestpicker/remote/RemoteTestContext$Scope;"));
			} else {
				loadArg(0);
				push(header);
				invokeStatic(Type.getObjectType(CONTEXT), new Method("enter",
						"(Ljava/lang/Object;Ljava/lang/String;)Lcom/sap/oss/smarttestpicker/remote/RemoteTestContext$Scope;"));
			}
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

	private static final class ListenerRegistrationAdvice extends AdviceAdapter {
		private ListenerRegistrationAdvice(org.objectweb.asm.MethodVisitor visitor, int access, String name, String desc) {
			super(Opcodes.ASM9, visitor, access, name, desc);
		}
		@Override protected void onMethodEnter() {
			loadArg(0);
			invokeStatic(Type.getObjectType(CONTEXT), new Method("capture", "()Ljava/lang/String;"));
			invokeStatic(Type.getObjectType(CONTEXT), new Method("associateListener",
					"(Ljava/lang/Object;Ljava/lang/String;)V"));
		}
		@Override protected void onMethodExit(int opcode) {
			if (opcode == ATHROW) {
				loadArg(0);
				push((String) null);
				invokeStatic(Type.getObjectType(CONTEXT), new Method("associateListener",
						"(Ljava/lang/Object;Ljava/lang/String;)V"));
			}
		}
	}

	private static final class AsyncStartAdvice extends AdviceAdapter {
		private AsyncStartAdvice(org.objectweb.asm.MethodVisitor visitor, int access, String name, String desc) {
			super(Opcodes.ASM9, visitor, access, name, desc);
		}
		@Override protected void onMethodEnter() {
			loadArg(0);
			invokeStatic(Type.getObjectType(CONTEXT), new Method("wrap", "(Ljava/lang/Runnable;)Ljava/lang/Runnable;"));
			storeArg(0);
		}
	}
}
