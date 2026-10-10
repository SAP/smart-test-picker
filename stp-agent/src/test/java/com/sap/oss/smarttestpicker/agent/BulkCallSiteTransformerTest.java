// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class BulkCallSiteTransformerTest {
    static final String COLLECTION = "Ljava/util/Collection;";
    static final String TIME = "JLjava/util/concurrent/TimeUnit;";
    static byte[] caller(String owner, String name, String descriptor, int opcode) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/BulkCalls", null, "java/lang/Object", null);
        String args = descriptor.substring(1, descriptor.indexOf(')'));
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call",
                "(L" + owner + ";" + args + ")V", null, null);
        method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 1);
        if (args.endsWith(TIME)) { method.visitVarInsn(Opcodes.LLOAD, 2); method.visitVarInsn(Opcodes.ALOAD, 4); }
        method.visitMethodInsn(opcode, owner, name, descriptor, opcode == Opcodes.INVOKEINTERFACE);
        method.visitInsn(Opcodes.POP); method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    @Test void wrapsExactlyFourShapesAndPreservesInvocationAndWideTimeoutOperands() {
        for (String owner : List.of("java/util/concurrent/ExecutorService", "java/util/concurrent/ForkJoinPool",
                "java/util/concurrent/ThreadPoolExecutor")) {
            for (String name : List.of("invokeAll", "invokeAny")) for (boolean timed : List.of(false, true)) {
                String desc = "(" + COLLECTION + (timed ? TIME : "") + ")" + (name.equals("invokeAll") ? "Ljava/util/List;" : "Ljava/lang/Object;");
                int opcode = owner.endsWith("Service") ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                List<String> errors = new ArrayList<>();
                byte[] transformed = new ExecutorCallSiteTransformer(errors::add).transform(getClass().getClassLoader(),
                        "example/BulkCalls", null, null, caller(owner, name, desc, opcode));
                assertNotNull(transformed); assertTrue(errors.isEmpty(), errors.toString());
                var node = new ClassNode(); new ClassReader(transformed).accept(node, 0);
                var instructions = Arrays.asList(node.methods.get(0).instructions.toArray());
                var calls = instructions.stream().filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).toList();
                assertEquals(2, calls.size());
                assertEquals("wrapCallables", calls.get(0).name);
                assertEquals(owner, calls.get(1).owner); assertEquals(name, calls.get(1).name);
                assertEquals(desc, calls.get(1).desc); assertEquals(opcode, calls.get(1).getOpcode());
                assertEquals(timed ? 1 : 0, instructions.stream().filter(i -> i.getOpcode() == Opcodes.LSTORE).count());
            }
        }
    }
    @Test void uncertainHierarchyIsReportedButUnrelatedClassesAreNotInstrumented() {
        String desc = "(" + COLLECTION + ")Ljava/util/List;";
        for (String owner : List.of("unavailable/Executor", "java/lang/Object")) {
            List<String> errors = new ArrayList<>();
            byte[] result = new ExecutorCallSiteTransformer(errors::add).transform(getClass().getClassLoader(),
                    "example/BulkCalls", null, null, caller(owner, "invokeAll", desc, Opcodes.INVOKEVIRTUAL));
            assertNull(result);
            if (owner.startsWith("unavailable")) {
                assertEquals(1, errors.size()); assertTrue(errors.get(0).startsWith("bulk-executor-attribution-incomplete:"));
            } else assertTrue(errors.isEmpty());
        }
    }
    @Test void doesNotMatchStaticForkJoinTaskBulkApiOrBootstrapClasses() {
        var transformer = new ExecutorCallSiteTransformer(error -> fail(error));
        byte[] bytes = caller("java/util/concurrent/ForkJoinTask", "invokeAll", "(" + COLLECTION + ")Ljava/util/Collection;", Opcodes.INVOKESTATIC);
        assertNull(transformer.transform(getClass().getClassLoader(), "example/BulkCalls", null, null, bytes));
        assertNull(transformer.transform(null, "java/util/concurrent/ForkJoinPool", null, null, bytes));
    }
}
