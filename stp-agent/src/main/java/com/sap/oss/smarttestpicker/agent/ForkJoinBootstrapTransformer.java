// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.function.Consumer;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.*;

/** Verified JDK method bodies only. No schema/hierarchy/field changes during retransformation. */
final class ForkJoinBootstrapTransformer implements ClassFileTransformer {
	private static final String TASK = "java/util/concurrent/ForkJoinTask";
	private static final String POOL = "java/util/concurrent/ForkJoinPool";
	private static final Type BRIDGE = Type.getObjectType("com/sap/oss/smarttestpicker/agent/bootstrap/ForkJoinBridge");
	private static final Map<String, Integer> TASK_METHODS = Map.of(
			"fork()Ljava/util/concurrent/ForkJoinTask;", 0, "invoke()Ljava/lang/Object;", 0,
			"doExec()I", 1, "reinitialize()V", 2);
	private static final Map<String, Integer> POOL_METHODS = Map.of(
			"execute(Ljava/util/concurrent/ForkJoinTask;)V", 0,
			"submit(Ljava/util/concurrent/ForkJoinTask;)Ljava/util/concurrent/ForkJoinTask;", 0,
			"invoke(Ljava/util/concurrent/ForkJoinTask;)Ljava/lang/Object;", 0);
	private final Set<String> verified = new HashSet<>();
	private final Consumer<String> errors;
	ForkJoinBootstrapTransformer(Consumer<String> errors) { this.errors = errors; }
	boolean verified() { return verified.containsAll(Set.of(TASK, POOL)); }
	@Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
			ProtectionDomain domain, byte[] bytes) {
		if (loader != null || (!TASK.equals(name) && !POOL.equals(name))) return null;
		try {
			var reader = new ClassReader(bytes);
			var node = new ClassNode(); reader.accept(node, ClassReader.EXPAND_FRAMES);
			if (!name.equals(node.name)) throw new IllegalStateException("class name mismatch");
			var methods = TASK.equals(name) ? TASK_METHODS : POOL_METHODS;
			for (String signature : methods.keySet()) {
				if (node.methods.stream().filter(m -> (m.name + m.desc).equals(signature)
						&& (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0).count() != 1)
					throw new IllegalStateException("missing/ambiguous " + signature);
			}
			if (TASK.equals(name)) {
				var execution = node.methods.stream().filter(m -> m.name.equals("doExec") && m.desc.equals("()I")).findFirst().orElseThrow();
				long calls = Arrays.stream(execution.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c
						&& c.owner.equals(TASK) && c.name.equals("exec") && c.desc.equals("()Z")).count();
				if (calls != 1) throw new IllegalStateException("doExec -> exec count=" + calls);
				scopeExecutionCall(execution);
			}
			var writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			node.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
					MethodVisitor delegate = super.visitMethod(access, method, desc, signature, exceptions);
					Integer op = methods.get(method + desc);
					if (op == null || op == 1) return delegate;
					return new AdviceAdapter(Opcodes.ASM9, delegate, access, method, desc) {
						int ticket;
						final Label start = new Label(), end = new Label(), handler = new Label();
						@Override protected void onMethodEnter() {
							push(op);
							if (TASK.equals(name)) loadThis(); else loadArg(0);
							invokeStatic(BRIDGE, new Method("enter", "(ILjava/lang/Object;)Ljava/lang/Object;"));
							ticket = newLocal(Type.getType(Object.class)); storeLocal(ticket); mark(start);
						}
						private void exit(int failure) {
							push(op); loadLocal(ticket);
							if (failure < 0) visitInsn(ACONST_NULL); else loadLocal(failure);
							invokeStatic(BRIDGE, new Method("exit", "(ILjava/lang/Object;Ljava/lang/Throwable;)V"));
						}
						@Override protected void onMethodExit(int opcode) { if (opcode != ATHROW) exit(-1); }
						@Override public void visitMaxs(int stack, int locals) {
							mark(end); visitTryCatchBlock(start, end, handler, "java/lang/Throwable"); mark(handler);
							int failure = newLocal(Type.getType(Throwable.class)); storeLocal(failure);
							exit(failure); loadLocal(failure); throwException(); super.visitMaxs(stack, locals);
						}
					};
				}
			});
			verified.add(name);
			return writer.toByteArray();
		} catch (Throwable failure) {
			verified.remove(name); errors.accept("forkjoin-boundary-unverified:" + name + ":" + failure); return null;
		}
	}
	/** Restore before JDK publishes normal completion, and before its exception handler runs. */
	private static void scopeExecutionCall(MethodNode method) {
		for (var instruction : method.instructions.toArray()) {
			if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(TASK)
					|| !call.name.equals("exec") || !call.desc.equals("()Z")) continue;
			int ticket = method.maxLocals++, failure = method.maxLocals++;
			var start = new LabelNode(); var end = new LabelNode();
			var handler = new LabelNode(); var next = new LabelNode();
			var before = new InsnList();
			before.add(new InsnNode(Opcodes.DUP));
			before.add(new InsnNode(Opcodes.ICONST_1)); before.add(new InsnNode(Opcodes.SWAP));
			before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE.getInternalName(), "enter",
					"(ILjava/lang/Object;)Ljava/lang/Object;", false));
			before.add(new VarInsnNode(Opcodes.ASTORE, ticket)); before.add(start);
			method.instructions.insertBefore(call, before);
			var after = new InsnList(); after.add(end);
			after.add(new InsnNode(Opcodes.ICONST_1)); after.add(new VarInsnNode(Opcodes.ALOAD, ticket));
			after.add(new InsnNode(Opcodes.ACONST_NULL)); after.add(exitCall());
			after.add(new JumpInsnNode(Opcodes.GOTO, next));
			// Inline handler remains inside the JDK's original protected range: original exception semantics survive.
			after.add(handler); after.add(new VarInsnNode(Opcodes.ASTORE, failure));
			after.add(new InsnNode(Opcodes.ICONST_1)); after.add(new VarInsnNode(Opcodes.ALOAD, ticket));
			after.add(new VarInsnNode(Opcodes.ALOAD, failure)); after.add(exitCall());
			after.add(new VarInsnNode(Opcodes.ALOAD, failure)); after.add(new InsnNode(Opcodes.ATHROW)); after.add(next);
			method.instructions.insert(call, after);
			method.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
		}
	}
	private static MethodInsnNode exitCall() {
		return new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE.getInternalName(), "exit",
				"(ILjava/lang/Object;Ljava/lang/Throwable;)V", false);
	}

}
