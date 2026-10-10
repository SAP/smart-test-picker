// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class ForkJoinBootstrapTransformerTest {
	private static byte[] bytes(String name) throws Exception {
		try (var input = ClassLoader.getSystemResourceAsStream(name + ".class")) { return input.readAllBytes(); }
	}
	@Test void verifiesExactJdkBoundariesAndAddsOnlyMethodAdvice() throws Exception {
		List<String> errors = new ArrayList<>();
		var transformer = new ForkJoinBootstrapTransformer(errors::add);
		for (String name : List.of("java/util/concurrent/ForkJoinTask", "java/util/concurrent/ForkJoinPool")) {
			byte[] original = bytes(name);
			byte[] changed = transformer.transform(null, name, null, null, original);
			assertNotNull(changed, errors.toString());
			var before = new ClassNode(); new ClassReader(original).accept(before, 0);
			var after = new ClassNode(); new ClassReader(changed).accept(after, 0);
			assertEquals(before.fields.size(), after.fields.size());
			assertEquals(before.methods.size(), after.methods.size());
			long entries = after.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
					.filter(i -> i instanceof MethodInsnNode call && call.owner.endsWith("/ForkJoinBridge") && call.name.equals("enter")).count();
			assertEquals(name.endsWith("Task") ? 4 : 3, entries);
		}
		assertTrue(transformer.verified()); assertTrue(errors.isEmpty());
	}
	@Test void missingMethodOrChangedExecutionShapeLeavesBytesUnchangedWithDiagnostic() throws Exception {
		String name = "java/util/concurrent/ForkJoinTask";
		for (boolean missing : List.of(true, false)) {
			var node = new ClassNode(); new ClassReader(bytes(name)).accept(node, 0);
			var method = node.methods.stream().filter(m -> m.name.equals("doExec")).findFirst().orElseThrow();
			if (missing) node.methods.remove(method);
			else for (var i : method.instructions.toArray())
				if (i instanceof MethodInsnNode call && call.name.equals("exec")) call.name = "changedExec";
			var writer = new ClassWriter(0); node.accept(writer);
			List<String> errors = new ArrayList<>();
			var transformer = new ForkJoinBootstrapTransformer(errors::add);
			assertNull(transformer.transform(null, name, null, null, writer.toByteArray()));
			assertFalse(transformer.verified());
			assertEquals(1, errors.size()); assertTrue(errors.get(0).startsWith("forkjoin-boundary-unverified:"));
		}
	}
	@Test void ignoresApplicationClassesAndOtherJdkClasses() throws Exception {
		var transformer = new ForkJoinBootstrapTransformer(message -> fail(message));
		assertNull(transformer.transform(getClass().getClassLoader(), "java/util/concurrent/ForkJoinTask", null, null,
				bytes("java/util/concurrent/ForkJoinTask")));
		assertNull(transformer.transform(null, "java/lang/Thread", null, null, bytes("java/lang/Thread")));
	}
}
