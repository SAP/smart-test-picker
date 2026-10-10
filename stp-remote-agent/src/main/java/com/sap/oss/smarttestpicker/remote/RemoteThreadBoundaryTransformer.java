// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

/** Only configured application Thread subclasses; no JDK or infrastructure class transformation. */
final class RemoteThreadBoundaryTransformer implements ClassFileTransformer {
    static final String HELPER = "com/sap/oss/smarttestpicker/remote/RemoteThreadContext";
    private final RemoteAgentConfiguration configuration;
    RemoteThreadBoundaryTransformer(RemoteAgentConfiguration configuration) { this.configuration = configuration; }

    static boolean isThread(String name, ClassLoader loader, ClassNode current) {
        return isThread(name, loader, current, new HashSet<>());
    }
    private static boolean isThread(String name, ClassLoader loader, ClassNode current, Set<String> visited) {
        if (name.equals("java/lang/Thread")) return true;
        if (name.equals("java/lang/Object") || !visited.add(name)) return false;
        try {
            String parent;
            if (name.equals(current.name)) parent = current.superName;
            else try (InputStream in = loader.getResourceAsStream(name + ".class")) {
                if (in == null) return false;
                parent = new ClassReader(in).getSuperName();
            }
            return parent != null && isThread(parent, loader, current, visited);
        } catch (Exception | LinkageError unavailable) { return false; }
    }
    static boolean visible(ClassLoader loader) {
        try { return Class.forName(HELPER.replace('/', '.'), false, loader) == RemoteThreadContext.class
                && Class.forName("io.opentelemetry.context.Scope", false, loader) == io.opentelemetry.context.Scope.class; }
        catch (ClassNotFoundException | LinkageError inaccessible) { return false; }
    }
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        if (loader == null || name == null || bytes == null || name.startsWith("java/") || name.startsWith("jdk/")
                || name.startsWith("com/sap/oss/smarttestpicker/remote/") || !configuration.instruments(name.replace('/', '.'))) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            if (!isThread(name, loader, node)) return null;
            if (!visible(loader)) {
                System.err.println("[stp-remote-agent] thread-boundary-unavailable: " + name + " cannot resolve shared RemoteThreadContext");
                return null;
            }
            boolean[] changed = {false};
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected String getCommonSuperClass(String a, String b) {
                    try {
                        Class<?> left = Class.forName(a.replace('/', '.'), false, loader);
                        Class<?> right = Class.forName(b.replace('/', '.'), false, loader);
                        if (left.isAssignableFrom(right)) return a;
                        if (right.isAssignableFrom(left)) return b;
                        if (left.isInterface() || right.isInterface()) return "java/lang/Object";
                        do { left = left.getSuperclass(); } while (!left.isAssignableFrom(right));
                        return Type.getInternalName(left);
                    } catch (ClassNotFoundException | LinkageError unavailable) { throw new IllegalStateException("Cannot resolve frame hierarchy", unavailable); }
                }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, method, desc, signature, exceptions);
                    if (!method.equals("run") || !desc.equals("()V") || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) return delegate;
                    changed[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, method, desc) {
                        final Type scope = Type.getType("Lio/opentelemetry/context/Scope;");
                        final Label start = new Label();
                        int local;
                        @Override protected void onMethodEnter() {
                            loadThis();
                            invokeStatic(Type.getObjectType(HELPER), new Method("enter", "(Ljava/lang/Thread;)Lio/opentelemetry/context/Scope;"));
                            local = newLocal(scope); storeLocal(local); mark(start);
                        }
                        void closeScope() { loadLocal(local); invokeInterface(scope, new Method("close", "()V")); }
                        @Override protected void onMethodExit(int opcode) { if (opcode != ATHROW) closeScope(); }
                        @Override public void visitMaxs(int maxStack, int maxLocals) {
                            Label end = new Label(), handler = new Label();
                            mark(end); visitTryCatchBlock(start, end, handler, null); mark(handler);
                            int thrown = newLocal(Type.getType(Throwable.class)); storeLocal(thrown);
                            closeScope(); loadLocal(thrown); throwException(); super.visitMaxs(maxStack, maxLocals);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return changed[0] ? writer.toByteArray() : null;
        } catch (Exception | LinkageError failure) {
            System.err.println("[stp-remote-agent] thread-boundary-unverified: " + name + ": " + failure);
            return null;
        }
    }
}
