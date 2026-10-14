// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Injects the STP method-entry hook at the top of every instrumented method. Uses a streaming
 * {@link ClassVisitor}/{@link MethodVisitor} pass (no {@code ClassNode}, no {@code COMPUTE_MAXS}):
 * on method entry it pushes the precomputed {@code long} method id and calls
 * {@code RemoteHooks.methodHit(J)V}, then widens {@code maxStack} by the two slots the long needs.
 * This keeps the class-transform wave cheap — ASM never rebuilds a node tree or recomputes frames,
 * which on a large Hybris classpath is the dominant load-time cost.
 *
 * <p>When {@link RemoteAgentConfiguration#skipTrivial()} is set, trivial methods are left untouched
 * (see {@link #isTrivial}). Everything about the output contract — serialized {@code className#name+desc}
 * keys, the marker field, the skip of abstract/native/empty/annotation members — is unchanged.
 */
final class RemoteMethodEntryTransformer implements ClassFileTransformer {
	private static final String MARKER = "$stp$remote$instrumented$v1";
	private static final String HOOKS = "com/sap/oss/smarttestpicker/remote/RemoteHooks";
	private final RemoteAgentConfiguration configuration;

	RemoteMethodEntryTransformer(RemoteAgentConfiguration configuration) { this.configuration = configuration; }

	@Override public byte[] transform(ClassLoader loader, String internalName, Class<?> redefining,
			ProtectionDomain domain, byte[] bytes) {
		try {
			if (internalName == null || bytes == null || internalName.equals("module-info")) return null;
			String className = internalName.replace('/', '.');
			if (!configuration.instruments(className) || className.startsWith("com.sap.oss.smarttestpicker.remote.")) return null;
			try {
				if (Class.forName(RemoteHooks.class.getName(), false, loader) != RemoteHooks.class)
					throw new ClassNotFoundException("different RemoteHooks class");
			} catch (ClassNotFoundException | LinkageError inaccessible) {
				System.err.println("[stp-remote-agent] method-hook-unavailable: " + internalName + " cannot resolve shared RemoteHooks");
				return null;
			}
			ClassReader reader = new ClassReader(bytes);
			// Cheap first pass: detect annotation types and the already-instrumented marker without
			// building a node tree. Visiting fields only (SKIP_CODE/DEBUG/FRAMES) is near-free.
			PreScan preScan = new PreScan();
			reader.accept(preScan, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if (preScan.annotation || preScan.alreadyInstrumented) return null;

			ClassWriter writer = new ClassWriter(reader, 0);
			InstrumentingClassVisitor visitor = new InstrumentingClassVisitor(writer, className, configuration.skipTrivial());
			reader.accept(visitor, 0);
			if (!visitor.instrumentedAny) return null;
			return writer.toByteArray();
		} catch (Throwable ignored) {
			// Instrumentation is observational and must not break application class loading.
			return null;
		}
	}

	/** Light visitor: records whether the class is an annotation and whether the marker field exists. */
	private static final class PreScan extends ClassVisitor {
		boolean annotation;
		boolean alreadyInstrumented;
		PreScan() { super(Opcodes.ASM9); }
		@Override public void visit(int version, int access, String name, String signature,
				String superName, String[] interfaces) {
			if ((access & Opcodes.ACC_ANNOTATION) != 0) annotation = true;
		}
		@Override public FieldVisitor visitField(int access, String name, String descriptor,
				String signature, Object value) {
			if (MARKER.equals(name)) alreadyInstrumented = true;
			return null;
		}
	}

	private final class InstrumentingClassVisitor extends ClassVisitor {
		private final String className;
		private final boolean skipTrivial;
		private int classAccess;
		boolean instrumentedAny;

		InstrumentingClassVisitor(ClassVisitor delegate, String className, boolean skipTrivial) {
			super(Opcodes.ASM9, delegate);
			this.className = className;
			this.skipTrivial = skipTrivial;
		}

		@Override public void visit(int version, int access, String name, String signature,
				String superName, String[] interfaces) {
			this.classAccess = access;
			super.visit(version, access, name, signature, superName, interfaces);
		}

		@Override public MethodVisitor visitMethod(int access, String name, String descriptor,
				String signature, String[] exceptions) {
			MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
			if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return delegate;
			if (skipTrivial && isTrivial(access, name, descriptor)) return delegate;
			long methodId = RemoteRecorder.catalog().register(className, name, descriptor);
			return new HookMethodVisitor(delegate, methodId);
		}

		@Override public void visitEnd() {
			if (instrumentedAny) {
				int markerAccess = Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
				markerAccess |= (classAccess & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
				FieldVisitor marker = super.visitField(markerAccess, MARKER, "Z", null, Boolean.TRUE);
				if (marker != null) marker.visitEnd();
			}
			super.visitEnd();
		}

		/** Marks a hooked method so {@link #visitEnd} knows to emit the marker field. */
		private final class HookMethodVisitor extends MethodVisitor {
			private final long methodId;
			private boolean hasCode;
			HookMethodVisitor(MethodVisitor delegate, long methodId) {
				super(Opcodes.ASM9, delegate);
				this.methodId = methodId;
			}
			@Override public void visitCode() {
				super.visitCode();
				hasCode = true;
				instrumentedAny = true;
				// Push the precomputed id and call the hook before any application instruction.
				mv.visitLdcInsn(methodId);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "methodHit", "(J)V", false);
			}
			@Override public void visitMaxs(int maxStack, int maxLocals) {
				// The long push needs two stack slots at method entry (empty stack), so the method's
				// peak stack is at least two. No COMPUTE_MAXS needed; the original frames are untouched.
				super.visitMaxs(hasCode ? Math.max(maxStack, 2) : maxStack, maxLocals);
			}
		}
	}

	/**
	 * A method is trivial when instrumenting it buys little coverage signal but costs transform and
	 * per-call overhead: compiler-synthesized members (bridge/synthetic, {@code lambda$}, {@code access$}),
	 * class initializers, plain field getters/setters, and no-arg or super-only constructors.
	 */
	static boolean isTrivial(int access, String name, String descriptor) {
		if ((access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0) return true;
		if (name.startsWith("lambda$") || name.startsWith("access$")) return true;
		if (name.equals("<clinit>")) return true;
		Type methodType = Type.getType(descriptor);
		Type[] arguments = methodType.getArgumentTypes();
		Type returnType = methodType.getReturnType();
		if (name.equals("<init>")) return arguments.length == 0;
		boolean noArgNonVoidReturn = arguments.length == 0 && returnType.getSort() != Type.VOID;
		boolean singleArgVoidReturn = arguments.length == 1 && returnType.getSort() == Type.VOID;
		if (noArgNonVoidReturn && (name.startsWith("get") || name.startsWith("is"))) return true;
		if (singleArgVoidReturn && name.startsWith("set")) return true;
		return false;
	}
}
