// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

final class NoOpClassFileTransformer implements ClassFileTransformer {
	private final AgentConfiguration configuration;
	private final AgentMetrics metrics;

	public NoOpClassFileTransformer(AgentConfiguration configuration, AgentMetrics metrics) {
		this.configuration = configuration;
		this.metrics = metrics;
	}

	public ClassDecision decision(String internalClassName) {
		if (internalClassName == null) return ClassDecision.IGNORED;
		String binaryName = internalClassName.replace('/', '.');
		if (matchesExplicitExclusion(binaryName)) return ClassDecision.EXCLUDED;
		if (matches(binaryName, configuration.includes())) return ClassDecision.INCLUDED;
		if (matches(binaryName, configuration.excludes())) return ClassDecision.EXCLUDED;
		return ClassDecision.IGNORED;
	}

	private boolean matchesExplicitExclusion(String className) {
		for (String prefix : configuration.excludes()) {
			if (!AgentConfiguration.isMandatoryExclusion(prefix) && className.startsWith(prefix)) return true;
		}
		return false;
	}

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		long started = System.nanoTime();
		metrics.seen(loader != null, protectionDomain != null);
		try {
			switch (decision(className)) {
				case INCLUDED -> metrics.included();
				case EXCLUDED -> metrics.excluded();
				case IGNORED -> metrics.ignored();
			}
		} catch (RuntimeException failure) {
			metrics.error();
		} finally {
			metrics.addNanos(System.nanoTime() - started);
		}
		return null;
	}

	private static boolean matches(String className, Iterable<String> prefixes) {
		for (String prefix : prefixes) {
			if (className.startsWith(prefix)) return true;
		}
		return false;
	}
}
