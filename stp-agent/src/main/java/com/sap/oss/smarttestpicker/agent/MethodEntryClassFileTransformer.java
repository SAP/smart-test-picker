// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.function.Consumer;

final class MethodEntryClassFileTransformer implements ClassFileTransformer {
	static final String MARKER_FIELD = "$stp$instrumented$v1";
	private static final String HOOK_OWNER = "com/sap/oss/smarttestpicker/runtime/RuntimeHooks";

	private final NoOpClassFileTransformer classifier;
	private final AgentMetrics metrics;
	private final MethodCatalog catalog;
	private final Consumer<String> errorSink;

	public MethodEntryClassFileTransformer(AgentConfiguration configuration, AgentMetrics metrics,
			MethodCatalog catalog, Consumer<String> errorSink) {
		this.classifier = new NoOpClassFileTransformer(configuration, metrics);
		this.metrics = metrics;
		this.catalog = catalog;
		this.errorSink = errorSink;
	}

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		long started = System.nanoTime();
		metrics.seen(loader != null, protectionDomain != null);
		try {
			ClassDecision decision = classifier.decision(className);
			if (decision == ClassDecision.EXCLUDED) {
				metrics.excluded();
				return null;
			}
			if (decision == ClassDecision.IGNORED) {
				metrics.ignored();
				return null;
			}
			if (isTestClassLocation(protectionDomain)) {
				metrics.ignored();
				return null;
			}
			if (isGeneratedFrameworkClass(className)) {
				metrics.ignored();
				return null;
			}
			metrics.included();
			return instrument(className, classfileBuffer);
		} catch (Throwable failure) {
			// This is an observation boundary: even a collector/ASM JVM error must not
			// escape into class loading and change the application's behavior.
			metrics.error();
			errorSink.accept("transformation-error:" + safeClassName(className) + ":"
					+ failure.getClass().getName());
			return null;
		} finally {
			metrics.addNanos(System.nanoTime() - started);
		}
	}

	private static boolean isGeneratedFrameworkClass(String className) {
		return className != null && (className.contains("$$") || className.contains("$MockitoMock$")
				|| className.contains("$ByteBuddy$") || className.contains("$HibernateProxy")
				|| className.contains("$HibernateInstantiator"));
	}

	private static boolean isTestClassLocation(ProtectionDomain protectionDomain) {
		if (protectionDomain == null || protectionDomain.getCodeSource() == null
				|| protectionDomain.getCodeSource().getLocation() == null) return false;
		String path = protectionDomain.getCodeSource().getLocation().getPath().replace('\\', '/');
		return path.contains("/test-classes/") || path.endsWith("/test-classes");
	}

	private byte[] instrument(String internalClassName, byte[] original) {
		if (original == null || "module-info".equals(internalClassName)) return null;
		ClassReader reader = new ClassReader(original);
		ClassNode node = new ClassNode(Opcodes.ASM9);
		reader.accept(node, 0);
		if ((node.access & Opcodes.ACC_ANNOTATION) != 0) {
			node.methods.forEach(ignored -> {
				metrics.methodConsidered();
				metrics.methodSkipped();
			});
			return null;
		}
		if (node.fields.stream().anyMatch(field -> MARKER_FIELD.equals(field.name))) {
			metrics.alreadyInstrumented();
			return null;
		}

		String binaryClassName = node.name.replace('/', '.');
		int instrumented = 0;
		for (MethodNode method : node.methods) {
			metrics.methodConsidered();
			if (method.name.equals("$jacocoInit")
					|| (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
				metrics.methodSkipped();
				continue;
			}
			MethodIdentity identity = new MethodIdentity(binaryClassName, method.name, method.desc);
			long methodId = catalog.register(identity);
			InsnList hook = new InsnList();
			hook.add(new LdcInsnNode(methodId));
			hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "methodHit", "(J)V", false));
			AbstractInsnNode first = method.instructions.getFirst();
			if (first == null) {
				metrics.methodSkipped();
				continue;
			}
			method.instructions.insertBefore(first, hook);
			metrics.methodInstrumented();
			instrumented++;
		}
		if (instrumented == 0) return null;
		int markerAccess = Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
		markerAccess |= (node.access & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
		node.fields.add(new FieldNode(markerAccess, MARKER_FIELD, "Z", null, Boolean.TRUE));
		ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		metrics.classTransformed();
		return writer.toByteArray();
	}

	private static String safeClassName(String className) {
		return className == null ? "<unknown>" : className.replace('/', '.');
	}
}
